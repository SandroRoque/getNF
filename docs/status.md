# Status and next steps

Last updated 2026-10-25. A handoff for picking the project up again. For *why* things are the way they are, read
`docs/decisions.md` (D1–D81). For the portal's behaviour, read `docs/sefaz-sc.md`.
For the data flow and analytical semantics, read `docs/architecture.md`.

## Product direction recorded 2026-10-14

See [objectives.md](objectives.md) for the new objectives: a self-contained Android app with a first-class analytical
experience, continued PC dashboard access, manual item creation/editing/deletion and categorization, the proposed
IPCI personal inflation index, and evaluation of local/API-assisted catalog workflows. These are future objectives;
the implementation status below remains the current baseline. Work can proceed in separate increments.
The proposed stages and their completion criteria are in [roadmap.md](roadmap.md), starting with the data model
and persistence review.
The user subsequently chose on-device parsing and optional contributions for shared curation and collective
analyses; see [local-and-shared-modes.md](local-and-shared-modes.md) and decision D24. These remain planned changes.

## Where it stands

**Original pipeline tested end to end; continuous capture completed twice on the Motorola and persisted images.
A blurred JPEG was accepted, so the quality filter is not yet validated:**
scan QR → native-resolution analysis frames (1440×1920 in the reviewed captures) → SEFAZ page in a WebView (the user
taps Validar if a challenge shows) → saved on the phone → uploaded to PocketBase → parsed into products and item lines →
categorized → reportable through the `spending_lines` view.

- **App (`android/`):** scan, photos, page capture. Receipts can be queued ("Later") and fetched afterwards ("Fetch next").
  Continuous capture now saves overlapping analysis frames automatically, draws detected edge segments, and
  measures sharpness exclusively inside an eroded receipt mask. Hints appear together as needed; QR may be read
  last. Connected top-to-bottom coverage and QR finish the session automatically. Incomplete exit offers explicit
  code-only/discard/continue choices and preserves prior photos. There is no manual shutter or whole-page rule.
  Region proposals now validate the paper boundary before drawing. The overlay follows an irregular contour;
  “Mostrar área analisada” exposes the actual focus mask and debug “Inspecionar frame” freezes one analysis image.
  Upright/sideways normalization and explicit camera matrices preserve QR/photo/mask coordinates (D31).
  D32/D33 prevent unanchored capture dead-ends and fix horizontal fallback. Physical JPEG review exposed
  a remaining blur false positive; capture completion does not yet guarantee legibility of every saved image.
  Full outlines can now establish geometry while text is blurred. Near-frontal top + lateral edges localize
  a closer view; content anchors are retained independently of saved photos. A later overview preserves the
  existing coordinate basis. See [capture-geometry-plan.md](capture-geometry-plan.md) and D30 for delivered scope;
  bottom-based geometric localization and predicted edge search remain planned.
  Fetching the SEFAZ page stays separate. See [automatic-capture.md](automatic-capture.md), D28 and
  [capture-validation.md](capture-validation.md) for test and physical-review evidence. Repeated scans append images
  to the existing NF. There is no post-save pruning; see [capture-retention-plan.md](capture-retention-plan.md).
  The app paces SEFAZ lookups per network and warns before a fetch would probably get the IP blocked (D17). It skips the
  portal's scripts, styles and images it doesn't need (D16), recognizes the rate-limit page, and pauses the WebView in the background.
- **Server:** retired 2026-10-25 (D73); the data lives on Supabase (D71, D72). What follows describes the old
  `server/` PocketBase: with hooks that parse every uploaded page (`pb_hooks/lib/`), sync `taxonomy/*.txt`
  at startup, and expose `spending_lines` (D15). Prorated discounts until a photo pass exists (D14).
