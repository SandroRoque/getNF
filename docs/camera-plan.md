# Camera plan: measuring first, then the barcode lookup (2026-10-31, being designed)

Agreed with the user so far; step 0 built (D129). A decision entry follows once each part is built and measured.

## What the user asked for

- **One camera for everything** (the "Escanear" button): an NFC-e QR code goes to receipt capture as today; a
  product's barcode shows what the person paid for it. If receipt capture gets measurably worse, the two are split
  into separate entries. Both flows get built either way, and the Catálogo gets a shortcut that opens the camera
  already in product mode.
- **Searches cover every caderneta on the phone**, labelled by caderneta. There's no full GTIN registry: only what
  the person bought turns up.
- **Later, to discuss:** recognising items without a barcode (produce, cold cuts, scale labels) with an image
  classifier, in the same camera (status.md › Next).
- **Good baselines before any change to the capture**, and automatic checks on every change.

## How the frame pipeline works today (LiveCapture.kt)

```
camera thread:   [frame N: OCR ‖ QR scanner ‖ gray copy] → [frame N+1: OCR ‖ QR ‖ gray] → ...
session thread:                                   [frame N: paper, rows, decisions, save photo] → ...
```

- **Stage 1 (`IngestAnalyzer`):** OCR, the QR scanner and the gray copy run at the same time. A frame leaves when the
  slowest finishes, which is OCR. Then `busy` is released and the next frame starts.
- **Stage 2 (`CaptureController`, on its own thread):** runs alongside the next frame's stage 1. If stage 2 falls
  behind, waiting frames are dropped (latest wins), never queued.
- **The saved photo is the analysed frame itself** (1920×1440). There's no separate capture. Reading discounts from
  photos happens after the receipt is done, off the frame path.
- **The period is the slowest branch:** one frame every 360–530 ms (median) in the 13 recorded sessions, mostly OCR.
  Those sessions are from 25 and 26 October, an older capture, so they aren't the baseline.

## Rules for growing the capture without lengthening a frame

1. **New detectors run as parallel branches, never in series.** The product barcode formats go into the scanner
   branch, which already runs beside OCR.
2. **Each branch must finish before OCR does.** The per-branch timings check it.
3. **Run a detector only in the state that needs it.** Product barcodes only while the mode is undecided or product;
   the receipt session ignores them.
4. **Product mode needs no OCR,** only the scanner (and later the classifier), so it can analyse several frames a
   second.
5. **A smaller image for whatever doesn't need detail** (barcodes, a classifier). Only OCR needs the full frame.
6. **Work that isn't urgent stays off the path:** saving photos, dumps and discount reading go to other threads or to
   after the receipt.
7. **Watch CPU contention and heat.** Parallel branches share cores, so measure on the phone, in sessions of real
   length.

## Testing and benchmarks: three layers

1. **Every push, no phone:** `tools/check.sh`, run by a git pre-push hook, blocks the push on a failure. It runs the
   JVM tests and the capture benchmark (`tools/capture-bench.sh`, which replays recorded sessions and scores them
   against saved pages). The benchmark can't run on GitHub, because it uses real receipts and the repo is public.
2. **Every release (before a bundle), on the phone:** Jetpack Microbenchmark times OCR, the scanners (QR only, and QR
   plus product formats) and the session over recorded frames. It lives in a module of its own with its own
   applicationId, so it installs and removes only itself and never touches the user's app (the reason for "never run
   connectedAndroidTest with the phone attached"). Emulator timings mean nothing.
3. **Real use (debug builds):** each branch's time is written per frame into the session dump next to `live.tsv`
   (today only logged).

## Plan

