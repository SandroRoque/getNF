#!/usr/bin/env python3
"""Blanks the buyer from SEFAZ pages already in Supabase Storage (D104): the "Consumidor" block's CPF/CNPJ, name and
address, and any CPF elsewhere on the page. Pages saved by the app since D104 come blanked; this cleans the older ones.
Same rules as the phone's BuyerRedaction.kt. Reads SUPABASE_URL and SUPABASE_SECRET_KEY like supabase-backup.py.

  python3 tools/blank-buyers.py            # report what would change (counts only, never the contents)
  python3 tools/blank-buyers.py --write    # replace those pages
"""
import argparse, importlib.util, json, os, re, time, urllib.error, urllib.request

REMOVED = "Consumidor removido pelo getNF"
# The block is found by its heading's text, in the server's shape or the WebView's jQuery Mobile one (T-30, D180).
HEADING = re.compile(r"<h4\b[^>]*>(.*?)</h4\s*>", re.I | re.S)
LIST = re.compile(r"(<ul\b[^>]*>)(.*?)(</ul\s*>)", re.I | re.S)
STATUS = re.compile(r"<span\b[^>]*ui-collapsible-heading-status[^>]*>.*?</span\s*>", re.I | re.S)
TAG = re.compile(r"<[^>]*>")
CPF = re.compile(r"(?<![\d.])\d{3}\.\d{3}\.\d{3}-\d{2}(?![\d])")


def label(inner):
  return " ".join(TAG.sub(" ", STATUS.sub("", inner)).split()).lower()


def blank(html):
  out, copied = [], 0
  for h in HEADING.finditer(html):
    if h.start() < copied or label(h.group(1)) != "consumidor": continue
    after = HEADING.search(html, h.end())
    nxt = after.start() if after else len(html)
    l = LIST.search(html, h.end())
    if not l or l.end() > nxt: continue
    inside = l.group(2)
    if "não identificado" in inside.lower() or REMOVED in inside: continue
    out += [html[copied:l.end(1)], f"<li><strong>{REMOVED}</strong></li>"]
    copied = l.start(3)
  out.append(html[copied:])
  return CPF.sub("***.***.***-**", "".join(out))


def main():
  p = argparse.ArgumentParser(); p.add_argument("--write", action="store_true"); args = p.parse_args()
  spec = importlib.util.spec_from_file_location("backup", os.path.join(os.path.dirname(__file__), "supabase-backup.py"))
  backup = importlib.util.module_from_spec(spec); spec.loader.exec_module(backup)
  e = backup.env(); base = e["SUPABASE_URL"].rstrip("/"); key = e["SUPABASE_SECRET_KEY"]
  auth = {"apikey": key, "Authorization": "Bearer " + key}

  def get(path):
    for attempt in range(4):
      try:
        return urllib.request.urlopen(urllib.request.Request(base + path, headers=auth)).read()
      except urllib.error.HTTPError as err:
        if err.code < 500 or attempt == 3: raise
        time.sleep(2 ** attempt)

  rows = json.loads(get("/rest/v1/receipt?select=page_path&page_path=not.is.null"))
  changed = 0
  for r in rows:
    path = r["page_path"]
    html = get("/storage/v1/object/receipts/" + path).decode("utf-8")
    clean = blank(html)
    if clean == html: continue
    changed += 1
    if args.write:
      urllib.request.urlopen(urllib.request.Request(base + "/storage/v1/object/receipts/" + path, clean.encode("utf-8"), method="POST",
                                                    headers={**auth, "content-type": "text/html; charset=utf-8", "x-upsert": "true"})).read()
  print(f"{len(rows)} pages, {changed} with a buyer or a CPF" + (" — replaced" if args.write else " (dry run; --write replaces them)"))


if __name__ == "__main__":
  main()
