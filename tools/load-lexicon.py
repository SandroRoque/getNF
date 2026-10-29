#!/usr/bin/env python3
"""Loads abbreviation translations (`QJO` -> queijo / cheese) into the Supabase `lexicon` table (D59, 4b).

  python3 tools/load-lexicon.py [--local] [file]   # default file: test-media/categorize/lexicon-pure.tsv

The file comes from the categorization eval and is built from real receipts, so it stays out of git; the table is
what the `categorize` function reads. Existing keys are updated; nothing is deleted. --local loads the local
Supabase (npx supabase start); without it, the online project (.env.local, password never printed).
Format: key<TAB>portuguese<TAB>english, `#` comments; phrases (with spaces) are matched before single words.
"""
import argparse, os, subprocess, sys, urllib.parse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def online_url():
  for path in (os.path.join(ROOT, ".env.local"), os.path.join(ROOT, "..", "..", "..", ".env.local")):
    if os.path.exists(path):
      env = dict(l.strip().split("=", 1) for l in open(path) if "=" in l and not l.lstrip().startswith("#"))
      ref = urllib.parse.urlparse(env["SUPABASE_URL"]).hostname.split(".")[0]
      return f"postgresql://postgres:{urllib.parse.quote(env['SUPABASE_DB_PASSWORD'], safe='')}@db.{ref}.supabase.co:5432/postgres?sslmode=require"
  sys.exit("no .env.local")


def q(text):
  return "'" + text.replace("'", "''") + "'"


def main():
  p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
  p.add_argument("file", nargs="?", default=os.path.join(ROOT, "test-media", "categorize", "lexicon-pure.tsv"))
  p.add_argument("--local", action="store_true")
  args = p.parse_args()
  rows = []
  for line in open(args.file, encoding="utf-8"):
    if line.strip() and not line.startswith("#"):
      key, pt, en = line.rstrip("\n").split("\t")
      rows.append(f"({q(key.upper())}, {q(pt)}, {q(en)})")
  sql = ("insert into public.lexicon (key, pt, en) values\n" + ",\n".join(rows) +
         "\non conflict (key) do update set pt = excluded.pt, en = excluded.en, updated_at = clock_timestamp();\n"
         "select count(*) from public.lexicon;")
  url = "postgresql://postgres:postgres@127.0.0.1:54322/postgres" if args.local else online_url()
  out = subprocess.run(["psql", url, "-Atq", "-v", "ON_ERROR_STOP=1"], input=sql, capture_output=True, text=True)
  if out.returncode:
    sys.exit(out.stderr.strip())
  print(f"{len(rows)} entries loaded; the table now has {out.stdout.strip()}")


if __name__ == "__main__":
  main()
