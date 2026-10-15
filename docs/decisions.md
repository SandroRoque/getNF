# Decisions

Append-only log. Newest at the bottom. When a decision is reversed, add a new entry instead of editing the old one.

---

## D1. Native Android app in Kotlin + Jetpack Compose (2026-10-08)

The app must load a third-party page in a WebView, let the user pass a Cloudflare Turnstile, then read the
resulting DOM (`evaluateJavascript("document.documentElement.outerHTML")`). A PWA can't read cross-origin
pages, so it's out. Kotlin + Compose is the current Android default, and Flutter/RN would add a layer without benefit here.
Scaffolded with Google's `android create empty-activity` template (AGP 9, Navigation 3, Compose BOM).

- `applicationId` / namespace: `io.github.sandroroque.getnf`
- `minSdk 26` (Android 8). `compileSdk`/`targetSdk 36` (template default; SDK platform 37 is also installed).
- Target device: Motorola Edge 50 Pro. Distributed by sideloading APKs, not the Play Store.

## D2. Raw capture is the source of truth; parsing is derived (2026-10-08)

For each receipt the app stores: the photo(s), the QR URL, the 44-digit chave, the final URL and **raw HTML**
of the details page (plus the XML if the Download button works, see `docs/sefaz-sc.md`), and timestamps.
Structured data (items, totals) is *derived* and can be regenerated from the raw data at any time. Some details
(e.g. per-item discounts) exist only on paper, so photos are kept at full resolution.