- **Dashboard:** `/dashboard/` on the existing PocketBase server (D19). Monthly stacked spending bars → selected
  month's category pie → grouped products with expandable purchases. Phone app or PocketBase admin login; live reload on
  opening or Refresh. Date range and category depth controls; responsive PC/phone layout.
  The selected month's NFs are also listed with dates, stores, totals, and expandable item details across all categories.
- **Local data** (not in git): the receipts captured so far, every product categorized, in `server/pb_data/` plus the
  photos on the phone. **Back up `server/pb_data/`.** The categorizations exist only there.
- **Tests:** `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`:
  116 unit tests and 53 instrumented vision/capture/lifecycle tests (the D37 focus benchmark and the D38 real-capture eval, which skips without local files). Never run connectedAndroidTest with the phone attached: it uninstalls the app and wipes its receipts on the emulator; an offline camera journey
  also read a synthetic QR, saved a photo and completed automatically (see capture-validation.md).
  Debug build and lint pass. PocketBase's 100-photo migration and rollback were validated in an isolated database;
  the migration was applied to the live server on 2026-10-15 after a backup (D36). Android also has a 100-photo technical cap.
  Server: `node --test server/test/*.test.js server/test/*.test.mjs`. After changing either parser: `node tools/nfce-golden.mjs`
  (parity goldens, D48).
  (12 parser/derivation tests + 12 dashboard tests). Run `node server/test/dashboard.test.mjs` for individual dashboard results.
- **Repo:** public at github.com/SandroRoque/getNF, branch `main`. History was squashed before publishing to remove
  personal data. Commits use the GitHub noreply address.

## Resuming

```bash
. tools/env.sh
docker compose -f server/compose.yaml up -d            # PocketBase; admin UI http://127.0.0.1:8090/_/
tools/emulator.sh --headless --camera test-media/video/<video>.mp4   # optional; works with the phone plugged in too
```

The phone uploads over home Wi‑Fi to `http://192.168.4.4:8090` (D18): no cable needed. Receipts scanned elsewhere wait
in the queue until the phone is home and the PC is on. The emulator uses the build default `http://10.0.2.2:8090`.

Categorizing new products: PocketBase admin UI → `products`, filter `category = ""`. Or ask Claude to propose a mapping
and apply it through the API (as was done for the first 75 products).

## SEFAZ rules of thumb (learned the hard way, see docs/sefaz-sc.md)

- Blocks are **per IP**, after roughly **5 lookups per hour**. A lookup is opening a receipt's page, plus Validar after a
  challenge. Asset count doesn't seem to matter. Home Wi-Fi and each SIM are separate budgets, and switching SIM worked immediately.
- Scan a pile of receipts with **Later**, then use **Fetch next**, switching network when the budget runs out.
- **Never probe the live portal for development.** Use saved pages, fixtures, or DevTools on an already-open WebView
  (`tools/webview-eval.mjs`). The PC, emulator and phone share the home IP.

## Known capture limits (D41)

Capture is good enough to move on (short receipts complete in seconds; OCR rejects blur). Not done or not proven:

- **Long receipts were never tested on the phone.** Every live capture fitted one or two frames; coverage built from
  several overlapping photos (content registration between segments) has only synthetic tests.
- The paper mask can still leak a little into a patterned background, and a strong shadow across the receipt can
  cut the mask at the shadow. OCR, not the mask, decides what counts as legible.
- Captures save more redundant photos than needed (up to 9 per short receipt); no pruning exists (D34).
- The OCR threshold (median line confidence >= 0.5) rests on a dozen real photos of one receipt under one light.
- OCR runs on the capture executor, pausing analysis for ~0.3 s per candidate on the Motorola.
- Test captures added 30+ photos to one real NF; the app can't remove them.

## Open items, roughly in priority order

- **UI/UX redesign (D81) built:** four tabs (Início, Gastos, Para você, Catálogo) with scan in the middle, one decision
  queue, stores as a lens, the Calmo look in light and dark, Portuguese throughout. Checked on the emulator; next, the
  user's review on the phone. Not redesigned yet: the scan and SEFAZ page screens (already Portuguese).

