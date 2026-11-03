#!/usr/bin/env python3
"""Release candidates and what's waiting for one (D158). The repo is the record: one manifest per versionCode in
docs/releases/<code>.md, the policy in docs/rollout.md.

  tools/release.py pending              app and server changes on origin/main that no tester has yet
  tools/release.py new <code> <name>    start a candidate at HEAD (clean, equal to origin/main, versionCode = code)
  tools/release.py contents <code>      the generated Contents section of a candidate (its base..sha)
  tools/release.py check                validate every manifest and the migrations' compat notes (CI runs this)
  tools/release.py dashboard [--out F] [--open]
                                        a read-only board of the whole machine (tickets, main, candidates by state),
                                        built from the repo (and the tickets when .env.local is there); default
                                        build/dashboard.html

A manifest starts with a ```release block of `key: value` lines (code, name, sha, base, aab, state). The rest is
Markdown written by people and agents: Contents (generated, checked for drift), Server, Checks, Play (dated events),
Notes. Only standard library; git must be on PATH.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REL = ROOT / "docs" / "releases"
MIGR = ROOT / "supabase" / "migrations"
SERVER_LOG = REL / "server.md"
APP_PATHS = ["android"]
SERVER_PATHS = ["supabase/migrations", "supabase/functions"]
# Who has the app: a state at or past "internal" means a build reached at least the user's phone through Play.
STATES = ["preparing", "internal", "submitted", "closed", "production", "replaced", "abandoned"]
DISTRIBUTED = {"internal", "submitted", "closed", "production", "replaced"}
TESTERS = {"closed", "production"}
# Migrations up to this one predate D158 and carry no compat note or server log line.
MIGRATION_CUTOFF = "20261031000000"
DESTRUCTIVE = re.compile(r"\b(drop\s+(table|column|function|view|policy|type|schema|trigger)|rename\s+(column|to)|"
                         r"alter\s+column\s+\w+\s+(type|set\s+not\s+null)|revoke)\b", re.I)
REF = re.compile(r"\b(D\d{1,4}|T-\d+)\b")


def git(*args, check=True):
    r = subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise SystemExit(f"git {' '.join(args)}: {r.stderr.strip()}")
    return r.stdout.strip() if r.returncode == 0 else None


def resolve(rev):
    return git("rev-parse", "--verify", "--quiet", f"{rev}^{{commit}}", check=False)


def read_manifest(path):
    text = path.read_text()
    m = re.search(r"```release\n(.*?)```", text, re.S)
    if not m:
        return None, text
    fields = {}
    for line in m.group(1).splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            fields[k.strip()] = v.strip()
    return fields, text


def manifests():
    out = {}
    for p in sorted(REL.glob("[0-9]*.md"), key=lambda p: int(p.stem)):
        f, text = read_manifest(p)
        out[int(p.stem)] = (p, f, text)
    return out


def version_code_at(sha):
    src = git("show", f"{sha}:android/app/build.gradle.kts", check=False) or ""
    m = re.search(r"versionCode\s*=\s*(\d+)", src)
    return int(m.group(1)) if m else None


def log(base, sha, paths):
    rng = f"{base}..{sha}" if base else sha
    out = git("log", "--no-merges", "--format=%H%x09%s", rng, "--", *paths) or ""
    return [(h[:7], s) for h, s in (l.split("\t", 1) for l in out.splitlines() if l)]


def grouped_struct(commits):
    """[{ref, title, hashes}] by the decisions and tickets the commits cite, then the uncited ones (ref None)."""
    groups, other = {}, []
    for h, s in commits:
        refs = REF.findall(s)
        if refs:
            for r in dict.fromkeys(refs):
                groups.setdefault(r, []).append((h, s))
        else:
            other.append((h, s))
    def key(r):
        return (0, int(r[1:])) if r.startswith("D") else (1, int(r[2:]))
    out = [{"ref": r, "title": groups[r][0][1], "hashes": [h for h, _ in groups[r]]} for r in sorted(groups, key=key)]
    return out + [{"ref": None, "title": s, "hashes": [h]} for h, s in other]


def grouped(commits):
    """Commits grouped by the decisions and tickets they cite; the rest listed on their own."""
    groups, other = {}, []
    for h, s in commits:
        refs = REF.findall(s)
        if refs:
            for r in dict.fromkeys(refs):
                groups.setdefault(r, []).append((h, s))
        else:
            other.append((h, s))
    def key(r):
        return (0, int(r[1:])) if r.startswith("D") else (1, int(r[2:]))
    lines = []
    for r in sorted(groups, key=key):
        hs = groups[r]
        lines.append(f"- **{r}**: {hs[0][1]}" + (f" (+{len(hs) - 1} more)" if len(hs) > 1 else "") +
                     " · " + ", ".join(h for h, _ in hs))
    for h, s in other:
        lines.append(f"- {s} · {h}")
    return lines


def contents_section(base_rev, sha):
    base = resolve(base_rev) if base_rev else None
    app = log(base, sha, APP_PATHS)
    srv = log(base, sha, SERVER_PATHS)
    lines = [f"Generated by `tools/release.py contents` from `{base_rev or '(root)'}..{sha[:7]}`; don't edit by hand.", ""]
    lines += [f"App ({len(app)} commits):", ""] + (grouped(app) or ["- none"])
    lines += ["", f"Server ({len(srv)} commits):", ""] + (grouped(srv) or ["- none"])
    return "\n".join(lines)


def section(text, title):
    m = re.search(rf"^## {re.escape(title)}\n(.*?)(?=^## |\Z)", text, re.S | re.M)
    return m.group(1).strip() if m else None


def tester_base():
    """The sha testers have: the newest manifest in closed/production, else the newest distributed one."""
    ms = manifests()
    for want in (TESTERS, DISTRIBUTED):
        for code in sorted(ms, reverse=True):
            _, f, _ = ms[code]
            if f and f.get("state") in want and f.get("sha"):
                return code, f
    return None, None


def cmd_pending():
    git("fetch", "-q", "origin", "main", check=False)
    head = resolve("origin/main") or resolve("HEAD")
    code, f = tester_base()
    base = f["sha"] if f else None
    print(f"Waiting on origin/main ({head[:7]}) since code {code} ({f['state']}, {base[:7]})" if f else "No release yet")
    for title, paths in (("App", APP_PATHS), ("Server", SERVER_PATHS)):
        c = log(base, head, paths)
        print(f"\n{title}: {len(c)} commits")
        for line in grouped(c):
            print(line)
    ms = manifests()
    open_ = [c for c in ms if ms[c][1] and ms[c][1].get("state") == "preparing"]
    if open_:
        print(f"\nA candidate is being prepared: code {open_[-1]} (docs/releases/{open_[-1]}.md)")


def cmd_contents(code):
    ms = manifests()
    if code not in ms:
        raise SystemExit(f"no docs/releases/{code}.md")
    _, f, _ = ms[code]
    print(contents_section(f.get("base"), f["sha"]))


def cmd_new(code, name):
    if git("status", "--porcelain"):
        raise SystemExit("the tree has uncommitted changes: build candidates from a commit")
    git("fetch", "-q", "origin", "main", check=False)
    head = resolve("HEAD")
    if head != resolve("origin/main"):
        raise SystemExit("HEAD isn't origin/main: candidates are cut from main as pushed")
    if version_code_at(head) != code:
        raise SystemExit(f"android/app/build.gradle.kts at HEAD has versionCode {version_code_at(head)}, not {code}")
    ms = manifests()
    if code in ms or (ms and code <= max(ms)):
        raise SystemExit(f"code {code} isn't above every existing manifest ({max(ms) if ms else '-'})")
    if any(ms[c][1] and ms[c][1].get("state") == "preparing" for c in ms):
        raise SystemExit("another candidate is still being prepared (state: preparing): finish or abandon it first")
    prev = max((c for c in ms if ms[c][1] and ms[c][1].get("state") in DISTRIBUTED), default=None)
    base = f"play/{prev}" if prev else ""
    path = REL / f"{code}.md"
    path.write_text(f"""# {name} (code {code})

