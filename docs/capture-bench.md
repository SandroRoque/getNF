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

1. **Record.** On the phone (debug build, `files/capture-dump/enabled` present), scan receipts. Prefer receipts the
   capture hasn't seen; the app keeps a list of them under `files/receipts/`.
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
| 2026-10-25 | held and current frame saved when together they complete | 9 of 9 | 0 | all | the blurred video still can't, as expected |