0. **The base.**
   - `tools/check.sh` and the hook.
   - Per-branch timings in the dumps (no behaviour change).
   - The microbenchmark module.
   - About 5 new sessions recorded by the user with the current build, as the speed baseline. Receipts they already
     have; no SEFAZ requests.

   **Built (D129):** `tools/check.sh` with the pre-push hook, `timing.tsv` with `tools/capture-timing.py`, and the
   `:benchmark` module with `tools/device-bench.sh`. **Done 2026-10-31,** with the baseline below.

   First device benchmark (2026-10-31, motorola edge 50 pro, 48 frames from the phone sessions, ms per frame,
   median / min / max):

   | | median | min | max |
   |---|---|---|---|
   | OCR | 145 | 42 | 218 |
   | QR scanner | 9 | 4 | 19 |
   | QR + product formats | 10 | 6 | 21 |
   | OCR beside QR (the analysis) | 147 | 43 | 223 |
   | OCR beside QR + products | 151 | 42 | 220 |

   The product formats cost about 1 ms in the scanner, which finishes long before OCR. But a frame alone analyses in
   about 150 ms, while the recorded sessions ran at one frame every 360–530 ms: most of the period goes elsewhere (the
   copies, the camera's delivery, sharing the cores with the preview and the session, heat). The baseline sessions'
   `timing.tsv` will show where.

   **Product sessions (2026-10-31).** The user's first three sessions with the step 0 build pointed the camera at
   products (a box, a coffee bag) to try the lookup, which doesn't exist yet. Kept apart in
   `test-media/live-dumps/products/` (not in the receipt corpus), they are step 2's first negatives and showed two
   things:
   - **The receipt capture takes packaging for paper.** The back of a coffee bag (light text on a dark background) was
     found as a receipt and 3 photos of it were committed; sheets of A4 on a desk too. Receipt paper is light with dark
     text: the paper finder should reject the inverse, and a product barcode centred in view should keep the receipt
     session from starting (the "product" state).
   - **Timing holds up on the phone:** one frame every 167 ms median (p95 288), OCR 162 ms median, the scanner 31 ms,
     the gray copy 45 ms, all from `timing.tsv`. The old 360–530 ms came from older builds. The receipt baseline (time
     to finish a receipt) still needs receipt sessions.

   **The receipt baseline (2026-10-31, step 0 build, motorola edge 50 pro).** Four receipt sessions, scored blind
   first as held out: 4 of 4 finished, no false finish, 64 of 64 items legible, 1.8 photos per receipt; the replay
   matches what the phone decided in all four. Then promoted to dev and accepted as the capture benchmark's baseline
   (`runs/20261031-021322-baseline-2026-10-31.json`), so every push is checked against them. Time to finish, in the
   replay: 18.5, 1.5, 1.8 and 0.5 s.

   | over the 69 frames, median / p95 ms | period | OCR | scanner | gray copy | session |
   |---|---|---|---|---|---|
   | receipts | 468 / 572 | 467 / 580 | 32 / 67 | 53 / 113 | 182 / 292 |
   | products (the sessions above) | 167 / 288 | 162 / 279 | 31 / 55 | 45 / 93 | 0 / 183 |

   What it says:
   - **The period is OCR, and OCR is slow on a receipt.** A frame full of receipt text takes about 470 ms; packaging
     about 160 ms. The device benchmark's 145 ms is OCR alone, over a mix of frames, with nothing else running: live,
     it shares the cores with the preview, the camera and the session (182 ms, alongside). Making OCR faster is a
     separate topic (a smaller image for the rows already read, say), not part of this plan.
   - **The product formats can't lengthen a receipt frame:** the scanner finishes in about 32 ms, against OCR's 470,
     and they add about 1 ms to it (device benchmark). The risk for one camera isn't time, it's the wrong mode: the
     product sessions above.

   Criteria to keep one camera:
   - the capture benchmark has no regressions;
   - per-frame p95 is at most 10% slower;
   - time to finish a receipt is unchanged within noise;
   - no receipt capture gets diverted to product mode;
   - product barcodes found in receipt frames: zero or near it.
1. **Lookup, no camera.** Given a GTIN, search every caderneta on the phone. For each match return the product, its
   category and caderneta, the last price and date, the last price at each store, and the 3-month average. Tell
   "never bought" apart from a scale label (GTIN-13 starting with 2, `parse/Derive.kt`). JVM tests.
2. **The camera decides what it sees.** The scanner also reads EAN-13, EAN-8 and UPC (not Code 128, which large NF-e
   printouts use for the access key). Three states:
   - **undecided**, at open: looks for everything;
   - **receipt**, once an NFC-e QR code or receipt paper is seen: product codes are ignored and the capture is exactly
     today's;
   - **product**, once a barcode is centred with no receipt paper around it: the sheet shows the result while the
     camera keeps looking. A QR code later switches to receipt.

   The same code read twice in a row doesn't reopen the sheet.
3. **Screens.**
   - A sheet over the live camera with the product, the last price and date, price by store with the cheapest
     marked, and the recent average, plus "Ver o produto".
   - "Você ainda não comprou este produto" and "Etiqueta da balança da loja" as their own states.
   - The camera hint becomes "…ou o código de barras de um produto". The scan how-to is unchanged.
   - The torch keeps its current behaviour.
   - The Catálogo shortcut.
4. **Measure again and decide:** microbenchmark, new receipt sessions, against step 0. Record the decision (one
   camera or two entries) with the numbers.

The user had more capture ideas to add before implementation starts. The first was parallelism, answered above.