Code review findings and the proposed reordering are in D35: per-orientation focus check, blurred text counted as
blank paper, upload that replaces server photos, the photo-cap dead-end, then roadmap stage 1 before more geometry.

- **Capture quality first:** D37/D38 decide legibility with on-device OCR of each saved candidate (band median
  confidence >= 0.5, calibrated on the real D34 JPEGs: the blurred one credits nothing). OCR takes 146–559 ms on the
  Motorola. D39–D41 reject regions that don't look like a receipt, keep the receipt's axis and trim mask leaks.
  The capture front is closed for now; see Known capture limits. Next: roadmap stage 1. Background: Two physical
  attempts completed, but JPEG review found one blurred image and another softer image among six saved.
  Synthetic blur benchmarks and conservative retention ranking can proceed without the phone. Keep captures
  during development; future bad/redundant-image selection is planned, with no deletion implemented (D34).
  Further device validation must cover long thermal receipts, low contrast, shadows, motion and interruptions.

0. **Stage 2 (local database) done.** The data model is approved (D44, [data-model.md](data-model.md)).
   2a (D45) moved receipts, photos and capture sessions into Room (`getnf.db`) and imports the old `receipt.json`
   manifests once, renaming them to `receipt.json.migrated`. 2b (D46) keeps a read-only copy of the server's lines and
   catalog, refreshed after each upload run and at startup. 2c (D47): Settings → Export data / Restore data writes or
   reads one zip (database, pages, photos, masks, hashes); a restore is checked first and swapped in on restart.
   Verified on the emulator after a reinstall and on the phone with the real data (2026-10-20): what came back was
   identical to what was exported. **Stage 2 is complete.**
0. **Stage 3 (parsing on the phone) in progress.** 3a (D48): the server's parser and derivation are ported to Kotlin
   (`android/.../parse/`) and match the JS field for field on the fixtures and on all 10 real pages, on the JVM and
   the emulator (`tools/nfce-golden.mjs`). 3b (D49): the phone parses each saved page into its own header, lines
   and store items; the server still supplies categories by code. A reparse of the real export matched the
   server's 135 lines exactly, and so did the phone itself after installing it (2026-10-21). The list shows items
   and amount paid per receipt. 3c (D50): tapping a receipt shows its lines with categories and totals, and "Read
   page again" re-parses the saved page (no SEFAZ request). 3d (D54): "Discounts from photos" reads per-item
   discounts from the photos (OCR on the phone), checked against the page's total; complete on 5 of 6 real
   discount receipts and verified on the phone. Not yet: confirming against the photo snippet, and undo. Capture on a
   dark, unevenly lit surface: the mask stops at the receipt's straight sides (D52), and a frame that fills the last
   coverage gap is saved (D53); verified live on the phone (2026-10-24). Next:
   stage 4, planned in [stage-4-plan.md](stage-4-plan.md) (D70): 4a Supabase foundation and data migration, then
   4b Jev suggestions (Edge Function) and 4c review and editing on the phone. Installing an APK older than D45 shows an empty list; restore from backup.
0. Photos upload append-only to `receipt_photos` with masks and metadata (D42); all 51 phone photos are on the live
   server. A restore onto a new machine needs one restart after the first start (D42).
1. **Uploads only on home Wi‑Fi with the PC on** (D18). Fine for now. Pending, by the user: reserve `192.168.4.4` for
   this PC in the router's DHCP settings, and run `sudo bash tools/setup-system.sh` once so Docker starts at boot.
   Tailscale (D6) can come with the NAS if uploads from outside are ever wanted.
2. **Untested live:** the page screen's **Next** (to the next waiting receipt) only appears after a real save.
3. **Is the home IP shared (CGNAT)?** Check the router's WAN address: anything in 100.64.0.0/10 means yes.
   Adjust `LookupBudget.LIMIT` once more blocks (or non-blocks) tell us more.
