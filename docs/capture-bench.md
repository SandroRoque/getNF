# Capture benchmark: how the capture is iterated on (D89)

The live capture (D82–D88) is changed only through this loop. Every recorded session is replayed through the
capture code on the PC and scored against the receipt as SEFAZ has it. Each change is compared with the last accepted
run, so a fix for one session can't silently break another.

## The pieces

- **Sessions**: phone captures from debug builds (frames, OCR, and `live.tsv`: each frame's camera time and the live
  decision), and recorded videos played at 5 fps. All live under `test-media/` (gitignored), listed in
  `test-media/bench/corpus.tsv` (path, `dev` or `holdout`, frame interval when there's no `live.tsv`).
- **Answer key**: the receipt's page the app saved when it was fetched, copied from the phone to
  `test-media/truth/<chave>/page.html`. The capture never sees it, and nothing here makes a request to SEFAZ. An item
  is legible when a saved photo reads its number, code and description. A receipt with no saved page is keyed by the
  item codes OCR read in its sessions whose check digit is right (marked `*`, weaker).
- **Score per session**: completed, seconds from the first frame placed on the receipt to done, photos, items
  legible, false completion (done while an item isn't legible), and whether the replay matches the live run.
- **Runs** go to `test-media/bench/runs/`; `test-media/bench/baseline.json` is the last accepted one.

## The loop

1. **Record.** On the phone (debug build, `files/capture-dump/enabled` present), scan receipts. They needn't be new
   receipts: a new session of a known receipt is new footage (angle, motion, light, timing), and what is held out is
   the session, until it has been measured.
2. **Pull.** `tools/pull-capture-dumps.sh` copies the new sessions and adds them to the corpus as `holdout`.
3. **Measure blind.** `tools/capture-bench.sh --holdout` gives totals only for the held-out sessions. This is the
   honest number for the current version, because nothing was tuned on them. Note it in the log below.
4. **Promote.** Mark them `dev` in the corpus. From now on they can be examined
   (`test-media/<session>/ingest/`: decisions per frame, rows, saved photos).
5. **Change one thing.** `tools/capture-bench.sh --label <name>` compares the run with the baseline. Lines marked
   `WORSE` are regressions: a session no longer completes, an item lost, a false completion, 25% and 2 s slower, two
   more photos.
6. **Keep or drop it.** Keep a change with no regressions (`--accept`, or copy its run over `baseline.json`), or with
   regressions explained in its decision entry. Drop anything else and note the idea in
   [capture-backlog.md](capture-backlog.md).

`--only <text>` replays matching sessions without comparing or keeping the run; useful while debugging.
`GETNF_INGEST_ROWS=<frame ids>` prints a frame's rows, and `GETNF_INGEST_TRACE` draws the paper finder's trace.

## Log

| Date | Change | Dev sessions done | False completions | Items legible | Notes |
|---|---|---|---|---|---|
| 2026-10-25 | baseline (D88) | 7 of 9 | 1 | 23–27 of 27 | the 1 is the item-number column, below |
| 2026-10-25 | every OCR box inside the paper counts | 7 of 9 | 0 | all | the item numbers' column was dropped |
| 2026-10-25 | (dropped) rows grouped along each line's own axis | 7 of 9 | 1 | | 5 regressions; in the backlog |
| 2026-10-25 | unread print only between required rows; hint toward it | 7 of 9 | 0 | all | 23.8 → 13.7 s on one phone session |
| 2026-10-25 | held and current frame saved when together they complete | 8 of 9 | 0 | all | the blurred video still can't, as expected |
| 2026-10-25 | **holdout**: 3 new phone sessions, measured blind | 2 of 3 | 0 | 73 of 73 | 15.4 s, 4.3 photos; then promoted to dev |
| 2026-10-25 | (dropped) at the end, keep the fewest photos covering every row | 10 of 12 | 1 | | lost an item read well only in a dropped photo |
| 2026-10-25 | at the end, keep the fewest photos covering every row read near its best | 10 of 12 | 0 | all | photos down on 5 sessions (7 → 3, 7 → 4, 6 → 4) |
| 2026-10-26 | paper finder walks without allocations (D91), guidance (D92), torch (D93) | 10 of 12 | 0 | all | no change in scores, as intended |
| 2026-10-26 | **holdout**: 4 new phone sessions, measured blind | 4 of 4 | 0 | 67 of 67 | 7.0 s, 2.0 photos; one waited 34 s for the QR code (D94) |
| 2026-10-26 | the QR code can be read after the rows are all captured (D94) | 14 of 16 | 0 | all | the benchmark doesn't model the QR code yet |
| 2026-10-26 | **holdout**: 3 new phone sessions, measured blind | 3 of 3 | 1 | 78 of 79 | 8.5 s, 3.0 photos; the QR code waited up to 16 frames (D95) |
| 2026-10-26 | QR code corners no longer turned twice (D95) | 17 of 19 | 1 | | the false completion: next |
| 2026-10-26 | accents off when matching rows (PAÔ is PAO) | 17 of 19 | 1 | | no change in scores |
| 2026-10-26 | (dropped) a row counts only with the surest read's digits | | | | 15 regressions |
| 2026-10-26 | (dropped) rows with 8+ digits need 0.8 | | | | 23 regressions |
| 2026-10-26 | (dropped) save the held frame when it read rows clearly better | | 1 | | fixes this one, makes another |
| 2026-10-26 | (dropped) a row counts only with the digits most frames agree on | | | | 13 regressions |
| 2026-10-26 | **holdout**: 3 new phone sessions, measured blind | 3 of 3 | 0 | 73 of 73 | 8.6 s, 2.7 photos; the QR code read with no wait in all (D95 confirmed) |
