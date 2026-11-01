#!/usr/bin/env python3
"""Testers' reports and tickets (D152): the triage tool. The rows live in the Supabase project (tables `feedback` and
`ticket`), never in this repo, which is public; this tool only reads and writes them with the secret key.

  python3 tools/tickets.py inbox                     # reports not triaged yet, oldest first
  python3 tools/tickets.py report 41                 # one report in full
  python3 tools/tickets.py new "Captura trava depois do QR" --kind bug --area captura [--priority urgente] [--reports 41,47]
  python3 tools/tickets.py attach T-12 41 47         # reports join a ticket
  python3 tools/tickets.py drop 52 --note "teste"    # a report set aside, no ticket
  python3 tools/tickets.py list [--all]              # open tickets (or every one)
  python3 tools/tickets.py show T-12                 # a ticket and its reports
  python3 tools/tickets.py set T-12 --state andamento [--priority ...] [--title ...] [--kind ...] [--area ...]
  python3 tools/tickets.py note T-12 "what was found"
  python3 tools/tickets.py resolve T-12 [--version 0.1.1] [--commit SHA]   # the commit defaults to HEAD
  python3 tools/tickets.py discard T-12 --note "why not"
  python3 tools/tickets.py summary                   # the open tickets, as markdown for docs/status.md
  ... --local                                        # against the local Supabase (npx supabase start)

Reports may hold personal details (stores, items, what people wrote): don't copy them into the repo; a ticket's
number is all a commit or a doc needs. Reads SUPABASE_URL and SUPABASE_SECRET_KEY from .env.local, never prints them.
"""
import argparse, datetime, json, os, re, subprocess, sys, textwrap, urllib.error, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KINDS, AREAS = ("bug", "ideia", "duvida"), ("captura", "sefaz", "sync", "telas", "dados", "outro")
PRIORITIES, STATES = ("urgente", "normal", "baixa"), ("aberto", "andamento", "resolvido", "descartado")


def env():
  for path in (os.path.join(ROOT, ".env.local"), os.path.join(ROOT, "..", "..", "..", ".env.local")):
    if os.path.exists(path):
      return dict(l.strip().split("=", 1) for l in open(path) if "=" in l and not l.lstrip().startswith("#"))
  sys.exit("no .env.local")


class Supabase:
  def __init__(self, url, key):
    self.url, self.key = url.rstrip("/"), key

  def call(self, method, table, body=None, **params):
    q = ("?" + urllib.parse.urlencode(params, safe=",.()*:")) if params else ""
    headers = {"apikey": self.key, "content-type": "application/json", "Prefer": "return=representation"}
    if not self.key.startswith("sb_"):
      headers["Authorization"] = "Bearer " + self.key
    req = urllib.request.Request(f"{self.url}/rest/v1/{table}{q}", json.dumps(body).encode() if body is not None else None,
                                 headers, method=method)
    try:
      with urllib.request.urlopen(req, timeout=60) as r:
        text = r.read().decode()
        return json.loads(text) if text else []
    except urllib.error.HTTPError as e:
      sys.exit(f"{method} {table}: {e.code} {e.read().decode()[:300]}")

  def get(self, table, **params):
    return self.call("GET", table, select="*", **params)


def ticket_id(s):
  m = re.fullmatch(r"(?i)t-?(\d+)", s) or re.fullmatch(r"(\d+)", s)
  if not m:
    sys.exit(f"not a ticket: {s} (T-12)")
  return int(m.group(1))


def when(ts):
  if not ts:
    return "-"
  t = datetime.datetime.fromisoformat(ts.replace("Z", "+00:00")).astimezone()
  return t.strftime("%d/%m %H:%M")


def first_line(text, width=90):
  line = next((l.strip() for l in text.splitlines() if l.strip()), "")
  return line if len(line) <= width else line[:width - 1] + "…"


def ids(values):
  out = []
  for v in values:
    out += [int(x) for x in str(v).split(",") if x.strip()]
  return out