```release
code: {code}
name: {name}
sha: {head}
base: {base}
aab: unknown
state: preparing
```

## Contents

{contents_section(base, head)}

## Server

Migrations this build needs, and whether each is applied online (`npx supabase migration list`). Compatibility with
the clients testers still have: see docs/rollout.md › Server changes.

- none

## Checks

Each with the evidence (a CI run URL, a command and its result, or the steps done), on this sha.

- [ ] CI green on this sha:
- [ ] `tools/check.sh` (full, with the capture benchmark):
- [ ] `bundleRelease` from this sha, clean tree; `aab` above set to `sha256:<hash>`:
- [ ] Release APK on the emulator: signed out, the demo, a capture from a video, Enviar comentário reaching the inbox:
- [ ] The phone updated from Play over the previous version (D162); receipts and account still there:
- [ ] Open blocking tickets for this build (`tools/tickets.py inbox`): none, or listed:
- [ ] Release notes (pt-BR, ≤ 500 characters) in docs/play-console.md, written from Contents; checked by the user:
- [ ] Data safety, privacy policy and listing still true for this build:

## Play

Dated events as confirmed in the Play Console (`YYYY-MM-DD HH:MM track: what happened`).

## Notes
""")
    print(f"wrote {path.relative_to(ROOT)}; fill Server and Checks, then build from {head[:7]}")


def cmd_check():
    errors = []
    ms = manifests()
    tags = {int(t.split("/")[1]): t for t in (git("tag", "-l", "play/*") or "").split() if t.split("/")[1].isdigit()}
    prev_sha = None
    for code in sorted(ms):
        path, f, text = ms[code]
        where = f"docs/releases/{code}.md"
        if not f:
            errors.append(f"{where}: no ```release block"); continue
        for k in ("code", "name", "sha", "base", "aab", "state"):
            if k not in f:
                errors.append(f"{where}: missing `{k}`")
        if f.get("code") != str(code):
            errors.append(f"{where}: code {f.get('code')} doesn't match the file name")
        if f.get("state") not in STATES:
            errors.append(f"{where}: state `{f.get('state')}` isn't one of {', '.join(STATES)}")
        sha = f.get("sha", "")
        if not re.fullmatch(r"[0-9a-f]{40}", sha) or not resolve(sha):
            errors.append(f"{where}: sha must be a full commit id present in the repo"); continue
        if version_code_at(sha) != code:
            errors.append(f"{where}: versionCode at {sha[:7]} is {version_code_at(sha)}, not {code}")
        if prev_sha and git("merge-base", "--is-ancestor", prev_sha, sha, check=False) is None:
            errors.append(f"{where}: {sha[:7]} doesn't descend from the previous manifest's sha")
        prev_sha = sha
        base = f.get("base")
        if base and not resolve(base):
            errors.append(f"{where}: base {base} doesn't resolve")
        if f.get("state") in DISTRIBUTED:
            tag = tags.get(code)
            if not tag:
                errors.append(f"{where}: state {f['state']} but no tag play/{code}")
            elif resolve(tag) != sha:
                errors.append(f"{where}: play/{code} points at {resolve(tag)[:7]}, the manifest says {sha[:7]}")
            if not section(text, "Play"):
                errors.append(f"{where}: state {f['state']} but no dated event under ## Play")
        got = section(text, "Contents")
        want = contents_section(base, sha)
        if got is not None and got.strip() != want.strip():
            errors.append(f"{where}: Contents differs from `tools/release.py contents {code}` (paste its output)")
    # Server: every migration after the cutoff is in the server log, and destructive ones explain their compat.
    log_text = SERVER_LOG.read_text() if SERVER_LOG.exists() else ""
    for m in sorted(MIGR.glob("*.sql")):
        stamp = m.name.split("_", 1)[0]
        if stamp <= MIGRATION_CUTOFF:
            continue
        sql = m.read_text()
        if m.name not in log_text:
            errors.append(f"supabase/migrations/{m.name}: not in docs/releases/server.md")
        if DESTRUCTIVE.search(re.sub(r"--[^\n]*", "", sql)) and "-- compat:" not in sql:
            errors.append(f"supabase/migrations/{m.name}: drops, renames or narrows something without a `-- compat:` "
                          "line saying which clients still work (docs/rollout.md › Server changes)")
    for e in errors:
        print(e, file=sys.stderr)
    print(f"release check: {len(ms)} manifests, {'ok' if not errors else f'{len(errors)} problem(s)'}")
    return 1 if errors else 0


def entries(body, prefix_re):
    """Bulleted entries of a section, continuation lines joined."""
    out, cur = [], None
    for line in (body or "").splitlines():
        if line.startswith("- "):
            cur = line[2:].strip(); out.append(cur)
        elif cur is not None and line.startswith("  "):
            out[-1] = out[-1] + " " + line.strip()
    return [e for e in out if re.match(prefix_re, e)]


def strip_md(t):
    return re.sub(r"`([^`]*)`", r"\1", t).replace("**", "")


def tickets_data():
    """Open tickets and the untriaged count from Supabase, or None where .env.local isn't (a cloud session)."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("tickets", ROOT / "tools" / "tickets.py")
    try:
        t = importlib.util.module_from_spec(spec); spec.loader.exec_module(t)
        e = t.env()
        db = t.Supabase(e["SUPABASE_URL"], e["SUPABASE_SECRET_KEY"])
        rows = db.get("ticket", state="in.(aberto,andamento)", order="id")
        untriaged = len(db.call("GET", "feedback", select="id", triaged_at="is.null"))
    except (SystemExit, Exception):
        return None
    return {"untriaged": untriaged, "open": [{"id": r["id"], "title": r["title"], "state": r["state"],
            "priority": r["priority"], "area": r["area"], "kind": r["kind"]} for r in rows]}


