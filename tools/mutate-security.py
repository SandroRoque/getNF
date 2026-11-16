#!/usr/bin/env python3
"""Checks the server's security tests by weakening the server (D208).

Each access rule of the local Supabase's database is weakened on purpose, one at a time, and the pgTAP suite
(supabase/tests/database) is run against it. A weakening the suite doesn't notice (a "survivor") is a hole in the
tests: that rule could be lost in a migration and nothing would say so.

What is weakened (the rules are read from the database itself, so new tables and functions are covered unasked):
  policy    an RLS policy's USING or WITH CHECK becomes true, loses one of its conditions, or takes members for managers
  rls       a table's row level security is switched off
  grant     a table is granted in full to a role that has less of it; a function a role can't call is granted to it
  guard     a function's "if ... then raise exception" loses one of its conditions, or goes altogether
  caller    a function's "= auth.uid()" becomes true (it stops asking who is calling)
  path      a SECURITY DEFINER function loses its pinned search_path
  trigger   a trigger is switched off
  bucket    a storage bucket becomes public, or loses its size or type limit

Nothing stays changed: a weakening is written into the test file's own transaction, right after its `begin;`, and
goes with its `rollback;`. Only the local database is ever used (127.0.0.1:54322); the online project is never touched.

  tools/mutate-security.py                 run everything, print the survivors
  tools/mutate-security.py --list          the weakenings, without running
  tools/mutate-security.py --only guard    those whose id contains the text
  tools/mutate-security.py --check         exit 1 on a survivor that isn't in supabase/tests/mutation-accepted.txt
                                           (or on an accepted one that no longer survives or exists)
"""
import argparse
import collections
import concurrent.futures
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TESTS = ROOT / "supabase" / "tests" / "database"
ACCEPTED = ROOT / "supabase" / "tests" / "mutation-accepted.txt"
# supabase_admin is the local stack's superuser: storage's policies belong to another role, which postgres can't alter.
# The tests themselves still run as postgres, as under `npx supabase test db`.
DB = dict(host=os.environ.get("GETNF_DB_HOST", "127.0.0.1"), port=os.environ.get("GETNF_DB_PORT", "54322"))
APP_ROLES = ("anon", "authenticated")
SCHEMAS = "('public', 'storage')"


def psql(sql, timeout=120):
  if DB["host"] not in ("127.0.0.1", "localhost", "::1"):
    sys.exit("mutate-security: only a local database is ever weakened")
  env = dict(os.environ, PGPASSWORD=os.environ.get("GETNF_DB_PASSWORD", "postgres"), PGOPTIONS="-c client_min_messages=warning")
  try:
    run = subprocess.run(["psql", "-h", DB["host"], "-p", DB["port"], "-U", "supabase_admin", "-d", "postgres", "-X", "-q", "-At",
                          "-F", "\x1f", "-R", "\x1e", "-f", "-"], input=sql, capture_output=True, text=True, env=env, timeout=timeout)
  except subprocess.TimeoutExpired:
    return "", "timed out"
  return run.stdout, run.stderr


def rows(sql):
  out, err = psql(sql)
  if err.strip():
    sys.exit(f"mutate-security: {err.strip()}")
  return [r.split("\x1f") for r in out.rstrip("\n").split("\x1e") if r.strip("\n")]


# --- Splitting conditions -------------------------------------------------------------------------------------------

def split_top(text, word):
  """The parts of `text` between `word` (and/or) at parenthesis depth 0, outside quotes."""
  parts, depth, quote, start, i = [], 0, False, 0, 0
  pattern = re.compile(rf"\s+{word}\s+", re.I)
  while i < len(text):
    c = text[i]
    if c == "'":
      quote = not quote
    elif not quote and c == "(":
      depth += 1
    elif not quote and c == ")":
      depth -= 1
    elif not quote and depth == 0 and c.isspace():
      m = pattern.match(text, i)
      if m:
        parts.append(text[start:i]); start = i = m.end()
        continue
    i += 1
  parts.append(text[start:])
  return [p.strip() for p in parts]


def unwrap(text):
  """`text` without parentheses that enclose all of it."""
  text = text.strip()
  while text.startswith("(") and text.endswith(")"):
    depth = 0
    for i, c in enumerate(text):
      depth += (c == "(") - (c == ")")
      if depth == 0 and i < len(text) - 1:
        return text
    text = text[1:-1].strip()
  return text


GUARD = re.compile(r"\bif\s+((?:(?!\bthen\b).)+?)\s+then\s+raise\s+exception\b[^;]*;\s*end\s+if\s*;", re.I | re.S)
CALLER = re.compile(r"[\w.]+\s*=\s*\(\s*select\s+auth\.uid\(\)\s*\)|[\w.]+\s*=\s*auth\.uid\(\)", re.I)


