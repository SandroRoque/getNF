#!/usr/bin/env python3
"""What testers sent during the closed test (D161): the `contribution_file` table and the private `contributions`
bucket, read with the secret key. Nothing else lists them (the app writes through the `contribute` Edge Function).

  python3 tools/contributions.py list [--days 7]       # per install, each item (a capture or a page) and its files
  python3 tools/contributions.py fetch 3f2a 9c01       # items (or whole installs) by id prefix, into test-media/
  python3 tools/contributions.py fetch --all           # everything not downloaded yet
  ... --local                                          # against the local Supabase (npx supabase start)

Files land in test-media/contributions/<install>/<item>/ (gitignored): they're real receipts (photos, the SEFAZ page,
and meta.json with the chave), so they never go into the repo as-is; fixtures go through tools/make-nfce-fixture.py.
An item is complete once its meta.json arrived (the app sends it last). Reads SUPABASE_URL and SUPABASE_SECRET_KEY
from .env.local, never prints them.
"""
import argparse, datetime, os, subprocess, sys, urllib.error, urllib.parse, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tickets import ROOT, Supabase, env, when  # noqa: E402

BUCKET = "contributions"
OUT = os.path.join(ROOT, "test-media", "contributions")
PAGE_ROWS = 1000  # PostgREST's default max rows on Supabase


def connect(local):
  if local:
    out = subprocess.run(["npx", "supabase", "status", "-o", "env"], cwd=ROOT, capture_output=True, text=True).stdout
    vals = dict(l.split("=", 1) for l in out.splitlines() if "=" in l)
    return Supabase(vals["API_URL"].strip('"'), vals["SECRET_KEY"].strip('"'))
  e = env()
  return Supabase(e["SUPABASE_URL"], e["SUPABASE_SECRET_KEY"])


def rows(db, days=None):
  """Every file row, oldest first, in pages (the table can pass PostgREST's 1000-row cap within a day)."""
  params = {"order": "received_at,install_id,item_id,name"}
  if days:
    since = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(days=days)
    params["received_at"] = "gte." + since.isoformat()
  out, offset = [], 0
  while True:
    page = db.call("GET", "contribution_file", select="*", limit=PAGE_ROWS, offset=offset, **params)
    out += page
    if len(page) < PAGE_ROWS:
      return out
    offset += PAGE_ROWS


def items(files):
  """{install: {item: [rows]}}, in the order they arrived."""
  by = {}
  for f in files:
    by.setdefault(f["install_id"], {}).setdefault(f["item_id"], []).append(f)
  return by


def describe(fs):
  names = {f["name"] for f in fs}
  photos = sum(n.endswith(".webp") for n in names)
  page = "page.html" in names
  parts = [f"{photos} foto(s)" if photos or not page else "", "página" if page else "",
           "" if "meta.json" in names else "INCOMPLETO (sem meta.json)"]
  return "  ".join(p for p in parts if p)


def mb(n):
  return f"{n / 1_048_576:.1f} MB" if n >= 1_048_576 else f"{max(1, round(n / 1024))} KB"


def local_path(f):
  return os.path.join(OUT, f["install_id"], f["item_id"], f["name"])


def downloaded(fs):
  return all(os.path.isfile(local_path(f)) and os.path.getsize(local_path(f)) == f["bytes"] for f in fs)


def download(db, f):
  path = f"{f['install_id']}/{f['item_id']}/{f['name']}"
  req = urllib.request.Request(f"{db.url}/storage/v1/object/{BUCKET}/{urllib.parse.quote(path)}",
                               headers={"apikey": db.key, "Authorization": "Bearer " + db.key})
  try:
    with urllib.request.urlopen(req, timeout=120) as r:
      body = r.read()
  except urllib.error.HTTPError as e:
    sys.exit(f"GET {path}: {e.code} {e.read().decode()[:300]}")
  dest = local_path(f)
  os.makedirs(os.path.dirname(dest), exist_ok=True)
  with open(dest + ".part", "wb") as out:
    out.write(body)
  os.replace(dest + ".part", dest)
  return len(body)


def main():
  p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
  p.add_argument("--local", action="store_true")
  sub = p.add_subparsers(dest="cmd", required=True)
  s = sub.add_parser("list"); s.add_argument("--days", type=float)
  s = sub.add_parser("fetch"); s.add_argument("ids", nargs="*"); s.add_argument("--all", action="store_true")
  a = p.parse_args()
  db = connect(a.local)

  if a.cmd == "list":
    by = items(rows(db, a.days))
    if not by:
      print("nada recebido" + (f" nos últimos {a.days:g} dia(s)" if a.days else ""))
      return
    n_items = n_files = n_bytes = incomplete = 0
    for install, its in by.items():
      size = sum(f["bytes"] for fs in its.values() for f in fs)
      print(f"{install[:8]}  {len(its)} item(ns), {mb(size)}")
      for item, fs in its.items():
        mark = "  baixado" if downloaded(fs) else ""
        print(f"  {item[:8]}  {when(max(f['received_at'] for f in fs))}  {describe(fs)}  {mb(sum(f['bytes'] for f in fs))}{mark}")
        n_items += 1; n_files += len(fs); n_bytes += sum(f["bytes"] for f in fs)
        incomplete += not any(f["name"] == "meta.json" for f in fs)
    print(f"{len(by)} instalação(ões), {n_items} item(ns), {n_files} arquivo(s), {mb(n_bytes)}"
          + (f"; {incomplete} incompleto(s)" if incomplete else ""))

  elif a.cmd == "fetch":
    if not a.ids and not a.all:
      sys.exit("which? ids (prefixes of an install or an item), or --all")
    by = items(rows(db))
    chosen = []
    for install, its in by.items():
      for item, fs in its.items():
        if a.all or any(install.startswith(x) or item.startswith(x) for x in a.ids):
          chosen.append((item, fs))
    if not chosen:
      sys.exit("nothing matches " + " ".join(a.ids))
    got = files = 0
    for item, fs in chosen:
      todo = [f for f in fs if not downloaded([f])]
      for f in todo:
        got += download(db, f); files += 1
    print(f"{files} arquivo(s) baixados ({mb(got)}) de {len(chosen)} item(ns), em {os.path.relpath(OUT, ROOT)}/")


if __name__ == "__main__":
  main()
