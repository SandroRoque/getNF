# Rollout of a bundle: stages and exit criteria (D156)

Every bundle sent toward Play (one `versionCode`) is a card on the board
<https://claude.ai/artifact/SBvMFZwuXkbg2oFdVi9jeN> (private to the user; its source is
[rollout-board.html](rollout-board.html), republished from there). A card moves to the next stage only when every exit
criterion of its stage is met and it isn't blocked. The board and this page say the same thing; change both together.

## Rules

1. One card per versionCode. A code is spent on its first upload to Play, even into a draft that's discarded.
2. Internal testing, review, closed testing and production take one bundle at a time (Play has one active release per
   track). A newer bundle entering one of them moves the older one to the archive as superseded.
3. Moving forward needs every exit criterion and no block. Moving back is allowed until the bundle has been uploaded;
   after that the way out is to discard it and start again with the next code.
4. Any stage can be blocked with a reason (a crash, data loss, a rejection). The card stays red until it's unblocked
   or discarded.
5. Every Play upload is tagged `play/<versionCode>` on the commit it was built from (D139).

## Stages

| # | Stage | Enters when | Exits when (all of them) | If it goes wrong |
|---|---|---|---|---|
| 1 | Preparação | A new versionCode is reserved for a version. | New `versionCode`/`versionName` in `android/app/build.gradle.kts` (never uploaded before, not even to a draft) · Scope frozen: everything in it is on main, fixed tickets cited (T-n) · Release notes in pt-BR in [play-console.md](play-console.md) (≤ 500 characters, nothing the app doesn't do) · Supabase migrations the version uses applied online (`npx supabase db push`) · Data safety, privacy policy and store listing checked (they change only if the version collects, sends or promises something new) | Scope growing: cut it and leave it for the next code. |
| 2 | Verificação local | Scope frozen and version set. | `tools/check.sh` passes and CI on main is green · `bundleRelease` built from a commit with no local changes (the commit goes on the card) · Release APK on the emulator: signed out, the demo, a capture from a video, Enviar comentário reaching `tools/tickets.py inbox` · Release APK on the phone, installed over the previous version (notes and account survive; never connectedAndroidTest with the phone attached) · No open blocking ticket this version should close | Fix on main and build again. Once uploaded, the code is spent: back to 1 with the next one. |
| 3 | Teste interno | Local check complete. | Uploaded to internal testing and `play/<code>` pushed · Installed from Play on the phone, opens and reads a real receipt (uses one SEFAZ-SC lookup) · Pre-launch report with no new failures · At least 1 day in the stage (automatic) · Android vitals: no new crashes or ANRs | Crash or failure: discard, fix, start again with the next code. |
| 4 | Revisão do Google | Internal testing clean. | Closed-testing release with the same bundle ("Add from library"; name = versionName, notes pasted) · Sent: Publishing overview › Send changes for review · Approved by Google · The opt-in link opens and installs | Rejected: discard with the reason, fix, start again at 1 with the next code. |
| 5 | Teste fechado | Approved and available to the group. | At least 7 days in the stage (automatic) · Vitals: crash rate under 1.09% and ANR rate under 0.47% (Play's bad-behaviour thresholds) · Inbox triaged, no blocking ticket open · Production access granted (first time only: 12 testers for 14 days in a row) · Store listing reviewed for the general public | Serious problem: a new code with the fix supersedes this one in closed testing. |
| 6 | Produção escalonada | Closed test stable and production access. | Staged 10% → 50% → 100%, each step after 48 h with crashes under 1.09% and ANRs under 0.47% · At 100% (automatic) · 7 days at 100% with no regression (automatic) · `docs/status.md` updated with the version in production | Crash spike or data loss: halt the rollout in Play and block the card; the fix ships with another code. |
| — | Arquivo | Concluded (left 6 with everything met), superseded (a newer code took its track) or discarded (rejected, crashed or abandoned, always with the reason). | Final: a code never comes back. | |