def print_report(f, full=False):
  head = f"#{f['id']}  {when(f['received_at'])}  {f['kind']:<13} {f['install_id'][:8]}  {f['about']}"
  if f.get("ticket_id"):
    head += f"  → T-{f['ticket_id']}"
  elif f.get("triaged_at"):
    head += f"  (posto de lado: {f.get('note') or ''})"
  print(head)
  if full:
    print(textwrap.indent(f["body"], "    "))
  else:
    print("    " + first_line(f["body"]))


def print_ticket(t, count=None):
  extra = f"  {count} relato(s)" if count is not None else ""
  print(f"T-{t['id']:<4} {t['priority']:<8} {t['state']:<10} {t['kind']:<6} {t['area']:<8} {t['title']}{extra}")


def triage(db, report_ids, ticket=None, note=None):
  now = datetime.datetime.now(datetime.timezone.utc).isoformat()
  body = {"ticket_id": ticket, "triaged_at": now}
  if note:
    body["note"] = note
  rows = db.call("PATCH", "feedback", body, id=f"in.({','.join(map(str, report_ids))})")
  missing = set(report_ids) - {r["id"] for r in rows}
  if missing:
    sys.exit(f"no such report: {sorted(missing)}")


def main():
  p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
  p.add_argument("--local", action="store_true")
  sub = p.add_subparsers(dest="cmd", required=True)
  sub.add_parser("inbox")
  s = sub.add_parser("report"); s.add_argument("id", type=int)
  s = sub.add_parser("new"); s.add_argument("title"); s.add_argument("--kind", choices=KINDS, required=True)
  s.add_argument("--area", choices=AREAS, required=True); s.add_argument("--priority", choices=PRIORITIES, default="normal")
  s.add_argument("--reports", nargs="*", default=[]); s.add_argument("--notes", default="")
  s = sub.add_parser("attach"); s.add_argument("ticket"); s.add_argument("reports", nargs="+")
  s = sub.add_parser("drop"); s.add_argument("reports", nargs="+"); s.add_argument("--note", required=True)
  s = sub.add_parser("list"); s.add_argument("--all", action="store_true")
  s = sub.add_parser("show"); s.add_argument("ticket")
  s = sub.add_parser("set"); s.add_argument("ticket"); s.add_argument("--state", choices=("aberto", "andamento"))
  s.add_argument("--priority", choices=PRIORITIES); s.add_argument("--title"); s.add_argument("--kind", choices=KINDS)
  s.add_argument("--area", choices=AREAS)
  s = sub.add_parser("note"); s.add_argument("ticket"); s.add_argument("text")
  s = sub.add_parser("resolve"); s.add_argument("ticket"); s.add_argument("--version"); s.add_argument("--commit")
  s = sub.add_parser("discard"); s.add_argument("ticket"); s.add_argument("--note", required=True)
  sub.add_parser("summary")
  a = p.parse_args()

  if a.local:
    out = subprocess.run(["npx", "supabase", "status", "-o", "env"], cwd=ROOT, capture_output=True, text=True).stdout
    local = dict(l.split("=", 1) for l in out.splitlines() if "=" in l)
    db = Supabase(local["API_URL"].strip('"'), local["SECRET_KEY"].strip('"'))
  else:
    e = env()
    db = Supabase(e["SUPABASE_URL"], e["SUPABASE_SECRET_KEY"])

  def ticket(tid):
    rows = db.get("ticket", id=f"eq.{tid}")
    if not rows:
      sys.exit(f"no ticket T-{tid}")
    return rows[0]

  def append_note(t, text):
    stamp = datetime.date.today().isoformat()
    return (t["notes"] + "\n" if t["notes"] else "") + f"{stamp}: {text}"

  if a.cmd == "inbox":
    rows = db.get("feedback", triaged_at="is.null", order="received_at")
    if not rows:
      print("nada novo")
    for f in rows:
      print_report(f)
  elif a.cmd == "report":
    rows = db.get("feedback", id=f"eq.{a.id}")
    if not rows:
      sys.exit(f"no report #{a.id}")
    print_report(rows[0], full=True)
  elif a.cmd == "new":
    t = db.call("POST", "ticket", {"title": a.title, "kind": a.kind, "area": a.area, "priority": a.priority, "notes": a.notes})[0]
    if a.reports:
      triage(db, ids(a.reports), t["id"])
    print_ticket(t)
  elif a.cmd == "attach":
    tid = ticket(ticket_id(a.ticket))["id"]
    triage(db, ids(a.reports), tid)
    print(f"{len(ids(a.reports))} relato(s) em T-{tid}")
  elif a.cmd == "drop":
    triage(db, ids(a.reports), None, a.note)
    print(f"{len(ids(a.reports))} relato(s) postos de lado")
  elif a.cmd in ("list", "summary"):
    params = {"order": "id"} if getattr(a, "all", False) else {"state": "in.(aberto,andamento)", "order": "id"}
    tickets = db.get("ticket", **params)
    counts = {}
    for f in db.call("GET", "feedback", select="ticket_id", ticket_id="not.is.null"):
      counts[f["ticket_id"]] = counts.get(f["ticket_id"], 0) + 1
    order = {p: i for i, p in enumerate(PRIORITIES)}
    tickets.sort(key=lambda t: (t["state"] in ("resolvido", "descartado"), order[t["priority"]], -counts.get(t["id"], 0), t["id"]))
    untriaged = len(db.call("GET", "feedback", select="id", triaged_at="is.null"))
    if a.cmd == "list":
      for t in tickets:
        print_ticket(t, counts.get(t["id"], 0))
      print(f"{untriaged} relato(s) sem triagem")
    else:
      print(f"Tickets abertos ({datetime.date.today().isoformat()}): {len(tickets)}; relatos sem triagem: {untriaged}.")
      for t in tickets:
        state = " (em andamento)" if t["state"] == "andamento" else ""
        print(f"- T-{t['id']} [{t['priority']}, {t['area']}] {t['title']}: {counts.get(t['id'], 0)} relato(s){state}")
  elif a.cmd == "show":
    t = ticket(ticket_id(a.ticket))
    print_ticket(t)
    print(f"aberto {when(t['created_at'])}, mudou {when(t['updated_at'])}")
    if t["resolved_at"]:
      print(f"resolvido {when(t['resolved_at'])} em {t['resolved_commit'] or '-'}, versão {t['resolved_version'] or '-'}")
    if t["notes"]:
      print(textwrap.indent(t["notes"], "  "))
    for f in db.get("feedback", ticket_id=f"eq.{t['id']}", order="received_at"):
      print_report(f, full=True)
  elif a.cmd == "set":
    t = ticket(ticket_id(a.ticket))
    body = {k: v for k, v in {"state": a.state, "priority": a.priority, "title": a.title, "kind": a.kind, "area": a.area}.items() if v}
    if not body:
      sys.exit("nothing to change")
    print_ticket(db.call("PATCH", "ticket", body, id=f"eq.{t['id']}")[0])
  elif a.cmd == "note":
    t = ticket(ticket_id(a.ticket))
    db.call("PATCH", "ticket", {"notes": append_note(t, a.text)}, id=f"eq.{t['id']}")
    print(f"nota em T-{t['id']}")
  elif a.cmd == "resolve":
    t = ticket(ticket_id(a.ticket))
    commit = a.commit or subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    body = {"state": "resolvido", "resolved_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "resolved_commit": commit or None, "resolved_version": a.version}
    print_ticket(db.call("PATCH", "ticket", body, id=f"eq.{t['id']}")[0])
  elif a.cmd == "discard":
    t = ticket(ticket_id(a.ticket))
    print_ticket(db.call("PATCH", "ticket", {"state": "descartado", "notes": append_note(t, a.note)}, id=f"eq.{t['id']}")[0])


if __name__ == "__main__":
  main()