def guard_mutants(definition):
  """(label, new definition) for each way a function's refusals can be weakened."""
  out = []
  for n, m in enumerate(GUARD.finditer(definition), 1):
    alternatives = split_top(m.group(1), "or")
    if len(alternatives) == 1:
      out.append((f"guard{n}=gone", definition[:m.start()] + definition[m.end():]))
    else:
      for k in range(len(alternatives)):
        rest = " or ".join(a for j, a in enumerate(alternatives) if j != k)
        out.append((f"guard{n}-without-or{k + 1}", definition[:m.start(1)] + rest + definition[m.end(1):]))
  for n, m in enumerate(CALLER.finditer(definition), 1):
    out.append((f"caller{n}=anyone", definition[:m.start()] + "true" + definition[m.end():]))
  return out


def clause_mutants(expr):
  """(label, new expression) for each way a policy's condition can be weakened."""
  out = [("true", "true")]
  conditions = split_top(unwrap(expr), "and")
  if len(conditions) > 1:
    for k in range(len(conditions)):
      out.append((f"without-and{k + 1}", " AND ".join(c for j, c in enumerate(conditions) if j != k)))
  if "my_managed_ledgers" in expr:
    out.append(("members-for-managers", expr.replace("my_managed_ledgers", "my_ledgers")))
  return out


# --- The weakenings, from the catalog ------------------------------------------------------------------------------

def mutants():
  found = []
  add = lambda id, sql, about: found.append((id, sql, about))

  for schema, table, name, using, check in rows(f"""
      select schemaname, tablename, policyname, coalesce(qual, ''), coalesce(with_check, '')
      from pg_policies where schemaname in {SCHEMAS} order by 1, 2, 3"""):
    for kind, expr in (("using", using), ("with check", check)):
      if not expr or unwrap(expr) == "true":
        continue
      for label, new in clause_mutants(expr):
        add(f"policy:{schema}.{table}.{name}:{kind.replace(' ', '-')}={label}",
            f'alter policy "{name}" on {schema}."{table}" {kind} ({new});', f"{kind} ({new})")

  for table, secured, granted in rows(f"""
      select c.relname, c.relrowsecurity,
        coalesce((select string_agg(r, ',') from unnest(array['anon', 'authenticated']) r
                  where has_table_privilege(r, c.oid, 'select, insert, update, delete, truncate, references, trigger')
                    and (c.relkind <> 'r' or (has_table_privilege(r, c.oid, 'select') and has_table_privilege(r, c.oid, 'insert')
                      and has_table_privilege(r, c.oid, 'update') and has_table_privilege(r, c.oid, 'delete')
                      and has_table_privilege(r, c.oid, 'truncate') and has_table_privilege(r, c.oid, 'references')
                      and has_table_privilege(r, c.oid, 'trigger')))), '')
      from pg_class c where c.relnamespace = 'public'::regnamespace and c.relkind in ('r', 'p', 'v', 'm') order by 1"""):
    if secured == "t":
      add(f"rls:public.{table}:off", f'alter table public."{table}" disable row level security;', "row level security off")
    for role in APP_ROLES:
      if role not in granted.split(","):  # it lacks something `grant all` would give
        add(f"grant:public.{table}:{role}", f'grant all on public."{table}" to {role};', f"everything granted to {role}")

  for oid, name, args, definer, path, callers, definition in rows(f"""
      select p.oid, p.proname, pg_get_function_identity_arguments(p.oid), p.prosecdef,
        exists (select 1 from unnest(coalesce(p.proconfig, '{{}}')) c where c like 'search_path=%'),
        coalesce((select string_agg(r, ',') from unnest(array['anon', 'authenticated']) r where has_function_privilege(r, p.oid, 'execute')), ''),
        pg_get_functiondef(p.oid)
      from pg_proc p where p.pronamespace = 'public'::regnamespace and p.prokind = 'f'
        and not exists (select 1 from pg_depend d where d.objid = p.oid and d.deptype = 'e')
      order by p.proname, 3"""):
    signature = f"public.{name}({args})"
    trigger = re.search(r"\bRETURNS\s+trigger\b", definition, re.I) is not None
    if not trigger:  # a trigger function can't be called, whoever may execute it
      for role in APP_ROLES:
        if role not in callers.split(","):
          add(f"grant:{signature}:{role}", f"grant execute on function {signature} to {role};", f"callable by {role}")
    if definer == "t" and path == "t":
      add(f"path:{signature}:unpinned", f"alter function {signature} reset search_path;", "search_path not pinned")
    for label, new in guard_mutants(definition):
      add(f"{'caller' if label.startswith('caller') else 'guard'}:{signature}:{label}", new + ";", label)

  for table, name in rows("""
      select c.relname, t.tgname from pg_trigger t join pg_class c on c.oid = t.tgrelid
      where not t.tgisinternal and c.relnamespace = 'public'::regnamespace order by 1, 2"""):
    add(f"trigger:public.{table}.{name}:off", f'alter table public."{table}" disable trigger "{name}";', "trigger off")

  for bucket, public, size, types in rows("select id, public, coalesce(file_size_limit::text, ''), coalesce(allowed_mime_types::text, '') from storage.buckets order by 1"):
    if public == "f":
      add(f"bucket:{bucket}:public", f"update storage.buckets set public = true where id = '{bucket}';", "public")
    if size:
      add(f"bucket:{bucket}:no-size-limit", f"update storage.buckets set file_size_limit = null where id = '{bucket}';", "no size limit")
    if types:
      add(f"bucket:{bucket}:any-type", f"update storage.buckets set allowed_mime_types = null where id = '{bucket}';", "any file type")
  return found


