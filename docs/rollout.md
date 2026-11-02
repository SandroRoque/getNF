# Rollout: changes, bundles, and when to cut one (D156, D157)

Two flows on one board, <https://claude.ai/artifact/SBvMFZwuXkbg2oFdVi9jeN> (private to the user; its source is
[rollout-board.html](rollout-board.html), republished from there). The board and this page say the same thing; change
both together.

- A **change** comes from a ticket (T-n), a decision (D-n), a commit or an idea, and travels to main on its own.
- A **bundle** (one versionCode) is cut when a cut rule says so. It takes every app change waiting in main and carries
  them through the Play tracks. Changes follow their bundle: concluded → delivered; discarded → back to "Em main" for
  the next one.

Not every change needs a bundle: internal ones (docs, tools, the sites, the server without an app change) are
delivered when they reach main.

## How changes get on the board

Whoever pushes an app change to main registers it (or moves its card): a Claude session that has the Artifact tools
writes the card itself; otherwise it tells the user. The cross-check for what's waiting is
`git log --oneline play/<last code>..origin/main -- android supabase`.

## Severity

- **Crítica:** a crash, data loss or corruption, a privacy or security failure, receipts that stop being read, or
  something Play or the server requires.
- **Importante:** changes what testers use or what the test measures: a new state, a fix for something frequent, a
  change in what the app collects.
- **Normal:** everything else.

## When a bundle is cut

1. **Hotfix:** a critical change reached main. Cut the same day, marked hotfix (the stages' automatic waits don't
   apply; every other criterion does).
2. **Important:** an important change reached main. Cut without waiting for the train.
3. **Train:** 7 days since the last bundle and at least one change waiting.
4. **Pile-up:** 8 or more changes waiting, before the train.
5. **No cut:** nothing waiting, or a bundle still in Preparação or Verificação local. Changes that arrive meanwhile join
   it while its scope isn't frozen.

The board's "Próximo bundle" panel applies these rules and shows the verdict.

## Change stages

| # | Stage | Exits when (all of them) | If it goes wrong |
|---|---|---|---|
| 1 | Entrada | Source cited (T-n, D-n, commit, link) · Kind and severity set · Fits the current phase (`docs/status.md`) | Out of phase or a duplicate: discard with the reason. |
| 2 | Em desenvolvimento | On main, the ticket or decision cited in the commit · `tools/check.sh` and CI green · Checked on the emulator or covered by a test · A decision in `docs/decisions.md` if not trivial | Bigger than it looked: split it. |
| 3 | Em main | Included in a bundle (automatic, on a cut) | Internal: delivered without a bundle. |
| 4 | No bundle | The bundle concluded (automatic) | If it caused the bundle's discard, take it out and back to 2. |
| — | Entregue | Delivered (its bundle concluded), delivered without a bundle (internal), or discarded with the reason. | |

## Bundle rules

1. One card per versionCode. A code is spent on its first upload to Play, even into a draft that's discarded.
2. Internal testing, review, closed testing and production take one bundle at a time (Play has one active release per
   track). A newer bundle entering one moves the older one to the archive as superseded.
3. Moving forward needs every exit criterion and no block. Moving back is allowed until the upload; after that the way
   out is to discard it.
4. A discarded bundle's changes go back to "Em main" and ride the next one.
5. Every Play upload is tagged `play/<versionCode>` on the commit it was built from (D139).

## Bundle stages

| # | Stage | Exits when (all of them) | If it goes wrong |
|---|---|---|---|
| 1 | Preparação | New `versionCode`/`versionName` (never uploaded, not even to a draft) · Scope frozen, its changes all on main · Release notes in pt-BR in [play-console.md](play-console.md), written from its changes · Supabase migrations it uses applied online · Data safety, privacy policy and listing checked | Scope growing: freeze it, the rest takes the next train. |
| 2 | Verificação local | `tools/check.sh` and CI green · `bundleRelease` from a commit with no local changes (on the card) · Release APK on the emulator: signed out, the demo, a capture from a video, Enviar comentário reaching the inbox · Release APK on the phone over the previous version (data survives; never connectedAndroidTest with the phone attached) · No open blocking ticket it should close | Fix on main and build again; once uploaded, the code is spent. |
| 3 | Teste interno | Uploaded and `play/<code>` pushed · Installed from Play, reads a real receipt (one SEFAZ-SC lookup) · Pre-launch report clean · 1 day in the stage, except hotfix (automatic) · Vitals: no new crashes or ANRs | Discard; the fix comes in as a critical change. |
| 4 | Revisão do Google | Closed-testing release with the same bundle ("Add from library") · Sent for review · Approved · The opt-in link installs | Rejected: discard with the reason; what Google asked for comes in as a critical change (source: Play Console). |
| 5 | Teste fechado | 7 days in the stage, except hotfix (automatic) · Crashes under 1.09%, ANRs under 0.47% (Play's bad-behaviour thresholds) · Inbox triaged, no blocker open · Production access granted (first time: 12 testers, 14 days in a row) · Listing reviewed for the public | The fix comes in as a critical change; its hotfix supersedes this bundle. |
| 6 | Produção escalonada | 10% → 50% → 100%, each after 48 h with crashes under 1.09% and ANRs under 0.47% · At 100% (automatic) · 7 days at 100% with no regression (automatic) · `docs/status.md` updated | Halt the rollout in Play and block the card. |
| — | Arquivo | Concluded, superseded or discarded (with the reason). A code never comes back. | |
