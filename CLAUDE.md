# getNF: notes for Claude

Start with `docs/status.md` (current state, how to resume, open items). Read `README.md`, then `docs/decisions.md`
(the decision log), before making design choices. Add a new
entry to `docs/decisions.md` for every non-trivial decision. Never rewrite old entries.

## The user

New to modern Android (last touched it in the Java-only era). Wants to stay hands-off during development:
drive the app yourself in the emulator, and only ask for things that need hands or a decision
(BIOS, sudo, physical phone, real receipts, solving a Turnstile that won't pass in the emulator).
Explain Android-specific concepts briefly when they come up.

## Environment

- Always `. tools/env.sh` first (SDK tools aren't on the default PATH). `tools/doctor.sh` shows readiness.
- Gradle project is in `android/`: `cd android && ./gradlew assembleDebug testDebugUnitTest`.
- Emulator: `tools/emulator.sh [--headless] [--camera <video-or-photo>]`. The back camera plays that file. Restart to change it.
- Inspect/drive the device with the `android` CLI (`android screen`, `android layout`, `android run`) or adb.
  See `.claude/skills/android-cli`.
- Supabase: the online project (São Paulo) is what debug builds use; its URL and keys, and the other credentials,
  are in `.env.local` at the repo root (a worktree uses the main checkout's). `npx supabase start` runs a local one
  (Docker); from the emulator it's `http://10.0.2.2:54321`. Schema changes go in `supabase/migrations/` as
  migrations; `npx supabase test db` runs the pgTAP tests.
- Testers' reports and tickets live in Supabase, not the repo (D152): `python3 tools/tickets.py inbox` to triage. Cite
  tickets by number ("T-12") in commits and docs, never a report's text.
- `sudo` needs a password, so you can't run it. Put root steps in `tools/setup-system.sh` and ask the user to run it.

## Git

- One task, one branch, named after the task (`claude/<task>`). A session's worktree comes with a made-up name: rename
  its branch when the task is clear (`git branch -m claude/<task>`), and start a new branch from `origin/main` for a
  new task instead of carrying on in the old one.
- Verified work goes to main (`git push origin HEAD:main`), then the main checkout is fast-forwarded
  (`git -C <main checkout> merge --ff-only origin/main`). Once merged, the branch has no further use: delete it when
  its session ends, and tell the user about stale worktrees rather than leaving them to pile up.
- Never commit build output (`build/`, APKs, bundles); check `git status` before committing new modules.
- Every Play upload is tagged `play/<versionCode>` on the commit it was built from (D139, `docs/play-console.md`).



- `test-media/` holds real receipts and is gitignored. Don't commit it, and don't copy it into tracked test dirs as-is.
  Make reduced/sanitized fixtures if tests need them.
- Don't hammer `sat.sef.sc.gov.br`. It blocks an IP after about 5 lookups per hour, and the PC, emulator and phone
  share one home IP, so every probe uses up the user's budget for real receipts. Never reload portal pages to debug:
  use `test-media/pages/`, the fixtures, or DevTools on an already-open WebView (`tools/webview-eval.mjs`). Before any
  live request, say how many and get a go-ahead (`docs/sefaz-sc.md`).
- The repo is public. Never commit a real chave (anyone can look the receipt up), store details from real receipts,
  or anything from `.env.local`. Fixtures go through `tools/make-nfce-fixture.py`.
