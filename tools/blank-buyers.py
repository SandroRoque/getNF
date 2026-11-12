#!/usr/bin/env python3
"""Blanks the buyer from SEFAZ pages already in Supabase Storage (D104): the "Consumidor" block's CPF/CNPJ, name and
address, and any CPF elsewhere on the page. Pages saved by the app since D104 come blanked; this cleans the older ones.
Same rules as the phone's BuyerRedaction.kt. Reads SUPABASE_URL and SUPABASE_SECRET_KEY like supabase-backup.py.

  python3 tools/blank-buyers.py            # report what would change (counts only, never the contents)
  python3 tools/blank-buyers.py --write    # replace those pages

Every receipt with a page is read, a page at a time by key (T-24: PostgREST answers at most 1,000 rows a request), and
a page whose object is missing is counted and skipped instead of stopping the run. Only the `receipts` bucket: the
`contributions` bucket's pages were blanked on the phone before they went (D104). The PC backup's mirror takes the
blanked copies at its next run (the objects changed), and its earlier copies expire with it (D191).
"""
import argparse, importlib.util, json, os, re, time, urllib.error, urllib.parse, urllib.request

PAGE = 500

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


def pages(get, size=PAGE):
  """Every receipt's page path, in key order, [size] rows a request: none missed or repeated."""
  after = None
  while True:
    query = "select=ledger_id,chave,page_path&page_path=not.is.null&order=ledger_id,chave&limit=%d" % size
    if after:
      lid, chave = after
      query += "&or=" + urllib.parse.quote("(ledger_id.gt.%s,and(ledger_id.eq.%s,chave.gt.%s))" % (lid, lid, chave))
    rows = json.loads(get("/rest/v1/receipt?" + query))
    yield from (r["page_path"] for r in rows)
    if len(rows) < size: return
    after = rows[-1]["ledger_id"], rows[-1]["chave"]


def run(get, put, write):
  """(pages, changed, missing): what blanking every page found, and replaced when [write]."""
  total = changed = missing = 0
  for path in pages(get):
    total += 1
    try:
      html = get("/storage/v1/object/receipts/" + path).decode("utf-8")
    except urllib.error.HTTPError as err:
      if err.code not in (400, 404): raise
      missing += 1  # the row names an object Storage doesn't have (NoSuchKey comes as 400)
      continue
    clean = blank(html)
    if clean == html: continue
    changed += 1
    if write: put("/storage/v1/object/receipts/" + path, clean.encode("utf-8"))
  return total, changed, missing


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

  def put(path, body):
    urllib.request.urlopen(urllib.request.Request(base + path, body, method="POST",
                                                  headers={**auth, "content-type": "text/html; charset=utf-8", "x-upsert": "true"})).read()

  total, changed, missing = run(get, put, args.write)
  print(f"{total} pages, {changed} with a buyer or a CPF" + (f", {missing} missing in Storage" if missing else "")
        + (" — replaced" if args.write else " (dry run; --write replaces them)"))


if __name__ == "__main__":
  main()
