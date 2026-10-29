#!/usr/bin/env python3
"""Backs up the online Supabase project to this PC (D65): the database and every stored file.

  python3 tools/supabase-backup.py            # into ~/getnf-backups/supabase/
  python3 tools/supabase-backup.py --check    # only report what's there (sizes, counts, age)

- Database: `pg_dump` of the app's data (schema `public`, minus the taxonomy seed, which the migrations load) plus
  the accounts (`auth.users`, `auth.identities`), data
  only, custom format, one file per run; the newest KEEP runs are kept. The schema itself is in supabase/migrations.
- Files (pages, archive photos, masks in bucket `receipts`): mirrored into files/, downloading only what's new or
  changed. Files deleted online stay here.

Reads SUPABASE_URL, SUPABASE_SECRET_KEY and SUPABASE_DB_PASSWORD from .env.local and never prints them.
The restore drill (tools/supabase-restore-drill.sh) proves a dump can be read back.
"""
import argparse, datetime, json, os, subprocess, sys, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KEEP = 14


def env():
  values = {}
  for path in (os.path.join(ROOT, ".env.local"), os.path.join(ROOT, "..", "..", "..", ".env.local")):
    if os.path.exists(path):
      for line in open(path):
        if "=" in line and not line.lstrip().startswith("#"):
          k, v = line.strip().split("=", 1)
          values[k] = v
      break
  missing = [k for k in ("SUPABASE_URL", "SUPABASE_SECRET_KEY", "SUPABASE_DB_PASSWORD") if not values.get(k)]
  if missing:
    sys.exit(f"missing in .env.local: {', '.join(missing)}")
  return values


def db_url(e):
  """Without the password: a failed pg_dump prints its arguments, and they end up in the journal. See pg_env."""
  ref = urllib.parse.urlparse(e["SUPABASE_URL"]).hostname.split(".")[0]
  return f"postgresql://postgres@db.{ref}.supabase.co:5432/postgres?sslmode=require"


def pg_env(e):
  return {**os.environ, "PGPASSWORD": e["SUPABASE_DB_PASSWORD"]}


def storage(e, path, body=None):
  req = urllib.request.Request(e["SUPABASE_URL"].rstrip("/") + "/storage/v1/" + path,
                               json.dumps(body).encode() if body is not None else None,
                               {"apikey": e["SUPABASE_SECRET_KEY"], "Authorization": "Bearer " + e["SUPABASE_SECRET_KEY"],
                                "content-type": "application/json"})
  with urllib.request.urlopen(req, timeout=120) as r:
    return r.read()


def objects(e, prefix=""):
  """Every file under prefix in bucket `receipts`, as (path, size, updated_at)."""
  offset = 0
  while True:
    page = json.loads(storage(e, "object/list/receipts", {"prefix": prefix, "limit": 1000, "offset": offset,
                                                          "sortBy": {"column": "name", "order": "asc"}}))
    for o in page:
      path = f"{prefix}/{o['name']}" if prefix else o["name"]
      if o.get("id") is None:  # a folder
        yield from objects(e, path)
      else:
        yield path, (o.get("metadata") or {}).get("size"), o.get("updated_at")
    if len(page) < 1000:
      return
    offset += 1000


def dump(e, out_dir):
  stamp = datetime.datetime.now().strftime("%Y-%m-%d_%H%M%S")
  target = os.path.join(out_dir, f"db-{stamp}.dump")
  # Tables only, not --schema=public: pg_dump ignores --schema when any --table is given (the first drill caught it).
  subprocess.run(["pg_dump", "--format=custom", "--data-only", "--table=public.*", "--exclude-table=public.taxonomy_*",
                  "--table=auth.users",
                  "--table=auth.identities", f"--file={target}.part", db_url(e)], check=True, env=pg_env(e))
  os.replace(target + ".part", target)
  dumps = sorted(f for f in os.listdir(out_dir) if f.startswith("db-") and f.endswith(".dump"))
  for old in dumps[:-KEEP]:
    os.remove(os.path.join(out_dir, old))
  return target


def mirror(e, files_dir):
  index_path = os.path.join(files_dir, ".index.json")
  index = json.load(open(index_path)) if os.path.exists(index_path) else {}
  fetched = total = 0
  for path, size, updated in objects(e):
    total += 1
    local = os.path.join(files_dir, path)
    if index.get(path) == updated and os.path.exists(local):
      continue
    os.makedirs(os.path.dirname(local), exist_ok=True)
    data = storage(e, "object/receipts/" + urllib.parse.quote(path))
    with open(local + ".part", "wb") as f:
      f.write(data)
    os.replace(local + ".part", local)
    index[path] = updated
    fetched += 1
  with open(index_path + ".part", "w") as f:
    json.dump(index, f)
  os.replace(index_path + ".part", index_path)
  return fetched, total


def check(out_dir):
  dumps = sorted(f for f in os.listdir(out_dir) if f.endswith(".dump")) if os.path.isdir(out_dir) else []
  if not dumps:
    print("no backup yet"); return 1
  newest = os.path.join(out_dir, dumps[-1])
  age = datetime.datetime.now() - datetime.datetime.fromtimestamp(os.path.getmtime(newest))
  files = sum(len([f for f in fs if not f.startswith(".")]) for _, _, fs in os.walk(os.path.join(out_dir, "files")))
  print(f"{len(dumps)} database dumps, newest {dumps[-1]} ({os.path.getsize(newest)} bytes, {age.total_seconds() / 3600:.1f} h old); {files} files")
  return 0 if age < datetime.timedelta(hours=36) else 2


def main():
  p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
  p.add_argument("--out", default=os.path.expanduser("~/getnf-backups/supabase"))
  p.add_argument("--check", action="store_true")
  args = p.parse_args()
  if args.check:
    sys.exit(check(args.out))
  e = env()
  os.makedirs(os.path.join(args.out, "files"), exist_ok=True)
  target = dump(e, args.out)
  fetched, total = mirror(e, os.path.join(args.out, "files"))
  print(f"database -> {target} ({os.path.getsize(target)} bytes); files: {fetched} new or changed of {total}")


if __name__ == "__main__":
  main()
