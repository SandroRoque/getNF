# Releases: changes, candidates, the Play tracks (D158)

The repo is the record. Changes are what they already are (tickets T-n in Supabase, decisions D-n, commits); a
**candidate** is one versionCode with a manifest in [releases/](releases/) that says which commit it is, what it
contains, what was checked and what Play confirmed. `tools/release.py` derives the mechanical parts from git, and CI
(`tools/release.py check`) fails when a manifest and git disagree. This replaces D156 and D157's board.

## Changes

A change is done when it's on main: `tools/check.sh` and CI green, the ticket or decision cited in the commit, a
decision in decisions.md if it isn't trivial, and an acceptance scenario run (a test, or steps on the emulator with
the result). Whether testers have it is derived, not tracked: `tools/release.py pending` lists the app and server
commits on main that the newest build testers have doesn't contain, grouped by decision and ticket.

A change that only touches docs, tools or the sites needs nothing more. **Server changes are not internal**: see below.

## Cutting a candidate

- **Weekly opportunity:** once a week, cut if `pending` shows something worth the testers' time. Nothing to show,
  no cut.
- **Early, with a stated reason only:** an incident in a build people have (data loss, a crash on a main path,
  receipts that stop being read, a privacy problem, with no workaround), something blocking the closed test itself,
  or an external deadline. The reason goes in the manifest's Notes.
- **One candidate at a time** (state `preparing`). A change that arrives meanwhile joins only by abandoning that
  candidate and cutting again at the new main, which invalidates its checks.
- **During a Google review**, ordinary changes wait for it to finish. Replacing a submission under review is an
  explicit decision, recorded in Play.
- `tools/release.py new <code> <name>` cuts at HEAD = origin/main with a clean tree and the matching versionCode in
  `build.gradle.kts`. The sha is the candidate: it's built from that sha and nothing else. To drop a change from a
  candidate, revert it on main and cut a new one; editing the manifest doesn't change what's in the AAB.

## Candidate states

`preparing → internal → submitted → closed → production`, plus `replaced` (a newer code reached its track) and
`abandoned` (never uploaded, or uploaded and withdrawn; say why). Each step is a dated line under `## Play`, as
confirmed in the Play Console, not as intended.

| State | Leaves when (each with its evidence in the manifest) |
|---|---|
| preparing | CI run URL green for the sha · `tools/check.sh` full · `bundleRelease` from the sha, `aab: sha256:…` recorded · release APK on the emulator (signed out, demo, a capture from a video, Enviar comentário reaching the inbox) · release APK on the phone over the previous version, data still there · no open blocking ticket that applies to this build, including regressions outside its scope · notes written from Contents and checked by the user · Data safety, policy, listing still true |
| internal | Uploaded, `play/<code>` pushed on the sha · installed from Play on the phone and a real receipt read · pre-launch report for this code: no unresolved blocker, the rest triaged · migrations the build needs applied online (`## Server`) |
| submitted | The same AAB added from the library to the closed track · sent for review · approved, or rejected with the reason |
| closed | An account in the closed test (not opted in to internal testing) installs this versionCode from Play |

**Rejected** is a fact about the submission, not the AAB: if the fix is outside the app (the review login, the
listing, a declaration), fix it and resubmit the same bundle; a new code only when the app must change.

There are no fixed waits between states. The closed test's 14 days are a property of the testers (see
play-console.md: each of at least 12 testers opted in continuously for 14 days), not of any build.

## Evidence with few testers

With a dozen testers, one person is 8% of the users: Play's thresholds (1.09% crashes, 0.47% ANRs, user-perceived)
are alerts when Vitals shows data, not a pass mark. Decisions rest on the scenarios run, the reports in the inbox, and
reproducible blockers. When Vitals has no data, write "no data" with the date; don't count it as green.

## Watching a build testers have

The session that cut it looks, daily in its first week and weekly after: `tools/tickets.py inbox`, the sync failures
and refused rows reports, receipts that didn't read, Vitals. Findings become tickets. A build stays supported (see
Server changes) after its card is done.

## Server changes

Installed builds keep running for months, offline or not updated, so a migration must not break any supported
client: every versionCode that reached someone through Play and isn't retired here (today 2 and 3).

1. **Expand, then contract.** Add tables, columns, functions with defaults first; ship the client that uses them;
   remove or narrow the old ones only after every client that needs them is retired.
2. A migration that drops, renames, narrows or revokes anything carries a `-- compat:` line saying which clients still
   work and how that was checked (`tools/release.py check` fails without it).
3. Each migration is listed in [releases/server.md](releases/server.md) when written and when applied online
   (`npx supabase migration list` shows it). "On main" isn't "deployed".
4. Before applying: the supported clients against the new schema (the release APK of each, or their tests against a
   local `npx supabase start` with the migration).
5. Recovery: a daily backup exists (`tools/supabase-backup.py`); a migration that rewrites data says how to undo it.

## Incidents

1. Stop the spread when possible: halt the rollout in Play (production only; it doesn't uninstall anything) or pull
   the release from the closed track.
2. Mitigate on the server when safe (a policy, a function), within the compat rule.
3. Fix with a higher versionCode, cut early with the incident as the reason, and test it on data the bad build
   already wrote.
4. Record it: a ticket, the manifest's Notes, and a decision if the process changes.

## Production (after access is granted)

- Its own preparation and review. The **first** production release goes to all of the selected countries at once:
  there's no percentage.
- Updates may use a staged rollout: the percentage changes only when someone raises it, after a look at reports and
  Vitals; record each change with the time.
- Halting a staged rollout stops new installs; devices already updated keep the build. A completed release can be
  halted only by falling back to an earlier completed one, which doesn't downgrade anyone either. The fix is always a
  new code.

## Tags

Every bundle uploaded to Play gets `play/<versionCode>` on its sha (D139). A refused upload gets none; a code once
uploaded, even into a discarded draft, is never reused (play-console.md).