4. **Reports:** the monthly dashboard is implemented. Weekly/quarterly/yearly views and exports remain future work.
5. **Photo pass for per-item discounts:** built on the phone (D54). Left: confirming each value against its photo
   snippet, and undo back to prorated.
6. **Category suggestions:** have an LLM propose categories for new products, for the user to confirm. Part of D51: interpreted fields (size, identity, category) become suggestions with a source and review
   state; stage 4 builds the review screen, stage 8 plugs models in.
7. **Size parsing gaps** (`server/pb_hooks/lib/derive.js` `parseSize`): "1K" (= 1 kg) and "1 25KG" (= 1.25 kg) aren't
   understood. Those were fixed by hand (`size_source = manual`).
8. **Manual-chave fallback** for damaged QR codes (D3/D13): not built.
9. **Minor:** a few details-page assets slip past the blocking before `onPageStarted` fires (D16).
10. **Smaller photos online (D61, planned):** once the camera and ingest flow settle, upload receipt-cropped grayscale
    WebP copies instead of full JPEGs, delete phone originals after confirmation, upload photos only when they prove
    discounts, and remove the 55 duplicated photos on the server.
11. **Move to Supabase (D62, D63, D70, D71):** 4a is built and the schema is live online; the phone syncs with
    Supabase and no longer talks to PocketBase. The real data is on Supabase and verified (D72), and the restore drill
    passed on it. PocketBase is retired and the nightly backup timer runs (D73). Left for
    4a: deleting photo originals on the phone after confirmation (D61). 4b is live (D74): new items get a Jev category
    (≥ 0.9) or a suggestion online, within a minute. The phone app with Room v5 (suggestions) still has to be
    installed on the phone. 4c is built and installed (D76). **Stage 5 built (D77):** Gastos (spending by month or year, by category, with what's estimated or missing), product price evolution and a preliminary category trend, under bottom tabs Gastos · Notas · Produtos. Stage 6 dropped for now (D79). **Stage 7 built (D79):** the IPCI, chained monthly on the price paid, with coverage, "dados insuficientes" below 30% or 3 products, the spending split and per-product contributions; on the user's data only June 2026 has a reading so far (+2,3%). **Stage 8 built (D80):** the LLM worker on the PC (nightly at 04:00) audits automatic categories, learns from corrections, proposes same products, sizes and category descriptions, and writes a health report shown in Ajustes. Local Supabase for
    development: `npx supabase start` (Docker), tests `npx supabase test db`.

## Gotchas

- PocketBase rejects view queries with a bare `||` or `CASE` in a column: wrap them in parentheses (D15).
- Schema changes made in the admin UI or through the collections API write migration files into `server/pb_migrations/`
  (root-owned). Commit or delete them (`docs/dev-environment.md`).
- A failing migration stops PocketBase from starting. Validate view SQL first by creating a throwaway collection via the API.
- New test fixtures must go through `tools/make-nfce-fixture.py`, which replaces chave, store, dates and taxes with fake values.
  Never commit a real chave: anyone can look it up on the portal.


## Current capture checkpoint — 2026-10-15

Bounded segmentation, paper-contour validation and coherent mask/overlay publication address the earlier
multi-second analysis stalls. Focus/torch/zoom controls and geometry/anchor recovery are in the installed APK.
The short successful physical window had median region analysis 50 ms and overlay-ready time 245 ms; these
are stage measurements, not display latency or a general performance guarantee.

Persistent geometry, top-based localization and explicit full-document capture are implemented. Bottom-based
geometric localization and predicted edge search remain planned; see [capture-geometry-plan.md](capture-geometry-plan.md).
The next independent increment is quality diagnosis and retention planning, not a camera API replacement.
Photos are retained as evidence; item/discount extraction still uses the fiscal HTML, not photo OCR.