def ci_data(sha):
    """The latest CI run on main from GitHub's public API, or None offline."""
    import json, urllib.request
    try:
        url = f"https://api.github.com/repos/SandroRoque/getNF/actions/runs?head_sha={sha}&per_page=5"
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "getnf-release"}), timeout=10) as r:
            runs = json.load(r)["workflow_runs"]
        runs = [x for x in runs if x["event"] == "push"] or runs
        return {"status": runs[0]["status"], "conclusion": runs[0]["conclusion"], "url": runs[0]["html_url"]} if runs else None
    except Exception:
        return None


def dashboard_data():
    import datetime
    git("fetch", "-q", "origin", "main", check=False)
    head = resolve("origin/main") or resolve("HEAD")
    subj, date = (git("show", "-s", "--format=%s%x09%cI", head) or "\t").split("\t")
    ms = manifests()
    cands = []
    for code in sorted(ms, reverse=True):
        _, f, text = ms[code]
        if not f:
            continue
        checks = entries(section(text, "Checks"), r"\[[ x]\]")
        play = []
        for e in entries(section(text, "Play"), r"\d{4}-\d{2}-\d{2}"):
            m = re.match(r"(\d{4}-\d{2}-\d{2})(?:\s+(\d{2}:\d{2}))?\s+(.*)", e)
            play.append({"date": m.group(1), "time": m.group(2), "text": strip_md(m.group(3))})
        app = log(resolve(f["base"]) if f.get("base") else None, f["sha"], APP_PATHS)
        cut = play[0]["date"] if play else (git("show", "-s", "--format=%cs", f["sha"]) or "")
        cands.append({"code": code, "name": f.get("name"), "sha": f.get("sha", "")[:7], "state": f.get("state"),
                      "base": f.get("base"), "aab": f.get("aab", "unknown") != "unknown", "cut": cut,
                      "checksDone": sum(c.startswith("[x]") for c in checks), "checksTotal": len(checks),
                      "openChecks": [strip_md(c[3:].strip()) for c in checks if c.startswith("[ ]")],
                      "play": play, "contents": grouped_struct(app), "notes": strip_md(section(text, "Notes") or "")})
    tcode, tf = tester_base()
    base = tf["sha"] if tf else None
    pending = {"app": grouped_struct(log(base, head, APP_PATHS)), "server": grouped_struct(log(base, head, SERVER_PATHS)),
               "appCommits": len(log(base, head, APP_PATHS)), "serverCommits": len(log(base, head, SERVER_PATHS)),
               "since": tcode}
    server_pending = [strip_md(l[2:]) for l in (SERVER_LOG.read_text() if SERVER_LOG.exists() else "").splitlines()
                      if l.startswith("- ") and re.search(r"applied:\s*pending", l)]
    return {"generated": datetime.datetime.now().astimezone().isoformat(timespec="minutes"),
            "head": {"sha": head[:7], "subject": subj, "date": date}, "ci": ci_data(head),
            "candidates": cands, "pending": pending, "serverPending": server_pending,
            "tickets": tickets_data(), "cadenceDays": 7}


def cmd_dashboard(argv):
    import json, webbrowser
    out = ROOT / "build" / "dashboard.html"
    if "--out" in argv:
        out = Path(argv[argv.index("--out") + 1]).resolve()
    data = json.dumps(dashboard_data(), ensure_ascii=False).replace("</", "<\\/")
    html = (ROOT / "tools" / "dashboard.html").read_text().replace("__DATA__", data)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(html)
    print(f"wrote {out}")
    if "--open" in argv:
        webbrowser.open(out.as_uri())


def main(argv):
    if git("rev-parse", "--is-shallow-repository") == "true":
        raise SystemExit("this clone is shallow: commit ranges would be wrong (git fetch --unshallow --tags first)")
    if len(argv) >= 1 and argv[0] == "pending":
        return cmd_pending()
    if len(argv) == 2 and argv[0] == "contents":
        return cmd_contents(int(argv[1]))
    if len(argv) == 3 and argv[0] == "new":
        return cmd_new(int(argv[1]), argv[2])
    if argv == ["check"]:
        return cmd_check()
    if argv and argv[0] == "dashboard":
        return cmd_dashboard(argv[1:])
    print(__doc__.strip(), file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]) or 0)