The **chave** is the natural key (it's unique, and it's printed on the paper as a fallback when the QR code won't decode).

## D3. Capture flow: live QR detection, then photo, then WebView only when needed (2026-10-08)

Test media showed the QR code decodes from a crisp full-receipt photo but not from a blurry one, and only from
~25% of video frames. So:

1. CameraX preview runs a barcode analyzer on frames continuously, and locks once a QR code is read.
2. The user takes one or more full-resolution photos (long receipts may need several).
3. Fetch the URL. SEFAZ-SC challenges intermittently (`docs/sefaz-sc.md`), so the app opens the page in a
   WebView, and the user only has to act if a Turnstile shows up. The app detects the details page by URL
   (`NFCe_Detalhes.aspx`) and grabs the DOM automatically.
4. A manual-chave fallback is included, for when the QR code is damaged.

Barcode library: ML Kit bundled barcode model (works without Play Services) or zxing-cpp. Undecided.
The quick desktop test used zxing-cpp.

## D4. Offline-first on the phone, upload queue to a self-hosted backend (2026-10-08)

Room DB + files in app storage. WorkManager uploads pending receipts with retry/backoff when the server is
reachable. The app is fully usable while the NAS doesn't exist yet.

## D5. Backend: PocketBase (2026-10-08)

A single binary with SQLite, file storage, auth, an admin UI and an automatic REST API. That covers "I can make it
an API if I want to" without writing a server. Runs in Docker (`server/compose.yaml`) on the desktop for now; to
move it to the NAS later, copy `server/` and `pb_data/`. Schema goes in `server/pb_migrations` (in git). Server-side
parsing/derivation can be a JS hook (`pb_hooks`) or an external script that re-parses raw HTML.

Pinned to PocketBase v0.40.5 (the build verifies the release checksum). Port bound to 127.0.0.1. The emulator reaches it
at `http://10.0.2.2:8090`.

## D6. Phone ↔ server connectivity via Tailscale (planned, 2026-10-08)

Don't expose PocketBase to the internet. Put the phone and the server on a tailnet. This isn't set up yet, since it isn't
needed until the app runs on the real phone.

## D7. Dev loop is agent-drivable, without Android Studio (2026-10-08)

- SDK in `~/Android/Sdk` (user space, installed with cmdline-tools/`sdkmanager`, not the AUR).
- The `android` CLI from cmdline-tools (`android run/screen/layout/docs`) and adb let Claude build, install,
  tap, screenshot and inspect the UI tree. Its official skills are installed in `.claude/skills/`.
- AVD `getnf`: Pixel 8 profile, Android 16 (API 36) **Google Play** image. Play is there so the WebView/Chrome
  stays current, which helps with Turnstile.
- Fake camera: `v4l2loopback` at `/dev/video42`, with `ffmpeg` looping a real receipt video or photo into it
  and the emulator using it as `-camera-back webcamN` (`tools/fake-camera.sh`, `tools/emulator.sh --fakecam`).
- Real receipts in `test-media/` are gitignored (personal data).
- Gradle downloads JDK 17 for the build automatically (foojay toolchain resolver). The system JDK is 21.

## D8. Repo layout (2026-10-08)

```
android/      Gradle project (the app)
server/       PocketBase: Dockerfile, compose, migrations, hooks
tools/        dev scripts (env, doctor, emulator, fake camera, root setup)
docs/         decisions, dev environment, portal notes
test-media/   local-only real receipts (gitignored)
```

## D9. Fake camera: the emulator's built-in `videofile:`/`imagefile:` instead of v4l2loopback (2026-10-08, supersedes the fake-camera part of D7)

Emulator 37.x accepts `-camera-back videofile:<path>` and `-camera-back imagefile:<path>`. Tested with the receipt
video and the crisp photo: both show up correctly oriented in the stock camera app. No kernel module, no ffmpeg
process, no rotation tweaks. (The v4l2loopback route also failed in practice: the emulator listed the device as
`webcam1` but then reported it "not found" at startup.)

`tools/emulator.sh --camera <file>` picks the right mode. For stills it bakes the EXIF orientation into a temp PNG first.
The camera crops the image to its aspect ratio, so a tall full-receipt photo only shows part of the receipt. Videos,
or photos framed the way a phone would see them, are the realistic inputs.

`tools/fake-camera.sh` was removed, and `setup-system.sh` now only installs `android-udev`. The `v4l2loopback` module
that was already installed is harmless and can be removed (see `docs/dev-environment.md`).

## D10. QR decoding: ML Kit bundled barcode model via CameraX `MlKitAnalyzer` (2026-10-08, settles D3's open question)

`com.google.mlkit:barcode-scanning` (bundled model, no Play Services) plugged into a `LifecycleCameraController`
with `camera-mlkit-vision`. It's the path the CameraX docs recommend, so it needs no hand-written `ImageAnalysis`
plumbing. On the emulator, reading the receipt video, it locked onto the QR code in a few seconds, even though only ~25% of
frames decode on the desktop. Analysis stops once a valid chave is read (QR payload `p=` parameter + mod-11 check digit).
Photos use `CAPTURE_MODE_MAXIMIZE_QUALITY` at the highest available resolution. zxing-cpp stays the fallback if ML Kit
struggles on the real phone.

## D11. v1 storage: one directory per receipt with a JSON manifest, not Room (2026-10-08, deviates from D4)

`filesDir/receipts/<chave>/` holds `receipt.json`, `photo-<ts>.jpg` files and `page.html`. The manifest carries a
`version` that's bumped on every local change and the `uploadedVersion` last confirmed by the server, so "needs upload"
is `version != uploadedVersion`, and a change made during an upload isn't lost. Why not Room yet: there's one list screen and
no queries, the raw files are the source of truth anyway (D2), and Room needs KSP wiring on AGP 9's built-in Kotlin,
which is extra build risk for no v1 benefit. Revisit when there's derived data to query (items, totals). A Room index can be
rebuilt from these directories.

The page is saved from the WebView DOM (`'<!DOCTYPE html>' + outerHTML`) as UTF-8. The portal serves UTF-8 with no
charset meta, so this matches what the server sent. It's ~5% bigger than the curl fixture, since the DOM is
re-serialised, but has the same 20 items.

## D12. Upload: WorkManager → PocketBase `receipts` collection, logged in as a plain `users` record (2026-10-08)

- Schema in `server/pb_migrations/1791500000_create_receipts.js`: `chave` (unique, `^[0-9]{44}$`), `qr_url`,
  `page_url`, `captured_at`, `page_fetched_at`, `photos` (≤20 JPEGs, 30 MB each), `page` (file), autodates.
  List/view/create/update need any authenticated user. Delete is superuser-only.
- The app logs in with email/password as a normal `users` record (`phone@getnf.local`, password in
  `server/.env.local` as `DEV_APP_*`). The superuser credentials never go near the phone. Debug builds bake the dev URL
  (`http://10.0.2.2:8090`) and that login in as defaults via `BuildConfig`. Release builds start empty, and the
  Settings screen overrides both (stored in app-private SharedPreferences).
- Upload is an idempotent upsert: find the record by `chave` (or the cached `remoteId`), then POST or PATCH with *all* files
  again. Re-sending photos costs bandwidth but makes retries trivially safe. An upload runs when the user leaves the
  page screen, from the home screen's Upload button, after saving Settings, and at app start if anything is pending.
  Network errors → `Result.retry()` with exponential backoff from 30 s. HTTP errors are shown on the receipt card.
- Cleartext HTTP is allowed app-wide (`network_security_config.xml`): the dev server is plain HTTP, and the phone
  will reach the NAS over Tailscale (D6), where WireGuard encrypts the traffic. A config can't whitelist the 100.64/10 range.
- `allowBackup="false"`: app storage holds receipts and the server password, so it shouldn't go to Google Drive backups.
- OkHttp pinned to 5.4.0: 5.5.0 requires compileSdk 37, which AGP 9.0.1 doesn't support yet.

## D13. v1 scope (2026-10-08)

In: QR scan → photos → WebView with automatic details-page capture → local save → background upload; Settings.
Deferred: manual-chave fallback (D3 step 4; needs a look at how the portal's chave form behaves), the XML Download
button, viewing photos/pages in the app, deleting receipts, parsing. Receipts that already have a page aren't tappable,
so the app never re-requests the portal for them.

## D14. Derived data model: products, categories (tree + tags), item lines with prorated discounts (2026-10-08)

Goal: spending per category (coffee, fruit, meat, grains, alcohol...) by week/month/quarter/year, and price tracking per
product and per class of product. This dataset is one branch of a larger "measure everything" dataset, so the
shapes are meant to be shared with future sources (card/bank statements, etc.).

- **No XML.** The portal's XML download requires an ICP-Brasil client certificate (`docs/sefaz-sc.md`). The details
  page HTML and the photos are the inputs.
- **Layers.** Raw capture (D2) → parsed item lines → shared products/categories → analysis. Each layer is derived from
  the one below and can be rebuilt. Parsing runs on the server (a PocketBase hook), so fixing the parser means
  re-running it, with no app update.
- **Categorize products, not lines.** A product is keyed by its EAN when the code is a GTIN, otherwise by
  (issuer CNPJ, store code) for bulk/in-store items (`243` MACA NAC KG). Each product is classified once, and every line
  inherits it. New products land as unclassified.
- **One main category per product** from a tree (`taxonomy/categories.txt`), so category totals always add up to what was spent.
  Plus **any number of tags** (`taxonomy/tags.txt`) for cross-cutting questions. Tags overlap and are never summed
  together. The taxonomy lives at the repo root because it's shared across sources. Node ids are slug paths (`food.produce.fruit`).
- **Discounts.** The page has only the total discount. Each line stores its gross total, its discount and the discount's `source`:
  `prorated` (total discount split proportionally to line totals, with cents distributed so the sum is exact) until the
  user asks for a closer look at a receipt. Then the per-item discounts are read from the photos (vision model) and
  stored with `source = photo`. The receipt total is unchanged, only the split across lines/categories moves.
- **Dates.** Buckets use the emission date from the page, not the scan date.
- **Price per class.** Bulk items are already priced per kg. Packaged goods need the size parsed from the description
  ("250G", "1L"), stored per product and correctable by hand.
- Categorizing happens in the PocketBase admin UI at first. An app screen and LLM-suggested categories come later.

## D15. Parser and derivation implemented as PocketBase JS hooks; `spending_lines` view for reports (2026-10-08)

Implements D14.
- `server/pb_hooks/lib/nfce_page.js` (parser) and `lib/derive.js` (product keys, sizes, discount proration, taxonomy
  files) are plain CommonJS with no dependencies. The same files run in PocketBase's JS engine and under
  `node --test "server/test/*.test.js"`. Fixtures in `server/test/fixtures/` are made with `tools/make-nfce-fixture.py`
  (reduced and sanitised, per CLAUDE.md). The parser handles both the server's HTML and the WebView's DOM (jQuery Mobile markup).
- `lib/ingest.js` runs after every receipt create/update. It re-parses when the page file changed or the parser
  `VERSION` went up, rebuilds that receipt's `receipt_items` in one transaction, keeps `photo` discounts by line number, and
  upserts `products`. On server start it syncs `taxonomy/*.txt` (mounted read-only) into `categories`/`tags`, refusing to
  delete nodes that are in use, and re-parses stale receipts. `POST /api/getnf/reparse[?all=1]` (superuser) does it by hand.
- Money is stored as reais (2-decimal numbers), but proration is done in integer cents, so line discounts add up exactly.
- `spending_lines` (a PocketBase view) is the read API for reports: one flat row per line with day/ISO week/month/quarter/year
  in store-local time (UTC-3 hardcoded for SC), category path plus level-1/level-2, tags, net/gross/discount, and price per
  kg/l. PocketBase's view checker only accepts plain columns and function calls; `||` and `CASE` must be parenthesised.
- Parse problems never fail the phone's upload. They land in `receipts.parse_error`, and the cross-check warnings
  (item count, item sum vs total, total − discount vs payable) go there too.

## D16. The WebView skips SEFAZ subresources it doesn't need (2026-10-09)

One receipt visit measured ~59 requests to SEFAZ hosts (docs/sefaz-sc.md), and the portal blocks IPs that send "too
many". `shouldInterceptRequest` now answers some requests with an empty response instead of fetching them
(`SefazPage.shouldBlock`, unit-tested):
- **Details page:** every SEFAZ subresource. We only need its HTML, and the parser handles the un-scripted DOM (it's the
  same as the server HTML fixture). The user sees an unstyled page for a moment, which is fine.
- **Challenge and other SEFAZ pages:** images and fonts only. Scripts and styles stay, because Turnstile and the Validar
  postback depend on them, and finding the minimal set would take live experiments, which is the thing we're trying not to do.
- **Never** Cloudflare or any non-SEFAZ host.

The page kind comes from `onPageStarted` (after redirects). Assets requested in the instant before it fires still load:
the cost is less saving, never breakage. Each visit logs `fetched:` / `blocked:` counts per host, so the next real
visit shows the actual saving.

## D17. The app paces SEFAZ lookups per network, and only advises (2026-10-09)

Working hypothesis from two blocks (docs/sefaz-sc.md): SEFAZ blocks an IP after ~5 lookups per hour. Switching the
phone's data SIM got an immediate pass, which confirms the block is per IP.
- `LookupBudget` logs our own lookups: main-frame requests to SEFAZ, i.e. opening a QR URL or the Validar postback.
  Redirects aren't seen by `shouldInterceptRequest`, so a 3-hop lookup counts once. The log is kept per network key
  (`CurrentNetwork`): `wifi` (one bucket, since telling SSIDs apart needs the location permission) or
  `cellular:<subscription id>` (one bucket per SIM). Seeing the rate-limit page marks that network as used up for an hour.
  Limit and window are constants (`LIMIT = 5`, 1 hour), to be adjusted as we learn more.
- **Advisory only.** When the budget is used up, the scan screen's Next, the home screen's "Fetch next" and the page
  screen's Next show a "Fetch the page later?" dialog with the next free slot, offering **Later** and **Fetch anyway**.
  The user knows things the app can't, like having just switched SIM or that the portal is behaving today.
- Scanning no longer forces the page step. Once the QR is read, **Later** keeps the photos and queues the receipt. Home
  shows "N receipt(s) waiting for their SEFAZ page" with the budget line and "Fetch next" (oldest first). After a page
  saves, **Next** goes straight to the next waiting one.
- Budgets start empty on a fresh install (or after clearing data), even if the network was used recently.

## D18. The phone uploads over home Wi‑Fi; Tailscale postponed (2026-10-09, defers D6)

Uploads needed the phone on a USB cable (`adb reverse`). PocketBase now also listens on the PC's LAN address, and the
phone's server URL is `http://192.168.4.4:8090`.
- **Why not Tailscale yet:** it adds an account and a background VPN app on the phone, plus a service on the PC, to get
  uploads from anywhere. Receipts aren't urgent: the app already queues them and WorkManager retries with backoff, so
  "uploads when I'm home" is enough. The only cost of the LAN route is one more port mapping. Revisit with the NAS.
- **Bound to the LAN address, never 0.0.0.0.** Docker's published ports go around ufw, so a 0.0.0.0 binding would be
  open on every interface whatever the firewall says. The address lives in `server/.env` (`GETNF_LAN_IP`, not in git).
  When unset, compose binds a second loopback address, so a fresh clone still starts.
- **Exposure:** other devices on the home Wi‑Fi can reach PocketBase's API and admin login. Everything needs a login
  (D12). Traffic is plain HTTP on the home network, where the risk is low.
- **Requirements:** the address must stay fixed (a DHCP reservation in the router), and Docker must start at boot
  (`setup-system.sh` enables `docker.service`; until then it was socket-activated, so PocketBase stayed down after a reboot
  until something ran a docker command). If the address can't be bound, the container won't start, so the failure is obvious.

## D19. Live monthly spending dashboard on the existing PocketBase server (2026-10-10)

- **Local web page:** buildless HTML/CSS/JavaScript and SVG charts in `server/pb_public/dashboard/`, mounted read-only
  at `/pb/pb_public`. PocketBase serves `/dashboard/` on its existing port and bindings. No additional service,
  external chart library, cloud publication, or Android screen is needed.
- **Read-only and authenticated:** sign in with the existing `users` account used by the phone. The browser reads all
  pages of `spending_lines` and `categories`; no schema changes or new API endpoints. Tokens live in session storage,
  never in URLs or source; logout/expired authentication clears both token and displayed data.
- **Interaction:** stacked monthly bars select a month; its category pie or legend selects a category; products group
  by product ID with expandable dated purchases. All three views stay together, stacked vertically on phones.
- **Defaults:** latest 12 calendar months through the current month, latest captured month selected within the range,
  and Detailed category depth (first two taxonomy levels). Controls allow other ranges, All history, Broad, or Most
  specific categories. Shallow assignments keep their category; uncategorized products remain explicit.
- **Meaning:** sum line `net` in integer centavos, bucket by the existing store-local receipt month, and display BRL.
  Quantity totals remain separate by unit. Empty months mean no recorded receipts, not zero actual spending; undated
  lines are excluded and counted visibly. Item discounts may be prorated, as in D14.
- **Freshness:** load on opening/login and explicit Refresh, with no background polling. Refresh preserves valid
  selections. Errors retain previously loaded data with an explicit notice, or a retry prompt before the first load.
- **Checks:** reconciliation across bars/pie/products/purchases, pagination, unit preservation, rounding, date gaps,
  and authentication/API errors are covered by dashboard tests. Browser verification uses an isolated profile and
  the real app account, without creating or changing receipt records.

## D20. Dashboard accepts the saved PocketBase admin login too (2026-10-10)

The admin UI and dashboard share an origin, so Chromium may autofill the admin credentials on the dashboard.
Rejecting those valid credentials made the dashboard appear broken. Sign-in now tries the phone's `users` collection
first, then `_superusers` only after an explicit invalid-credentials response. Network, server, and rate-limit errors
do not trigger a second attempt. The session stores the authenticated collection alongside its token so page reload
and Refresh use the correct auth endpoint. Older phone-account sessions default to `users`. Logout clears both.
The dashboard remains read-only and neither account's password nor the collection access rules change.

## D21. Selected month's NF list alongside category drill-down (2026-10-10)

The dashboard now also reads the authenticated `receipts` collection and the existing receipt relation on
`spending_lines`. A full-width Receipts this month section lists NFs by issue date (newest first), with store, parsed
item count, and actual total paid. Expand a row to see its items across all categories. The NF list follows the selected
month and remains independent of pie/category selection. Receipt month uses UTC-3, matching D15; capture dates are
not used. Dated NFs without parsed items still appear, while undated NFs are counted in the data note. Parser issues
and differences between item sums and receipt totals remain visible rather than replacing receipt totals with item sums.
This adds no schema changes or new endpoints, and the dashboard remains read-only.

## D22. Distinct category colors and stronger pie selection (2026-10-10)

Hash-generated hues made unrelated categories look identical (produce and meat, for example). Main category groups
now have an explicit palette. Additional category colors are chosen by maximizing perceptual separation in OKLab
from the colors already used at that depth. Registering the full loaded history before filtering keeps assignments
consistent across month/range changes; the charts and legends share the same category color registry. Uncategorized
remains neutral gray. A selected pie slice moves out slightly, retains its full color, and dims the other slices;
hover/keyboard focus restores a slice's full color. Selection remains indicated by its outline and legend state too.

## D23. Android autonomy, user-managed catalog, and personal inflation objectives (2026-10-14)

The user set a new product direction: the app must be self-contained, Android must provide a first-class experience,
and the dashboard must remain accessible on PC. This revises the long-term server-centered processing direction
in D14–D15 and the dashboard scope in D19; it does not change the current implementation by itself.

Manual item creation, editing, deletion, and categorization must be available to the user. Agent-assisted catalog
maintenance is not a sufficient product workflow. Entity boundaries and deletion/history behavior still need design.

The user proposed IPCI — Índice de Preços ao Consumidor Individualíssimo — as an individual inflation measure.
Its methodology is still open. The user also wants to consider “system one”, beginning with “laya” locally and later
refining through newer APIs; the exact tools/providers remain to be clarified, so no integration is selected yet.

[objectives.md](objectives.md) records the requirements, open questions, and proposed incremental stages.
The immediate request is to persist these objectives, not implement all changes simultaneously.

## D24. On-device parser, optional shared curation, and contributor-only collective analysis (2026-10-14)

After considering VPS ETL, the user chose parsing on Android. Users must be able to manually classify, correct
volume/unit/weight, review their data, and use personal analyses without an external processing service.

Optional receipt contributions support classification, correction, and review by the service operator, building
a comprehensive product/issuer catalog and price history. The desired scope covers available fiscal, financial,
and product data; the user prefers not to collect the buyer's name, CPF, or card data. Field and evidence transport
must still be designed, including buyer data present in raw captures and receipt lookup references.

Collective-data analyses are available only to users who share receipt data. Local-only users retain their personal
analyses. Private PC access is distinct from collective contribution. Hosting, contribution scope, withdrawal rules,
and reconciliation of curated versus personal corrections remain open. No service is deployed by this decision.

See [local-and-shared-modes.md](local-and-shared-modes.md). This resolves the parser-location question in the ETL
comparison; the current implementation still needs migration as described in the roadmap.

## D25. Continuous camera capture with automatic completion (2026-10-14)

The user wants a fluid capture session: point/move the camera over the receipt while the app gathers sufficient
images, guides adjustments, and closes the camera automatically. Taking individual photos and deciding when
capture is complete must not be required in the primary flow. This replaces the target manual-photo flow in D3/D13;
the current app still stops QR analysis once identified and requires manual photos and completion.

Frame selection, document edges, QR detection, focus/legibility checks, and coverage tracking are candidate means,
not selected algorithms. Long receipts require coverage across overlapping captures; readable QR or detected edges
alone do not prove all receipt text was captured. Library choice remains open, including evaluating existing scanners.

See [automatic-capture.md](automatic-capture.md) for incremental delivery and evidence required for completion.

## D26. First automatic whole-document capture increment (2026-10-14)

Use the existing CameraX controller, wrapped MlKitAnalyzer (QR plus bundled Latin text recognition), and bundled
OpenCV for document geometry/quality. Keep analyzing after identifying the QR. A whole quadrilateral with margins,
legible receipt text, matching QR inside the paper, and a stable hold triggers a high-resolution still. Revalidate
the saved image before attaching it to ReceiptStore; failed images are removed and capture retries.

Automatic completion returns Home. Portal lookup stays an explicit Home action, so capture completion does not
silently consume a SEFAZ lookup. Preserve a manual multi-photo fallback offered after 15 seconds for long receipts
and difficult scenes; continuous automatic multi-segment coverage remains a separate pending increment of D25.

Build, unit tests, device vision tests, lint, and an offline emulator camera journey with a fictitious receipt
validate this first increment. No live SEFAZ requests were used. Thresholds require real-device calibration, and
geometric coverage/OCR presence are quality heuristics rather than guarantees about every character. Native library
and bundled model size should be considered when preparing public distribution.

## D27. Sharpness must be measured exclusively inside the detected receipt area (2026-10-14)

The user made receipt-area detection mandatory: only the visible receipt interior may contribute to sharpness.
Exclude background and paper/background boundary responses, including filter neighborhoods. A bounding rectangle
that includes background is insufficient. Support partial receipt regions without requiring all four corners.

When localization is uncertain, mark sharpness unevaluated rather than scoring the whole frame. Apply the same
rule to candidate frames and saved images. Per-content-region quality must prevent a sharp QR from masking blurred
text elsewhere. Algorithms remain to be selected; the current inner rectangular ROI does not fully implement this
requirement. See [automatic-capture.md](automatic-capture.md) for proposed validation criteria.


## D28. Continuous capture with masked quality and connected segment coverage (2026-10-14)

Replace D26's whole-page requirement and manual fallback with automatic overlapping segments. Draw detected
edge segments; show simultaneous need-driven hints and one movement destination. Top, bottom, QR, geometry,
quality and capture are evaluated continuously, rather than gated steps. Two visible lateral edges suffice.

Use CameraX analysis frames as the actual captured images. OpenCV detects/refines a paper mask and measures
native-resolution sharpness strictly inside its eroded interior, including filter support. Assess content bands
separately; a sharp QR cannot validate blurred product text. Recheck the encoded JPEG with the same mask.

Use ORB/RANSAC content registration to establish overlap. Never infer complete coverage from endpoints alone.
A synthetic top-to-bottom jump exposed false closure with optical flow on repeated receipt rows; remove that
fallback and require descriptor evidence. Complete only with persisted connected coverage and an associated QR.

Keep provisional images private. Exit without QR discards the attempt. Exit with QR offers code-only retention,
full attempt discard, or continued capture; preserve all pre-existing note evidence. Rotation retains the session.
Process recovery keeps only identified code for unfinished attempts; completed batch commits replay idempotently.
ReceiptStore imports a complete batch atomically, storing original JPEGs, masks and geometry/quality metadata.
Raise PocketBase's existing photos limit from 20 to 100; do not replace the database.

Synthetic emulator tests and isolated migration validation are documented in capture-validation.md. Camera
calibration on the Motorola is still required; this decision does not claim field-proven legibility or completeness.


## D29. Bound mask refinement and preserve continuous AF (2026-10-15)

Physical Motorola testing exposed multi-second overlay latency: per-frame GrabCut on the full 720-pixel scene
cost 1.2–3.2 seconds in observed samples. Limit segmentation to a cropped paper ROI on a 240-pixel grid; erode
before enlargement to exclude coarse boundary uncertainty. Keep focus evaluation at native resolution, inside
the conservative mask and outside filter support. Publish detected segments before mask/QR/storage work, copy
luminance in bulk, and evaluate focus once after QR detection. Keep latest-frame backpressure.

Remove area thresholds relative to the entire scene: a narrow receipt and its endpoints must not be rejected
because there is table around them. Use masked sample counts instead. A 22%-width synthetic receipt adds coverage.

Do not periodically retrigger AF every three seconds. Retain CameraX continuous AF with explicit tap/region focus,
expiring manual focus requests after two seconds. Expose torch and zoom controls. Camera2 interop remains available
if measurements establish a specific unmet hardware control; processing latency alone does not justify a rewrite.
Local debug stage timings contain no fiscal data. See capture-validation.md for physical measurements and limits.


## D30. Learn receipt geometry before capture and register zoom independently of photos (2026-10-15)

Separate session geometry, current frame localization, and validated persisted coverage. A confident observation
of all four physical edges establishes provisional geometry even if text is blurred. Confirm it with another
compatible overview or two consecutive compatible top observations. Polygon corners at image boundaries do not
count as physical edges. Keep the original width-256 coordinate basis when a later overview is registered.

Deliver the first slice for overview → closer top view. Use a top-edge similarity transform only with both
lateral edges, at most 8° deviation from perpendicular, and at most 10% width taper. Strong perspective requires
content matches; missing support means lost localization. Bottom-based geometry and predicted edge search are
separate future increments. Geometry lives in the ViewModel session; no schema change or partial-photo resume.

Extract ORB once per frame at a bounded paper-relative scale, independently of photo eligibility. Retain usable
anchors for new extent/scale, capped at 100. Use reciprocal matches with a ratio test in either direction, preserving
RANSAC's 12-inlier/55% consensus, spatial spread and projective guards. Synthetic repeated product rows exposed
false matches during scale normalization; the top-to-bottom jump regression remains a release check.

Use orientation-restricted transverse Hough voting plus observed edge support and paper/background transition
checks to retain faint overview endpoints. Keep sharpness confined to the conservative current paper mask.
A full sharp receipt may complete with one validated photo; blurry geometry cannot fill a content gap. QR stays
independent and required. Show confirmed geometry, and request focus/holding when the top is already visible.
Build, lint, 41 unit and 19 synthetic instrumented tests pass; physical latency/legibility remain to be verified.


## D31. Validate paper regions and render the actual analysis area (2026-10-15)

Physical testing disproved the assumption that the old Hough overlay delimited the paper. Raw scene-line
candidates were published before mask/localization validation; line length and pair width rewarded the wrong
background boundaries. Replace the primary detector with bounded region proposals from both threshold
polarities and gradient contours, scored by observed boundary transition support. Reject components that touch
the lateral frame limits, while allowing clipped longitudinal ends. Keep a photometrically validated line-based
fallback for partial/faint regions; no unvalidated line candidates in the normal overlay.

Separate the observed irregular contour, conservative paper mask, and registration quadrilateral. Focus uses a
shared eroded mask builder with QR/filter-neighbourhood exclusion, also used to expose the actual focus area.
The user chose an always-visible contour with optional translucent mask and both upright/sideways support.
Publish contour/mask/axis with a frame ID after analysis instead of mixing early raw lines with older state.
Debug offers one in-memory frozen analysis frame; it is not persisted or uploaded.

Normalize the longitudinal axis with a quarter turn and apply that transform consistently to camera mapping,
QR corners, quality and saved bitmaps. Explicit matrices compose analysis→buffer→sensor→view. Clipped ends
identify the axis when a close visible strip is wider than it is long. Preserve world coordinates and swap mapped
endpoints when content registration establishes a reversed axis; an orientation change cannot use geometric
fallback without content association. Preserve that direction on subsequent full-outline fallback. A bounding
rectangle cannot hide strong perspective and enable the top similarity model.

Use a stricter ORB ratio of 0.6 in either reciprocal direction; retain at least 12 inliers and round the 55% consensus
requirement upward. Existing gap/long-scan checks and new rotation/scale cases pass. Build, lint, 41 unit and
30 synthetic instrumented tests pass. Physical acceptance and the visual-update p95 target remain to be measured.
No database, camera API, neural model or external service change is part of this repair.


## D32. Do not initialize an unanchored partial map or rebase saved coverage (2026-10-15)

The first physical check of D31 showed a paper-following contour and much faster region analysis, but sessions
could remain unregistered. A clean attempt reached `located=true, sharp=true, anchors=0, tracked=false`.
Prevent a partial frame without usable descriptors from initializing world coordinates. A full-outline map may
still initialize without sharpness/descriptors for the overview→zoom flow.

If no anchor exists and a usable full overview appears, the engine may replace that provisional reference only
when there are no saved segments and no pending photo candidate. Once any photo exists, preserve the coordinate
basis and require registration. Tests exercise both allowed recovery and refusal to rebase existing coverage.
This fixes a demonstrated tracker dead-end; it does not establish physical acceptance of the full scan flow.

## D33. Require a usable anchor before saving and retain orientation through fallback (2026-10-15)

The follow-up physical attempt exposed a second dead-end: an unanchored full frame could still save a candidate,
which correctly disabled reference replacement but prevented recovery. Require a retained usable anchor before
selecting a photo; learning geometry remains independent of sharpness/anchors. A synthetic engine regression
exercises misleading initial geometry without descriptors, zero saved photos, and recovery on a useful overview.

When threshold/contour proposals fail, try validated line proposals in both quarter-turn orientations, preferring
the last located orientation. Previously the fallback silently returned to vertical-only analysis for a horizontal
receipt. Test a faint partial strip on a light table in both orientations. Keep one current-frame mask and apply
its orientation consistently to QR, photos and preview mapping.

In landscape, reserve side panels for status/controls and the centre for the preview/overlay; controls must not
cover the whole receipt. Preserve the mask-visibility preference through recreation. A Compose test checks
landscape control visibility and retained preference. The physical capture remains unapproved until retested.

D33 validation: 41 unit and 35 instrumented tests pass, including low-contrast open strips in both orientations.
The line fallback uses Canny 8/24 after that regression exposed the old high threshold; focus thresholds remain
unchanged. Landscape reserves 216 dp side panels; mask visibility survives rotation. The APK was installed on
the Motorola preserving app data. Functional physical acceptance is still required.


## D34. Retain development captures; plan quality and redundancy selection separately (2026-10-15)

Two D33 scans completed on the Motorola, appending batches of four and two images to the same existing NF.
Review of the six stored JPEGs found four legible main-content images, one clearly motion-blurred image accepted
by the quality check, and one softer image. This establishes persistence/completion, not reliable legibility.
Structured data still comes from fiscal HTML; photo OCR is not implemented.

The user accepts keeping many images per NF during development and wants eventual heuristics for rejecting
bad captures and removing redundancy. Preserve current evidence; this checkpoint adds no deletion policy.
The current 100-photo technical cap remains, with the server migration tested only in isolation. It is not
the desired final retention policy. Future selection must preserve unique coverage and useful detail across
repeated scans, and be evaluated as a dry run before enabling deletion. See capture-retention-plan.md.

The phone is unavailable while charging; synthetic blur/quality evaluation, retention design and repository
checkpointing can proceed independently. Further physical validation remains necessary before accepting
the quality filter or enabling automatic pruning.

## D35. Review findings: fix quality and upload risks first, then start the data model (2026-10-15)

A code review after D34 (build, lint, 41 unit and 24 server tests passing) found defects the tests don't cover.
Recorded at the user's request, together with how they fit the roadmap. Nothing is implemented by this entry.

- **Directional blur passes the focus check.** `DocumentVision.quality` divides gradient energy at two scales but
  combines X and Y with `Core.magnitude`. Motion blur only removes edges along the motion; perpendicular edges keep
  the ratio above 1.18. This is the likely cause of the accepted blurred JPEG (score 1.324, D34). Evaluate the
  ratio per orientation (at least X and Y) and take the worst.
- **Blurred text can count as blank paper.** Tiles with local std < 5 are skipped as empty. Blur lowers contrast, so
  faint blurred thermal print can fall below it. Blank bands are usable and bridge coverage, and a decoded QR alone
  sets `anyContent`. An illegible region can then be credited as covered. The synthetic benchmark must include it.
- **The 100-photo migration is not a manual step.** `server/pb_migrations` is mounted, so PocketBase applies it on the
  next container restart (e.g. after `setup-system.sh` and a reboot). Until then, receipts with more than 20 photos
  fail to upload. Back up `pb_data` and restart deliberately, or cap Android at 20 until then.
- **Upload replaces the server's photo list with the phone's.** `PocketBaseClient.upsert` resends every photo on each
  version bump; masks and `captureSegments` never leave the phone. The server is the only photo backup and the phone
  the only copy of capture metadata. Two loss paths follow: future local pruning (capture-retention-plan step 3)
  would delete the same photos on the server, and a reinstall followed by a rescan of the same chave would replace
  the server's photos with the new, shorter list. The second depends on PocketBase's PATCH semantics for multi-file
  fields; verify in an isolated database. Stage 1 of the roadmap must design append-only, content-identified evidence
  upload before any deletion is enabled.
- **Capture can dead-end near the photo cap.** Until the QR is read, `photoAllowance` is 100. If the receipt already
  holds nearly 100 photos and the QR comes last, the session saves past the remaining allowance, then completion is
  blocked and exit discards the attempt. The message also hardcodes "100".
- **Smaller items:** the debug APK is 225 MB (OpenCV for every ABI; distribution needs ABI filtering);
  `mlkit-text-recognition` is declared but unused since D28; every located frame converts and rotates a full
  bitmap even when it won't become a candidate; the capture error message hides the cause even in debug builds.

**Approaches to evaluate** (proposals, not choices):
1. Use on-device OCR (ML Kit, already the candidate for discounts in discounts-and-ocr.md) on candidates only, as the
   legibility criterion, instead of a gradient ratio alone. One component serves capture quality and stage 3.
2. Move final image selection after the session (capture-retention-plan step 2) and keep real-time logic to
   "enough coverage exists". Much of D29–D33's tuning would stop being critical.
3. Revisit how much full coverage is worth: the only planned consumer of the photos is per-item discount OCR.
4. Take the stored photo with `ImageCapture` when analysis picks the moment, instead of the video-exposure frame.

**Proposed order:** (a) decide the migration, fix the cap dead-end and the stale OCR note; (b) synthetic blur
benchmark covering both quality defects, per-orientation focus, OCR as a legibility test; (c) pause further
geometry work (bottom localization, predicted edge search) and start roadmap stage 1, with photos as evidence with
metadata, append-only upload and a deletion policy; (d) retention step 2 becomes post-session selection on that model.

## D36. Photo limit applied on the server; capture stops cleanly at the cap; PATCH replaces photos (2026-10-15)

First slice of D35's order, step (a).

- **Server:** backup `pre-photo-limit-2026-10-15.zip` created through PocketBase's backup API (kept in
  `pb_data/backups` and copied to `~/getnf-backups/`, outside the repo; 147 files, `data.db` and `storage/`). A
  deliberate restart applied the 20 → 100 migration: `photos.maxSelect` is 100; receipts, items, products,
  categories and `spending_lines` counts were identical before and after (9 / 135 / 84 / 75 / 135); the
  dashboard responds. The largest receipt had 12 photos, so nothing was failing yet.
- **Capture cap:** `CaptureEngine` keeps the NF's existing photo count and derives the allowance from it. When an
  attempt can't fit (QR read last after too many segments, or no slot left while coverage is still missing) it
  stops selecting and saving, drops the pending candidate, never completes, and keeps a message with the real
  numbers visible until exit. `KEEP_CODE` keeps the previous photos and drops the attempt's (D28). A last free slot
  still completes. Debug builds append the exception class and message to the generic capture error.
- **PATCH semantics, verified in an isolated container** (empty database, fictitious chave, synthetic JPEGs): a
  record with 3 photos patched the way `PocketBaseClient.upsert` does, with 1 photo, ends with 1 photo, and the 2
  dropped files are deleted from storage. This confirms D35's loss paths: local pruning, or a reinstall plus a
  rescan, would delete server photos. Fix it in roadmap stage 1 (append-only evidence upload); until then, no
  local deletion of photos.
- **Found on the way, not fixed:** with the hooks mounted, PocketBase 0.40.5 doesn't start on an empty database.
  Bootstrap logs `taxonomy sync failed: sql: no rows in result set` and `parseStale` then exits with the same error.
  The live server has data and is unaffected, but restoring onto a new machine (e.g. the NAS) needs this fixed.
- **Not run:** the two new instrumented tests (`qrReadLastOnAFullNoteStopsWithAMessageAndKeepsExistingPhotos`,
  `lastFreeSlotStillCompletes`). The emulator refused to start because `/home` had 1.8 GB free. Build, lint and
  unit tests pass.

D36 validation: after freeing disk space, all 37 instrumented tests pass on the emulator, including the two new
cap tests.