# --- Running the suite against one weakening ------------------------------------------------------------------------

BEGIN = re.compile(r"^begin;[ \t]*$", re.M | re.I)
# pgTAP isn't kept installed (the Supabase CLI adds it for a run); here it comes and goes with each transaction.
PRELUDE = "create extension if not exists pgtap with schema extensions;\nset local statement_timeout = '30s';\n"


def run_file(path, mutation):
  """None if the test file passes with the weakening in place, else a few words on how it failed."""
  text = path.read_text()
  m = BEGIN.search(text)
  if not m:
    sys.exit(f"mutate-security: {path.name} has no `begin;` line of its own")
  script = text[:m.end()] + "\n" + PRELUDE + mutation + "\nset session authorization postgres;\n" + text[m.end():]
  out, err = psql(script)
  lines = out.replace("\x1e", "\n").splitlines()
  plan = next((int(l[3:]) for l in lines if re.fullmatch(r"1\.\.\d+", l)), None)
  failed = [l for l in lines if l.startswith("not ok")]
  passed = sum(1 for l in lines if l.startswith("ok "))
  if failed:
    return failed[0][:110]
  if plan is None or passed != plan or any(l.startswith("# Looks like") for l in lines):
    return ("stopped: " + (err.strip().splitlines() or ["no plan"])[0])[:110]
  return None


def applies(mutation):
  out, err = psql("begin;\n" + mutation + "\nrollback;\n")
  return None if not err.strip() else err.strip().splitlines()[0]


KILLS = collections.Counter()  # per test file, how many weakenings it has caught in this run


def judge(mutant, files):
  id, sql, _ = mutant
  error = applies(sql)
  if error:
    return id, "invalid", error
  for f in sorted(files, key=lambda f: -KILLS[f.name]):  # the files that catch most go first: a catch ends the search
    why = run_file(f, sql)
    if why:
      KILLS[f.name] += 1
      return id, "caught", f"{f.name}: {why}"
  return id, "survived", ""


def accepted():
  if not ACCEPTED.exists():
    return {}
  out = {}
  for line in ACCEPTED.read_text().splitlines():
    if line.strip() and not line.startswith("#"):
      id, _, reason = line.partition("  ")
      out[id.strip()] = reason.strip()
  return out


def main():
  parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
  parser.add_argument("--list", action="store_true"); parser.add_argument("--check", action="store_true")
  parser.add_argument("--only", default=""); parser.add_argument("--verbose", action="store_true")
  parser.add_argument("--jobs", type=int, default=6)
  args = parser.parse_args()

  everything = mutants()
  chosen = [m for m in everything if args.only in m[0]]
  if args.list:
    for id, _, about in chosen:
      print(f"{id}  [{" ".join(about.split())}]")
    print(f"{len(chosen)} weakenings", file=sys.stderr)
    return 0

  files = sorted(TESTS.glob("*.sql"))
  for f in files:  # the suite must pass untouched, or every weakening would look caught
    why = run_file(f, "")
    if why:
      sys.exit(f"mutate-security: {f.name} fails on the database as it is ({why}); run `npx supabase test db` first")

  results = {}
  # Each run is one transaction that rolls back, so runs don't see each other; a lock wait only slows them.
  with concurrent.futures.ThreadPoolExecutor(args.jobs) as pool:
    for id, verdict, why in pool.map(lambda m: judge(m, files), chosen):
      results[id] = (verdict, why)
      if args.verbose or verdict == "invalid":
        print(f"{verdict:8} {id}  {why}", flush=True)

  ok = accepted()
  survivors = [id for id, (v, _) in results.items() if v == "survived"]
  new = [id for id in survivors if id not in ok]
  stale = [id for id in ok if args.only in id and id not in survivors]
  invalid = [id for id, (v, _) in results.items() if v == "invalid"]
  about = {id: a for id, _, a in everything}
  for id in new:
    print(f"SURVIVED  {id}  [{" ".join(about[id].split())}]")
  for id in stale:
    print(f"STALE     {id}  (accepted in {ACCEPTED.name}, but it {'is caught now' if id in results else 'no longer exists'})")
  caught = sum(1 for v, _ in results.values() if v == "caught")
  print(f"mutate-security: {len(chosen)} weakenings, {caught} caught, {len(survivors) - len(new)} accepted, "
        f"{len(new)} survived" + (f", {len(invalid)} couldn't be applied" if invalid else "") + (f", {len(stale)} stale" if stale else ""))
  return 1 if args.check and (new or stale or invalid) else 0


if __name__ == "__main__":
  sys.exit(main())
