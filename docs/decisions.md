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

## D37. Capture legibility decided by on-device OCR; the gradient score only pre-filters (2026-10-15)

A synthetic benchmark with OCR ground truth (capture-validation.md) reproduced both D35 defects and showed that
the gradient ratio can't be calibrated: it measures blur in pixels while legibility depends on blur relative to
character size. Sharp 36 px text failed and 20 px text with 9 px motion blur passed; per-direction scoring fixed
motion blur but no threshold separated legible from illegible (1.18: 55 illegible accepted, 1,194 of 1,532
legible rejected). OCR confidence alone, without ground truth, did: at 0.8, 0 illegible accepted.

- **Saved candidates are read with ML Kit Latin OCR** (already a dependency, unused since D28), once per candidate
  on the decoded JPEG, never per analysis frame (`TextLegibility`). A content band counts as covered only when OCR
  read at least one line in it and every line has confidence ≥ 0.8. Only the legible parts of the candidate's
  bands are credited (mapped linearly onto the tracked bands); a candidate with nothing legible is discarded.
  Lines outside the paper mask are ignored. When no line is confident, a second pass reads the image rotated
  180°, because the canonical image can be upside down (D31).
- **Gradient score:** per-direction ratio (worst of X and Y), threshold lowered from 1.18 to 0.7. It now only keeps
  OCR off gross blur. Pre-filter + OCR on the benchmark: 0 illegible accepted, 594 of 1,532 legible rejected.
- **Blank paper:** content is also detected from line-scale structure (difference of Gaussians, read only where
  its ~15 px support stays inside the paper), so blurred faint print isn't classified as blank. A decoded QR no
  longer makes blank bands bridgeable; only text OCR read in the same image does.
- **Exceptions, deliberately:** a band whose only content is the decoded QR passes (the QR is its evidence); a
  band holding a paper end with no OCR line counts as blank, because the coarse mask can include a sliver of
  table there. Without it the ends were never credited and sideways captures couldn't complete.
- **Guidance:** with a loose pre-filter, the focus hint shows for 1.5 s after OCR rejects a candidate.
- **Tests:** the instrumented install grants runtime permissions (`installOptions -g`); the camera lifecycle tests
  depended on a manual `pm grant` that each run's uninstall wiped. 41 unit tests, 38 instrumented tests including
  the benchmark, and lint pass.

**Limits:** synthetic evidence only, and the ground truth is machine legibility. About 39% of legible bands are
still rejected, so captures need more frames. OCR runs synchronously on the capture executor: analysis and the
overlay pause while a candidate is read (1.6 s median on the emulator, unmeasured on the Motorola). Faint
blurred print below the structure threshold can still pass as blank inside a frame where other text is legible.
Next: measure OCR time and confidences on the Motorola, including the six D34 JPEGs, before trusting 0.8.

## D38. OCR legibility recalibrated on real receipts: band median confidence >= 0.5 (2026-10-15)

D37's threshold came from synthetic text and was wrong for thermal paper. On the ten real capture JPEGs
(capture-validation.md), ML Kit's per-line confidence for legible print was 0.5–0.85 and no band passed "every
line >= 0.8", so captures could never complete. The motion-blurred JPEG produced no OCR line at all, which is the
signal that matters.

- A band with OCR lines is legible when their **median** confidence is >= 0.5.
- Content OCR couldn't read (logo, QR, a table sliver at a paper end, gaps between items) passes only between legible
  text bands of the same image, or when it holds the decoded QR or a paper end. An image without legible text credits
  nothing. Blank paper bridges only in images with legible text.
- Real JPEGs: blurred 0 of 13 content bands; the softer one 9 of 15; legible ones 12/13 to 16/19.
- Synthetic benchmark: 111 of 1,476 illegible bands accepted, 107 of them partially legible (50–89% characters);
  4 severe (< 50%). 129 of 1,532 legible rejected (594 under D37). The benchmark now asserts these as regression
  bounds (severe <= 5, legible rejected <= 15%) instead of zero false accepts.
- `CaptureEvidenceEvalTest` evaluates real JPEGs placed in the app's private `files/eval/` on a test device; it is
  skipped otherwise and logs only numbers.
- Instrumented tests must not run on the phone: connectedAndroidTest uninstalls the app and would wipe its receipts.
  With the phone attached, install APKs with `adb -s <emulator> install -r -g` and run `am instrument` there.

**Limits:** one illegible real photo is thin evidence; the threshold needs more real captures, including faint and
partially blurred ones. A blurred strip between legible text bands would pass as unread content; depth-of-field blur
usually grows toward one end, but it isn't excluded. OCR time on the Motorola is still unmeasured.

## D39. A region must look like a receipt; long unread stretches aren't print; an anchorless map yields (2026-10-15)

The first live capture with D38 exposed three faults. On a white table the phone's rectangular shadow was located as
the receipt: it has a strong boundary but no texture, so it never produced anchors, and frames with it in the mask were
saved. On a red board, the board's outline became the mask with the receipt inside. And a distant sideways overview
created an anchorless map that close upright frames could never localize.

- **Paper validation** (`DocumentVision.paperLike`), applied to every region proposal and to the line fallback:
  at least 70% of the interior (25 px away from the boundary, at 720 px scale) must be light (>= 0.6 of the
  scene's 90th-percentile luminance); mean colour saturation <= 40; and >= 0.4% of the interior must deviate from
  its σ3 blur by >= 6 levels (print). The analyzer now reads the camera's U/V planes at quarter resolution.
  Receipts are always light but come in white, yellow, green or blue pastel; those read ~3–25 and pass.
- Real frames (capture-validation.md): the shadow frames are no longer located (light fraction 0.00); on the red
  board, the board is rejected (0.63 light) and the receipt inside it located; all frames with a visible receipt
  that reached validation passed (0.76–1.00 light, saturation 3–15).
- **Legibility:** unread content between legible text counts only in runs of <= 2 bands (a logo, a gap), and an image
  needs >= 2 legible text bands to credit anything. A wrong mask over a shadow was credited by D38's bracketing rule.
- **Tracking:** before any photo exists, an anchorless provisional map is replaced after 4 consecutive sharp, printed
  frames it fails to localize, not only by a new full overview (extends D32).
- Synthetic tests cover a soft shadow on a light table, a receipt on a red board, four pastel papers, and the
  sideways-overview-then-close sequence; geometry fixtures now carry print. Benchmark: 107 of 1,476 illegible bands
  accepted, none severe (< 50% characters right); 129 of 1,532 legible rejected.

**Limits:** thresholds come from one afternoon's frames under one light. A receipt mostly in shadow, or blank paper
with little print in view (a long bottom margin), may not be located. Saturation uses the camera's white balance.

## D40. Keep the receipt's axis, replace an anchored provisional map, and tolerate a shadowed end (2026-10-15)

The second live capture with D39 found three more faults.

- **Axis swap.** A map started on a close strip classified sideways (`turn=90`, `INITIAL_STRIP`) later registered
  upright frames by content with a ~90° rotation. The receipt's length was measured along the map's width: the whole
  receipt fit in ~20 units and the capture "completed" (top and bottom 11 units apart). Content registration now
  rejects transforms that turn the receipt's long axis sideways; a 180° flip (axisReversed) is still allowed. The
  synthetic sequence written for it doesn't reproduce the live registration (it passes without the guard), so the
  guard has no failing-first test.
- **Anchored dead-end.** A provisional map with one anchor, from a frame later rejected by OCR, blocked full upright
  views: D39's replacement required no anchors. Now, before any photo or candidate exists, a map is replaced (anchors
  cleared) after 4 consecutive sharp, printed frames it can't localize, anchored or not.
- **Shadowed end.** The user reported the end of the receipt under the phone's shadow was left out. The light
  fraction for paper drops from 70% to 50% (shadow-only regions read 0.00 on real frames). Region proposals add an
  Otsu threshold on illumination-normalized luminance (σ40 division), which keeps a shadowed end with the rest. To
  keep a dark board from winning at 50%, a paper-like region well inside another (>= 90% of its outline >= 6 px
  inside) is preferred; a region sharing the outer boundary is the same paper. The synthetic "shadowed end on a red
  board" test passes with or without the new proposals, so the real frame still has to confirm it.

Real frames (CaptureEvidenceEvalTest): unchanged verdicts, shadow rejected, receipt inside the red board located.
46 unit, 46 instrumented (benchmark unchanged: 107 of 1,476 illegible accepted, none severe) and lint pass.

D40 validation on real frames: the four photos of the red-board capture (17:38), whose capture-time masks left out
the part under the phone's shadow, are located as the whole receipt by the D40 detection, shadowed end and QR
included, board excluded.

## D41. Trim background leaks from the paper mask; close the capture front for now (2026-10-15)

On a patterned red board the contour followed light filaments of the print where they touched the receipt. The
selected region is now opened with a 15 px ellipse (at 720 px scale), original pixels within 15 px of the opened
shape are restored (a plain opening rounded the corners and broke the overview→top-zoom confirmation test), and the
result is reduced to its largest piece before use.
On the real frames of that capture the leak shrinks but doesn't disappear (one frame keeps a blotch at a corner);
the synthetic filament test passes with or without the trim. 46 unit and 47 instrumented tests and lint pass. A frame with a strong shadow across the receipt
still stops the mask at the shadow.

The user judged capture good enough to move on, and so do the measurements: the last two live captures completed in
3 and 10 s with a consistent receipt length (530–575 units) and OCR rejecting blur in 250–470 ms. Remaining capture
work is recorded under Known capture limits in status.md. **Long receipts that need several overlapping photos
(stitching/registration across segments) were never tested on the phone**; every live capture so far was a short
receipt that fits one or two frames. Next: roadmap stage 1 (data model, append-only evidence upload).

## D42. Photos are append-only evidence in their own collection (2026-10-15)

First slice of roadmap stage 1, chosen because of the loss paths confirmed in D36: the upload replaced the server's
photo list (deleting what the phone didn't send), and capture masks and segment metadata existed only on the phone.

- **`receipt_photos`** (PocketBase): one record per photo with `receipt`, `local_name` (unique per receipt),
  `session_id`, `image` (JPEG), `mask` (PNG, optional), `segment` (capture JSON: polygon, bands, ends, score) and
  `captured_at`. Authenticated users can create and read; nobody can update or delete through the API. The legacy
  `receipts.photos` field is no longer written by the app; a PATCH without it keeps its files.
- **Deleting a receipt deletes its photos** (cascade), as the user chose. Today only a superuser can delete, in the
  PocketBase admin, whose confirmation doesn't mention the photos. **Requirement for the app's future delete
  (stage 4): warn how many photos will go with the receipt and require an explicit confirmation.**
- **App:** `Receipt.uploadedPhotos` records photos the server confirmed. The upload updates the receipt without
  photos, then adds each pending photo to `receipt_photos` (mask and segment when it has them), confirming them one
  by one so an interrupted run resumes. It first looks the photo up and treats a unique-index rejection as already
  uploaded, so retries never duplicate.
- **Existing photos are uploaded once more** into `receipt_photos`, now with masks and metadata, as the user chose.
  The ~55 legacy copies stay in `receipts.photos` until a retention policy removes duplicates.
- **Fresh databases:** the bootstrap hook skips taxonomy sync and re-parse when the collections don't exist yet (the
  JSVM runs onBootstrap before migrations and has no post-migration hook); the next start syncs. A restore onto a new
  machine therefore needs one restart after the first start.
- **Verified:** client request shapes with a fake server (no `photos` field ever; mask/segment sent; existing photo not
  re-sent; lost-response retry resolves to the existing record). On an isolated PocketBase with the real migrations and
  hooks: legacy photos kept across a PATCH (2 → 2), duplicate create 400, user update/delete 403, user receipt delete
  403, superuser receipt delete 204 with its photos gone. Live server: backup `pre-receipt-photos-2026-10-15.zip`,
  migration applied, receipts/items/products/categories/spending_lines/legacy photo counts unchanged (9/135/84/75/135/55).
- **Phone, live server:** the first upload added all 51 local photos in ~35 s over home Wi-Fi: 30 capture photos,
  each with mask and segment, and 21 older photos. Legacy `receipts.photos` stayed at 55; nothing is pending on the
  phone; a relaunch uploaded nothing more (still 51). No emulator end-to-end run.

## D43. Stage 1 data model: phone is the source of truth, PC read-only, reports use the current category (2026-10-17)

Second slice of roadmap stage 1. The user decided two questions while planning it:

- **The PC only reads.** The phone owns the structured data and sends the server a one-way copy (notes, lines with
  their effective product/discount, catalog) for the dashboard and as a backup. The server never edits, so there
  are no sync conflicts. After the cutover PocketBase stops parsing and the admin edit rules close. Two-way sync and
  editing from the PC are out of scope and could become their own stage.
- **Reports use each product's current category.** Recategorizing corrects history, as the dashboard already does.
  Lines don't store the category of their time.

Measured in the same slice:

- **Room works in this build.** AGP 9.0.1 with built-in Kotlin 2.3.20, Room 2.8.5 and KSP 2.3.12: `assembleDebug`
  passes and an instrumented write/read test passes on the emulator. The APK grew 16 KB, because Room's runtime
  already came in through WorkManager. This was a throwaway branch; nothing was merged.
- **Read-only checks on the live server** (counts only; script kept outside the repo):
  - quantities have up to 3 decimals and unit prices up to 2;
  - `qty × unit_price` matches the line total on all 135 lines;
  - re-parsing every stored page gives the same (line, code) on all 9 receipts;
  - 4 receipts repeat a code across lines, so lines are matched by line number, not by code;
  - 97 codes are GTINs and 38 store codes, none empty;
  - manual work to carry over: 82 categorized products (33 categories), 4 tagged and 3 manual sizes;
  - the server has 9 receipts and the phone 8.

[data-model.md](data-model.md) holds the proposed schema, flows, re-parse rule, deletion policy, taxonomy seeding and
migration plan. Its choices are not approved yet. They get their own entry after the user reviews the document.

**Correction after review (2026-10-17):** the first draft of data-model.md let one product hold several codes and
offered merging products (e.g. the same bulk item at two stores). The user rejected it: merging similar items, such as
different wines into one "wine", is not identity. Products stay **one code, one product**, as on the server today: a
GTIN, or a store code together with the store's CNPJ. Nothing is merged. Grouping similar products is the category's
job for spending totals, and a future price-comparison concern (IPCI). A product with purchases can only be archived.
If a store turns out to change an item's internal code, that gets its own decision.

**Second correction (2026-10-17):** the user pointed out that banana caturra is banana caturra at any market, so the
same item sold at several stores needs one unifying identity. That differs from merging different wines into "wine".
data-model.md now has two levels:

- **Store item** (`store_item`): one per code as the receipt shows it (GTIN, or store code with the store's CNPJ),
  created automatically. Each line points to its store item.
- **Product**: what the thing is, with name, category, size and tags. Each store item points to a product. A new code
  gets a product of its own, and the user can link a store item to an existing product.

Linking or unlinking never changes a purchase, because lines point to store items. Rule: link only the same item
(same variety, same way of selling), and warn when units are incompatible. Different products stay separate and are
grouped by category. A single line can still be corrected to another product (`product_override_id`). This
supersedes the "one code, one product" rule of the first correction.

## D44. data-model.md approved: the local schema and policies for stage 2 (2026-10-17)

After two corrections (recorded under D43), the user approved [data-model.md](data-model.md) and moved on to stage 2:

- **Evidence is immutable.** Extracted values are never edited. Corrections live in the catalog (product name,
  category, size, tags) or as explicit per-line fields (`product_override_id`, manual discount with source and
  review state).
- **Store item and product.** A store item is one per code, as the receipt shows it. A product is what the thing is.
  Linking store items to a product unifies the same item across stores without touching purchases.
- **Re-parse.** Lines are matched by line number. A line with manual data that no longer matches becomes an orphan
  for review and is never dropped.
- **Deletion.**
  - A receipt goes with its lines, photos and page, after a warning and a confirmation.
  - A product with purchases can only be archived.
  - Deleting a category asks where its products go, and leaves a tombstone so the taxonomy doesn't recreate it.
- **Taxonomy.** It is a seed that only adds.
- **Server.** It receives a one-way copy, and `spending_lines` keeps its shape.
- **Migration.** Carry over the server's manual work, and verify counts, categories and monthly totals before
  switching the upload.

## D45. Stage 2a: receipts and photo evidence move into a Room database (2026-10-17)

First slice of stage 2. Correction to data-model.md's migration plan: in the roadmap, parsing on the phone is stage
3. During stage 2 the server keeps parsing and remains where products are categorized. The phone gains the database
and a queryable copy; the phone becomes the source of truth in stages 3–4.

- **Database.** `getnf.db` (Room 2.8.5, KSP 2.3.12) has three tables:
  - `receipt`: the old manifest's receipt fields;
  - `receipt_photo`: name, order, uploaded flag, and the capture segment JSON (which holds the mask name);
  - `capture_session`: the sessions already imported, so importing the same capture again is a no-op.

  Photos and pages stay files in `receipts/<chave>/`. Exported schemas are committed in `android/app/schemas/`, so
  later versions must ship migrations.
- **Same API.** `ReceiptStore` keeps its API and the `Receipt` shape, so capture, home, page and upload are
  unchanged. Every change is one transaction. `importCapture` still copies files first and commits in the database
  last. Receipt rows are updated, never REPLACEd: a REPLACE deletes the row first, and the cascade would delete its
  photos.
- **One-time import.** On load, each `receipt.json` is imported with its versions and upload state, so nothing is
  re-sent, and then renamed to `receipt.json.migrated`, never deleted. A manifest whose receipt already exists is
  only renamed, so the database always wins. An unreadable manifest is skipped and logged.
- **Limitation:** an APK older than D45 sees no receipts. Recovery is the backup taken before installing.
- **Tests.**
  - Local JVM tests run Room on a bundled SQLite (`sqlite-bundled-jvm`, explicit journal mode).
  - Four new tests cover the import (fields and upload state preserved, idempotent, database wins, corrupt manifest
    skipped) and changes after the import.
  - 55 unit and 47 instrumented tests pass.
  - On the emulator, a planted legacy manifest showed up in the real app as uploaded, with no upload attempted.
- **Phone (2026-10-17).** The app data was backed up first, to `~/getnf-backups/phone-pre-room-2026-10-17.tar`
  (103 files, matching the phone). The APK was installed over the old one, keeping data. On first launch:
  - all 8 manifests were imported and renamed;
  - the database holds 8 receipts with no pending version, 51 photos (all uploaded, 30 with segments), 8 capture
    sessions and no upload errors;
  - no upload ran;
  - the server stayed at 9 receipts and 51 `receipt_photos`, with no receipt updated.

## D46. Stage 2b: a read-only copy of the server's lines and catalog on the phone (2026-10-17)

- **Schema v2** (Room auto-migration 1→2, tested with `MigrationTestHelper`): adds `issuer`, `category`, `tag`,
  `product`, `store_item`, `product_tag` and `receipt_line` as in data-model.md.
  - **Deviation from the doc:** the parsed receipt header (issuer, issue time, totals in cents, parser version, parse
    error) lives in its own `receipt_parsed` table instead of `receipt`. ReceiptStore rewrites whole `receipt` rows, and
    a separate table keeps those writes from ever clearing parsed fields.
- **ServerMirror** makes the phone's lines and catalog match a server snapshot, in one immediate transaction (a
  failure changes nothing).
  - Local ids stay stable: categories match by path, tags by slug, store items by code key, lines by (chave, line).
  - Each server product becomes one store item and one product. A manual size goes to the product; a parsed size goes
    to the store item.
  - Whatever the server no longer has is dropped from the copy. That is safe only while nothing is edited on the phone
    (stage 4 changes this).
  - It uses `useWriterConnection { immediateTransaction }`, not `withTransaction`, which takes the framework path and
    fails under the bundled JVM driver.
- **Refresh.** `PocketBaseClient.fetchSnapshot` reads categories, tags, products, receipts (without the heavy
  `parsed` JSON), receipt_items, and the receipt_photos count per receipt, paginated. UploadWorker refreshes the copy
  after its uploads, with the same login. A refresh failure is only logged.
- **Startup.** The app now enqueues the worker on every start, except under instrumented tests (debug builds point at
  the real server).
- **Fewer PATCHes.** A receipt whose record is current (remote id, uploaded version) no longer gets a PATCH when only
  photos are pending, so its page isn't re-sent and re-parsed.
- **Receipts only the server has** are adopted by `ReceiptStore.adoptRemote` as already uploaded, with their page
  downloaded.
  - Their legacy `receipts.photos` are downloaded and queued for receipt_photos **only when the server receipt has no
    receipt_photos yet**. Otherwise a fresh install would re-send the same evidence under the server's file names and
    duplicate it.
  - An adopted receipt whose evidence is already in receipt_photos gets no local photos for now. Restoring evidence is
    stage 2c's job.
- **Tests.** 63 unit tests (ServerMirror: mapping, idempotence, removals, rollback, adoption, store-local monthly
  totals; client: pagination, field conversion, file token, no partial download) and 48 instrumented tests pass.
- **Phone (2026-10-17).** Backup first: `~/getnf-backups/phone-pre-mirror-2026-10-17.tar` (108 files, matching the
  phone). After installing over the old APK, the first run:
  - adopted the server-only receipt with its 4 legacy photos;
  - mirrored 84 products, 9 receipts and 135 lines;
  - uploaded those 4 photos.

  Compared with the live server, using counts only:
  - 9/135/84 receipts/lines/products on both;
  - 0 products with a different category;
  - all 61 month×category cells of `spending_lines` equal (192,775 cents in total on both sides);
  - 0 photos pending on the phone;
  - `receipt_photos` at 55, with all 9 receipts now covered;
  - no receipt record updated on the server.

  A relaunch refreshed the copy and uploaded nothing more.

## D47. Stage 2c: export and restore through a user-picked file, swapped in on restart (2026-10-17, fixes 2026-10-20)

- **What a backup holds.** One zip, written to a file the user picks through the system picker (Storage Access
  Framework, so no storage permission):
  - `db/getnf.db`: a copy taken while holding Room's writer connection, after a `wal_checkpoint(TRUNCATE)`, so
    nothing can be written between the checkpoint, the copy and the file list;
  - `receipts/<chave>/<file>`: every page, photo and mask the database refers to;
  - `manifest.json`: format, app and schema version, counts (receipts, photos, lines, products) and a SHA-256 per file.

  Server credentials, the lookup budget and open capture sessions are left out.
- **Restore.** Settings copies the picked file into the cache and checks every hash, then shows the backup's counts
  next to the phone's. Nothing changes until the user taps Replace. A backup from a newer schema is refused; an older
  one is accepted, and Room migrates it when it opens. Archive paths are limited to the database and files directly
  inside a 44-digit chave directory.
- **Swap on restart.** Replace extracts into `restore/staged`, verifying hashes again, and writes `restore/READY`.
  `GetNFApp.onCreate` swaps it in before anything opens the database:
  - the current database and receipts move to `restore/previous`, and stay there until the restored database has
    opened;
  - every step is skipped when already done, so a start interrupted halfway finishes the swap on the next one;
  - a failed swap is logged and retried on the next start instead of crash-looping.
- **Fixes after the first emulator run (2026-10-20):**
  - **The app didn't come back.** The restart used an alarm with a PendingIntent. Android 14+ blocks that
    ("Background activity launch blocked") because the app's process is dead when the alarm fires. The restore was
    applied only once the user opened the app by hand. Now `RestartActivity` runs in its own `:restart` process
    (the ProcessPhoenix pattern) and is started while the app is still in the foreground. It kills the old process
    and opens the app again. `GetNFApp.onCreate` does nothing in that process.
  - **Fresh install, interrupted swap.** With no database to move aside, `previous` held only a `getnf.db.none`
    marker, but the "already moved aside" check looked only for `getnf.db`. A start interrupted after the restored
    database was in place would then have moved it into `previous`, leaving an empty app.
  - **Leftovers from a failed start.** If a swap failed after the originals were moved aside, that start could create
    an empty database or receipt directory at the live paths, and every retry would then fail. Once `previous` holds
    the originals, whatever is at a live path with a staged copy still present is deleted before the move.
- **Tests.** 71 unit tests (archive round trip, corrupt/missing/newer/foreign archives, unsafe paths, swap, cleanup,
  interrupted swaps including on a fresh install, leftovers from a failed start). The instrumented `BackupServiceTest`
  (export, wipe, restore and compare every table and file) passes on the emulator.
- **Emulator (2026-10-20).** Seeded with 2 synthetic receipts (fake chaves, 6 random photos, 2 pages). Exported from
  Settings, uninstalled the app, reinstalled, restored. The app restarted on its own (new pid, "restored a backup"
  logged). All 8 files had identical SHA-256s, and the `receipt` and `receipt_photo` rows were identical.
- **Phone (2026-10-20).** Backup first: `~/getnf-backups/phone-pre-restore-2026-10-20.tar` (113 files, matching the
  phone). After installing over the D46 APK:
  - **Export** from Settings: 9 receipts, 55 photos, 135 lines, 84 products; 127 MB, 95 files, every hash correct
    (copy in `~/getnf-backups/phone-export-2026-10-20.zip`). Every table of the exported database matched the
    pre-install copy row for row. The 94 photos, masks and pages were byte-identical.
  - The export leaves out the 8 `receipt.json.migrated` files. D45 imported them and nothing reads them, so a restore
    drops them. They remain in the tar.
  - **Restore** of that file onto the same phone: the dialog showed 9/55 on both sides. After Replace, the app came
    back on its own in a new process and logged "restored a backup". The startup refresh mirrored 84/9/135 and
    uploaded nothing. Afterwards every table and all 94 files were identical to the export.
  - Stage 2 is complete: the data can be queried on the phone, the migration was checked, and a restore was verified.

## D48. Stage 3a: the server's parser and derivation, ported to Kotlin and held to the JS by golden files (2026-10-20)

First slice of stage 3 (parsing on the phone). Nothing in the app calls the port yet. It must first give the same
answers as the server.

- **Port.** `parse/NfcePage.kt` is `nfce_page.js` and `parse/Derive.kt` is the parts of `derive.js` the phone needs:
  GTIN check, code key, size from the description, discount allocation and line discounts. The taxonomy file
  parsers stay on the server until the phone owns the catalog (stage 4).
  - It reads the page's decimal text exactly, straight into the database's units: Long cents, quantities and unit
    prices at scale 4, sizes in millionths. The JS goes through floats and rounds, and gives the same numbers for
    every page seen so far.
  - Allocation ranks the leftover cents by exact integer remainder instead of a float fraction. Ties still go to the
    earlier line.
  - `NfcePage.VERSION` equals the JS `VERSION` (1). Both must change together.
  - Known size gaps ("1K", "1 25KG", status.md) are ported unchanged, so the two sides keep agreeing. They get fixed in
    both at once.
- **Parity.** `tools/nfce-golden.mjs` runs the JS on every fixture in `server/test/fixtures/` and writes golden JSON
  in the phone's units to `android/app/src/test/resources/nfce-golden/`. `NfceParityTest` compares the two field by
  field: header, items, code key, size, prorated discount, totals, payments, taxes and warning text. The fixtures stay
  in the server's directory; the Android tests read them from there.
  - **Real pages, kept out of git:** `node tools/nfce-golden.mjs <dir>` writes a golden file next to each page, and
    `GETNF_PARITY_DIR=<dir>` adds them to the test.
  - **Run on the device too:** Android's java.util.regex is ICU-based, not the JVM's. `NfceParityDeviceTest` runs the
    same comparison (helper in `src/sharedTest/`) on the emulator, and its `parityDir` argument takes real pages
    copied into the app's files.
  - **Result:** all 10 real pages available (the 9 phone receipts from the D47 export, plus the one in
    `test-media/pages/`; 155 items) match field for field on the JVM and on the emulator. A golden value off by one
    cent fails the test. The Turnstile challenge page is rejected by both.
- **Next (3b).** Parse on the phone when a page is saved, and for saved pages whose local parse is missing or older
  than `VERSION`. The phone's parse becomes the source of `receipt_parsed`, `receipt_line`, `issuer` and store item
  wording and parsed sizes. Until stage 4 makes the phone the editor, the server still supplies the catalog side
  (categories, tags, manual sizes, which product a store item belongs to), matched by code key. A store item the
  server doesn't know yet gets its own uncategorized product. A page that fails to parse keeps its receipt and shows
  the error. Before the switch, the phone's lines are compared with the mirrored ones for the real receipts.

## D49. Stage 3b: the phone parses its own pages; the server still supplies the catalog (2026-10-20)

Carries out the plan at the end of D48. A saved page becomes items, totals and store items on the phone, with no
server involved. Categorizing stays on the server until stage 4.

- **`ReceiptParser`** writes `receipt_parsed`, `receipt_line`, `issuer` and store items from the saved page, in one
  immediate transaction, with ingest.js's rules:
  - **Store items:** a code already known as a store item keeps its product, and so its category. Its wording and
    parsed size are updated. An unknown code gets a new uncategorized product.
  - **Discounts:** a line's discount from a photo or set by hand survives a re-parse, matched by line number, together
    with its review state and any product correction. The rest of the receipt's discount is prorated over the other
    lines.
  - **Header:** paid is "Valor a pagar", or else gross minus discount. Warnings are stored in the server's wording
    ("warnings: …").
  - **Unreadable page** (or a page whose chave is another receipt's): the receipt and its earlier header and lines
    stay, and the error is recorded. It's retried only when the page is fetched again or the parser changes.
- **When it runs:** when a page is saved, at startup for every receipt that needs it, and after the upload worker
  adopts receipts that only the server had. A receipt needs it when the phone hasn't parsed it yet, parsed it with
  another `VERSION`, or fetched the page again since (`pageFetchedAt` changed). One parse runs at a time.
- **Schema v3** (auto-migration 2→3, tested): `receipt_parsed.source` (`server` by default, `phone`) and
  `receipt_parsed.pageFetchedAt`. Rows from before D49 count as the server's, so the first start of this version
  parses every saved page on the phone.
- **ServerMirror now:**
  - skips the header and lines of receipts the phone parsed, also when the server doesn't have them;
  - never deletes a store item that a line uses, even if the server doesn't know it yet;
  - still sets a known store item's product category, tags and manual size, matched by code;
  - no longer clears a store item's parsed size just because the server holds a manual one.
- **List screen:** each receipt shows its item count and amount paid, a warning in red, or "Couldn't read the page"
  with the error.
- **Real data, before switching** (`ExportReparseTest`, local only: `GETNF_EXPORT_DIR` = the unzipped D47 export):
  - a copy of the phone's database, reparsed with ReceiptParser, gave the server's 135 lines for all 9 receipts,
    field for field, including store item and discount;
  - same 9 headers;
  - same 84 store items and products, no new product;
  - all 61 month×category totals unchanged.

  A one-cent change in a server line makes it fail.
- **Tests.** 93 unit tests (ReceiptParser: header, lines, store items, uncategorized new codes, known codes keeping
  their category, photo/manual discounts across a re-fetch, unreadable and foreign pages, warnings, when to re-parse,
  and the mirror leaving phone parses alone while still categorizing). The 2→3 migration test passes on the emulator.
  On the emulator app, a receipt with the fixture page showed "8 item(s) · R$ 30,64", and the two with dummy pages
  showed the error.
- **First phone run (2026-10-20), and three fixes.** Backup first: `~/getnf-backups/phone-pre-local-parse-2026-10-20.tar`
  (105 files, matching the phone). The first start of the D49 build parsed all 9 pages, with no warnings. Compared with
  the backup: receipts, photos, lines (135), products, categories, tags and issuers were identical. That showed:
  - **Each page parsed twice:** startup and the upload worker both ran `parsePending` and each checked `needsParse`
    before taking the lock. The check now happens under the lock.
  - **A cancellation recorded as a parse error:** WorkManager replaced the running worker (REPLACE, by design), and
    the `CancellationException` from the IO dispatcher was caught as a failure. One receipt got "Job was cancelled"
    as its parse error and wasn't going to be retried. Cancellation now propagates and writes nothing; a test cancels
    a parse while the page is being read.
  - **A size removed by hand came back:** the server keeps "manual, no quantity" for a product whose size was removed
    on purpose (a suitcase described as "10KG"). The mirror turned that into "no manual size", so the phone's reading
    of the description (10 kg on the store item) would have counted. The mirror now keeps `sizeSource = manual` with
    no size on the product, which overrides the store item's parsed size. A hand-set 1.25 kg was already right.
- **Phone with the fixes (2026-10-21).** The stuck receipt's parse marker was cleared once by hand (the app stopped,
  `receipt_parsed.pageFetchedAt` set to NULL), and the fixed build installed over the first one. Compared with the
  pre-install backup:
  - all 9 receipts parsed on the phone, none with an error;
  - the 9 headers, the 135 lines, receipts, photos, categories, tags and issuers are identical;
  - the only differences are intended: 2 store items now hold the description's size (10 kg, 25 kg), and the
    suitcase's product is `manual` with no size. The 25 kg item's product has its hand-set 1.25 kg.

  A second start parsed nothing, and its server refresh left every table identical. The list shows items and amount
  paid per receipt.

## D50. Stage 3c: a receipt detail screen, and reading a saved page again on demand (2026-10-21)

- **Navigation.** Tapping a receipt that has a saved page opens `ReceiptDetail(chave)`. A receipt without a page
  still opens the SEFAZ page, through the lookup budget check, as before.
- **The screen** shows:
  - the store name in the title;
  - issue date, chave, items total, discount and amount paid;
  - who read it (this phone or the server), and any warning or error;
  - every line with quantity × unit price, its discount and the discount's source, and its category as
    "Parent › Category" ("Uncategorized" when the server hasn't categorized the product yet).

  A line's own product correction wins over its store item's product, as in `monthlyTotals`. Two Room flows feed it
  (`CatalogDao.header`, `lineDetails`), so a parse or a server refresh shows up at once.
- **"Read page again"** re-parses the saved page with ReceiptParser and says what came of it. It never contacts
  SEFAZ; fetching the page again stays a separate, budgeted action. Useful after a parser change, or to retry an
  error.
- **Formats** (`ui/Format`): reais and scale-4 quantities in pt-BR ("R$ 30,64", "0,575 KG").
- **Checked** on the emulator (fixture receipt; a dummy page showing its error and 0 lines; Read again) and on the
  phone with a real receipt: 15 lines with the server's categories, the totals matching the list.
- **Tests.** 99 unit tests (queries: store name, line order, category and parent labels, a line's correction
  winning; formats).

## D51. Interpreted fields are suggestions with a source and a review state; models propose, the user confirms (2026-10-21)

Recorded after a design conversation, not code. No implementation changes with this entry.

- **The split.**
  - **Deterministic, stays in code:** reading the structured SEFAZ HTML, totals, discount prorating, GTIN check
    digits. These are exact and proven by the parity tests (D48).
  - **Interpretive, needs intelligence plus the user's confirmation:**
    - pack size: "10KG" on a suitcase is capacity, "1 25KG" means 1.25 kg, "1K" means 1 kg;
    - product identity: one item under different codes or stores, abbreviations such as "QJO MUSS";
    - category;
    - grouping products for price comparison (the IPCI, stage 7).

  Regexes and switch cases can't settle these. They can only guess.
- **Every interpreted field is a suggestion with a source (regex, LLM, user) and a review state**, the way
  `discountSource` / `discountReview` already work for discounts. Today's parsed size is really an unmarked regex guess;
  the size source on store items and products should say so. Only a value the user confirmed or entered is final.
- **The regex stays as a free first pass.** A model proposes the unclear or new cases. The user confirms or corrects
  them on a review list on the phone.
- **Confirmations are remembered per store item or product**, so a code is asked about once. A later reparse or model
  run never overwrites a confirmed value (the same rule as D49's "manual, no size" marker).
- **Where it lands.**
  - **Stage 4** (manual categorization on the phone) builds the review screen, the source/review-state fields and the
    "ask once" memory, with regex as the only suggester. The manual flow must be complete first.
  - **Stage 8** plugs models in as further suggestion sources: local first, then APIs. It adds no new storage or screens.
  - Status open item 6 (LLM category suggestions) and item 7 (size gaps such as "1K") are the same thread.
- **Still open:** what the objectives call "system one" and "laya" locally. They were kept as the user wrote them;
  **ask the user what they refer to before choosing any tool** (D24 era notes, objectives.md §4).

## D52. The paper mask stops at the receipt's straight sides (2026-10-21)

- **Seen live:** a receipt on a dark chair, with a lit patch of the cloth beside it. The overlay and the analyzed area
  spread over a large part of the background to the left of and below the receipt. Coverage never completed, and the
  app kept asking to go back to the last recognized segment.
- **Cause:** on one of that capture's saved frames (re-run with `CaptureEvidenceEvalTest`), the plain Otsu region
  itself held the patch: it's as light as paper in shade. No receipt-only region was proposed, so choosing among
  proposals (D40) couldn't help. `trimLeaks` (D41) only removes strands thinner than ~15 px.
- **Fix:** `PaperRegion.straightenSides`, after `trimLeaks`. Along the receipt's axis, each row's outermost mask pixel
  on each side is collected, and a line is fitted to each side:
  - **The fit:** RANSAC over sampled row pairs, with inliers within 2 px, then least squares over the inliers. A
    candidate steeper than 0.35 (across the axis) is refused, since that would be the bulge's own outline.
  - **The cut:** whatever reaches more than 3% of the receipt's width past a side's line is cut.
  - **Left alone:** a side is fitted only when 30% of the rows lie on one line. Rows where the mask reaches the
    image border don't count, since the edge isn't seen there. So an irregular outline with no straight stretch
    stays as it was.
- **Proposal logging:** `DEBUG_PAPER` now also logs every proposal's source (otsu, otsu-inverted, canny, flattened),
  area and score, the chosen one, and what straightening cut.
- **Checked:**
  - **The saved frame:** its bump of background on the left side is gone, with 9,981 px cut at analysis scale. The
    other four frames of the capture are unchanged.
  - **New synthetic test:** a lit irregular patch merged with the lower half of a receipt's side. Without the fix it
    put ~40% of the paper's area of background into the mask; with it, what remains is the tolerance strip (~2%).
  - **All 48 vision/capture instrumented tests** pass on the emulator.
- **Not yet checked live:** the original failure (the live frames aren't saved). Per-frame cost not measured; it's two
  line fits over at most ~720 rows.
- **Known, separate:** a white receipt on a white table still gives a fragmented mask (frame 0 of the same capture).
  That's low contrast, not a leak.

## D53. A frame that fills the last coverage gap is always worth saving (2026-10-24)

- **Seen live (screen recording):** the same receipt on the dark chair. With D52 the outline now follows the receipt.
  One overview photo was saved, and Topo, Fundo and QR were ticked, but Cobertura never was. For 40 s of scanning
  close up the app saved nothing more and kept asking to go toward the top.
- **Cause:** `CapturePolicy.useful` only accepted a frame if at least 35% of its bands were new. Once the overview had
  both ends but missed a sliver between them (a band too blurred or glared to count), every close-up was mostly
  coverage already saved, so the sliver could never be filled. The phone's 256 KiB log buffer had already rotated, so
  the size of the gap isn't known; the rule's dead end is shown by a unit test.
- **Fix:** while top and bottom are saved but not connected, a frame is useful if it adds any coverage (more than half
  a unit) between them. A close-up of only what's already saved still isn't.
- **Diagnostics:** for the next live try, logcat is streamed to the PC during the capture instead of read afterwards.

## D54. Stage 3d: per-item discounts read from the photos, on the phone (2026-10-21)

*Written and committed as "D51" (7fd9e87, 2e3e8b9) on a branch while another session recorded the D51 above on
main. Renumbered at the merge (2026-10-24); those two commit messages still say D51.*

The page gives only the receipt's total discount, so lines got a prorated share of it (D14). The printed receipt says
which items had a discount. On a real receipt, prorating had given the R$ 29,98 lombo R$ 0,79 of discount it never
had.

- **OCR:** `PhotoOcr` runs ML Kit's bundled Latin recognizer, already in the app for legibility (D37), on the saved
  photo with its EXIF orientation applied. It keeps each line's text and box. Results are cached next to the photo
  (`<photo>.ocr1.json`, not part of a backup; it's rebuilt when needed).
- **Reading** (`discounts/PhotoDiscounts`, pure, JVM-tested). The HTML says what the items are, so OCR is only asked
  where each item's discount is:
  - **Anchors:** an item is found by the HTML's code among a line's first tokens, with OCR's letter-for-digit
    confusions undone. The printed item number is too often misread ("J1" for 11) to anchor on. A short in-store
    code also needs the description's first word on the same line.
  - **Same product rung up more than once:** the matching lines pair with the items in print order when the counts
    agree; otherwise a readable item number decides.
  - **`Desconto: 1,56` lines** (the supermarket's layout) go to the nearest item-looking line above, only if that line
    is a found item and the gap is within 2.2 line heights. A discount under an item OCR couldn't place stays
    unassigned rather than landing on the item above. The totals block's "Desconto R$ …" is never taken.
  - **`De R$ 256,51 por R$ 166,73`** (the pharmacy's layout): the "from" price is the line's gross, which names the
    item when it's unique on the receipt; the discount is the difference.
  - **Several photos:** readings of the same item from overlapping photos count once. When they differ, the value read
    at least twice as often as any other wins; otherwise it's a conflict.
  - **Verdict:** `COMPLETE` when the readings add up to the page's discount, `PARTIAL` when less, `NONE`, or
    `CONFLICT` (readings exceed the total, a discount exceeds its line, or photos disagree), in which case nothing is
    applied.
- **Applying** (`PhotoDiscountReader`): found discounts become `photo` (unreviewed), and the rest of the total is
  prorated over the other lines. A `manual` discount is never replaced. ReceiptParser keeps photo and manual
  discounts across re-parses (D49). It runs on demand from the receipt screen ("Discounts from photos"), as D14
  intended, shown only when the receipt has a discount and photos.
- **Real photos** (OCR recorded on the emulator from the D47 export, `PhotoDiscountsEvalTest`, local only):
  - **Complete** on 5 of the 6 receipts with a discount: four supermarket receipts (R$ 4,40 as 4 discounts, R$ 2,80
    as 5, R$ 1,50 as 2 on the same product rung up twice, R$ 0,56 as 2) and the pharmacy (R$ 133,49, agreed by 23 and
    24 of its 32 photos despite misread digits in others).
  - **Partial** on the sixth, an older low-resolution photo of five identical items: R$ 0,10 of R$ 0,50 found.
  - **Nothing** on the three receipts without a discount. That includes one whose NF holds three photos of a
    different receipt, with `Desconto` lines: no code matched.
- **OCR time:** about 2.7 s per 3072×4096 photo on the emulator.
- **Phone (2026-10-21).** Backup first: `~/getnf-backups/phone-pre-photo-discounts-2026-10-21.tar` (105 files,
  matching the phone). On the R$ 4,40 receipt, "Discounts from photos" said "From 2 photo(s): 4 discount(s), R$ 4,40
  of R$ 4,40. All found." in under 5 s. Lines 10, 11, 14 and 15 got 156, 204, 24 and 56 cents (`photo`), as printed;
  every other line 0, which includes the lombo that prorating had given 79 cents. No other receipt changed.
- **Not done:** showing the evidence crop so the user can confirm (the review state stays `unreviewed`), and a way to
  go back to prorated (needs stage 4's line editing). The server still prorates: its dashboard shows prorated
  discounts until stage 4's one-way copy sends the phone's lines.
- **Tests:** 115 unit tests (reading: assignment, partial/conflict, unrecognized and distant items, short codes,
  overlapping photos and majorities, the same product twice, from/to prices, another receipt's photo; applying:
  remainder prorated, kept across re-parse, cache, manual kept, conflict changes nothing).

## D55. New direction: hosted online service first, AI categorization, local-only Android after the MVP (2026-10-24)

Decided by the user while planning stage 4. It supersedes D43's "phone is the source of truth, PC read-only" and
the stage 4 plan built on it (proposed the same day, never started).

- **No database on the user's computer.** The PocketBase on the PC and its dashboard are retired. The data is
  hosted online and served from there. A web app may come later, on the same hosted data.
- **The Android app is the main client.** A fully local Android mode stays a goal, but second class, maybe after the
  MVP. What already runs on the phone (capture, QR, fetching and parsing the page, photo discounts, the Room
  database) is kept: it is the base of that mode and the app's offline cache meanwhile. Fetching pages has to stay on
  the phone anyway: SEFAZ blocks an IP after about 5 lookups per hour (docs/sefaz-sc.md).
- **Users:** the user first, then friends and a beta. Built multi-user from the start (accounts, per-user isolation),
  launched to one account.
- **Categorization by AI, with the user always able to pick by hand.** Two kinds of model:
  - **"System 1" decision models**: non-generative classifiers that answer typed choice/score/yes-no questions in
    one forward pass, with calibrated probabilities. The user named TypeSafe's **Jev** (hosted API) and **Laya**
    (open weights, Apache 2.0, installed on the user's PC). This resolves the open "system one" and "laya" question
    in main's D51 and objectives.md.
  - **An LLM** for what the decision model isn't confident about.
  - Every AI value is a suggestion with a source and a review state (main's D51). A user's choice is final and is
    never overwritten.
- **Where models run:** during development, on the user's PC (local LLM and Laya side by side). For a real launch,
  likely a third-party cloud provider of open-weight models.
- **Still open:** the hosting service (the user asked for a comparison first), and which models, to be settled by an
  evaluation on the user's real, already-categorized items.

## D56. First categorization eval: a local LLM categorizes well; Laya untrained does not (2026-10-24)

`tools/categorize-eval.py` scores models against the user's own categories: `export` pulls the 82 hand-categorized
products (33 categories, of the 75 in the taxonomy) from the local PocketBase into `test-media/categorize/`
(gitignored, real purchases). Models see only the taxonomy's labels, never its comments, which name real items.

| Model (all local) | exact category | 2nd level | top level | time per item |
|---|---|---|---|---|
| Laya, tree walk, Portuguese labels (auto-routed to multilingual) | 4% | 4% | 6% | ~25 ms |
| Laya, top level only, English labels, `english` checkpoint | | | 60% | ~25 ms |
| Swift-1.5 Qwen3.8 27B IQ2_S (ukisai, LM Studio), whole taxonomy in the prompt | **84%** | 88% | **94%** | ~3 s |

- **Laya without training isn't usable here.** Its best top-level score (49/82) is below always answering "food"
  (54/82): it answered food for 64 items. With Portuguese labels it mostly picked "taxes and fees" or "beverages"
  whatever the item. This matches its README and the independent benchmark: it is a base to train, not a zero-shot
  engine, and weak past ~20 options and outside English.
- **The LLM is good.** 0 invalid answers. Most of its 13 misses are judgment calls the taxonomy leaves open
  (sliced cured meats as cold cuts vs pork or poultry; canned tuna as fish vs canned goods; fruit pulp as juice vs
  fruit; condensed milk as baking vs milk). Only a couple are plain errors (an abbreviated brand of pasta read as
  cheese, a soap brand read as bread).
- **Its stated confidence is useless for triage:** every answer said 0.85–0.98, and the wrong ones were as sure as the
  right ones. Deciding what the user must review needs another signal (agreement between runs or models, a
  calibrated System 1 model, or a trained head).
- **So, for now:** the LLM proposes; everything stays "suggested" until the user confirms. Each confirmation is a
  labeled example. Laya becomes worth re-testing once there are enough of them to train its head (the benchmark puts
  break-even at 500–5,000 labels), or as the calibrated second opinion.
- **Open:** the taxonomy's ambiguous boundaries cost more than the model does. Notes per category ("cured meats go
  to cold cuts") would help both the user and the model; they can't come from the item examples now in the comments,
  which are the test set.
- **Setup:** GPU 16 GB. The 27B IQ2_S needs ~10.5 GB with one slot and 8k context, so the user's Jebadiah server
  (8.3 GB) was stopped for the run. LM Studio's server needs its API key (`LMSTUDIO_API_KEY`, entered by the user).

## D57. Roles: Laya assigns categories, an LLM manages them (2026-10-24)

The user's direction after D56:

- **Laya (System 1) assigns** a category to every item: local, fast, with a probability per option.
- **The LLM manages the categories.** It maintains what Laya reads (a description per category, a dictionary of
  receipt abbreviations, boundary rules) and every so often reviews a batch of Laya's decisions, to catch where fixed
  knowledge is needed: a dictionary entry, a rule, or labeled examples to train Laya on.
- **The user** confirms or corrects; their choice is final and is the best training data.

Since untrained Laya was unusable (D56), the next experiments, scored on the user's 82 items, are whether it gets
usable with: descriptions expanded through the LLM's abbreviation dictionary; LLM-written category descriptions
instead of bare labels; and a head trained on LLM-produced labels (LLM-categorized and LLM-invented receipt-style
descriptions), never on the 82 test items.

`tools/categorize-eval.py` now also reads the LM Studio key from the gitignored `server/.env.local`.

## D58. Laya trained on written practice items categorizes at 90%, with a confidence worth using (2026-10-24)

Following D57, with Claude standing in for the managing LLM during development (the user's call: the LLM reviews
Laya in deployment, Claude reviews both in development). All runs on the user's 82 hand-categorized items.

| Setup | exact | top level | per item |
|---|---|---|---|
| Laya, untrained, Portuguese (D56) | 4% | 6% | ~25 ms |
| Laya `english`, items expanded through a lexicon to English, short English category descriptions | 61% | 79% | ~25 ms |
| same, always descending to a leaf, sizes dropped | 63% | 80% | |
| **Laya `english` trained on 1,082 written items, lexicon + descriptions** | **90%** | **95%** | ~70 ms (3 levels) |
| LLM (Swift-1.5 27B IQ2_S), whole taxonomy | 84% | 94% | ~3 s |
| LLM + unit, price, store and the receipt's other items | 87% | 94% | |
| LLM + context + the 8 most similar confirmed items (never itself) + boundary notes | **95%** | 98% | ~5 s |

- **What Laya reads:** each receipt description goes through a lexicon (abbreviations, phrases and brands to plain
  English: "QJO MUSS FAT" -> "cheese mozzarella sliced"); the English checkpoint reads English far better than
  Portuguese (multilingual with Portuguese labels: 16%). Each tree level is one choice question whose options are
  short English descriptions; longer top-level descriptions hurt (49% top level), since 16 options share a ~192-token
  budget.
- **Training data:** 1,082 receipt-style descriptions over all 62 leaf categories, written by Claude without copying
  the test items (exact copies are dropped; none were). `laya-train` (RLCD, 4 epochs, shuffled options) on one row per
  tree level, 2,625 rows; a few minutes on the 5060 Ti.
- **Its confidence is a usable review signal**, unlike the LLM's (D56): median 0.81 on right answers, 0.20 on wrong
  ones. At a 0.3 threshold, 76 items are assigned with 73 right (96%), and the 6 left for review hold 5 of the 8
  errors.
- **Caveats:** the lexicon, the boundary notes and the practice items were written with the test items in view, so
  these scores are optimistic for brand-new items. The real measure comes from new receipts, scored before the user
  confirms them. The lexicon holds brands from the user's purchases, so it, the practice items and the checkpoint stay
  in `test-media/categorize/` for now.
- **So the D57 pipeline holds up:** Laya assigns, with its confidence deciding what the user reviews; the LLM (in
  development, Claude) maintains the lexicon, the descriptions and the training items, and retrains Laya as the
  user's confirmations grow.
- `tools/categorize-eval.py` gained `synth` (LLM-written items), `trainset` (Laya rows) and Laya options
  (`--lexicon`, `--descriptions`, `--lang en`, `--no-here`, `--clean`).

## D59. Most of D58's 90% is the lexicon's translation; Laya adds about 14 points and the confidence (2026-10-24)

The user asked whether the lexicon was doing the categorizing. Some D58 entries carried category words ("ham cold
cuts", "Ype cleaning", "frozen fruit pulp for juice"), so the parts were measured apart, on the same 82 items:

| Setup | exact |
|---|---|
| No model: the leaf whose description shares the most words with the translated item, D58 lexicon | 72% |
| same, translation-only lexicon (40 entries stripped of category words; brands stay bare names) | 70% |
| Laya trained on raw receipt text (no lexicon) | 48% |
| **Laya trained and run with the translation-only lexicon** | **84%** (at confidence >= 0.3: 73 assigned, 67 right) |
| Laya trained and run with the D58 lexicon (hints included) | 90% |

- **Translation is the core.** Turning abbreviations into plain words does most of the work: a word match alone
  reaches 70%, and Laya without it learns little from 1,082 examples (48%, unsure of almost everything).
- **Laya on top adds about 14 points and a usable confidence** (median 0.76 on right answers, 0.20 on wrong ones).
- **Category hints in the lexicon add about 6 points,** but they are rules, not translation. They belong in an
  explicit, reviewable place (boundary notes, or brand -> category knowledge), not hidden in a dictionary.
- **So the LLM's (in development, Claude's) main job is the lexicon:** abbreviations and phrases to plain words, and
  brands to what they make. That is the "hard-coded knowledge" of D57, and it's where most accuracy comes from.
  Coverage matters: the lexicon was written with the test items in view, so a new receipt's unknown abbreviations are
  the main risk, and finding them (tokens the lexicon doesn't know) is a cheap, precise review signal.

## D60. Open Food Facts knows 14 of the user's 58 barcodes: good names, too little coverage (2026-10-24)

The user's idea after D59: for items with a standard code, fetch a real product description instead of reading the
receipt's abbreviations. With the user's go-ahead, the 58 GTINs among the 82 test items were looked up on Open Food
Facts (`categorize-eval.py lookup`: barcodes only, 58 requests, cached in test-media).

- **Coverage: 14/58 (24%).** Not found: most dairy (11), meat (6), hygiene (4), juices, coffee, cleaning, wine.
- **Quality when found is what we want:** full Portuguese names with brand and size, which settle the cases the
  abbreviations couldn't ("PEITO FGO EXCELSIOR" -> "Peito de frango defumado", i.e. cold cuts; "BISC BAUDUCCO 140G"
  -> a chocolate wafer; "LEITE COND" -> "Leite condensado integral"). Its categories are sparse (half empty) and once
  wrong ("incorrect-product-type" on a coffee filter).
- **So:** a lookup is worth doing once per store item and caching, but Open Food Facts alone is not enough here.
  Next candidate: Cosmos (Bluesoft), built for Brazilian retail, which also returns the NCM tax classification; it
  needs a free account token from the user. The SEFAZ SC page we save carries no NCM (checked).
- Not measured yet: accuracy with looked-up names. With only 14 found, mostly items already right, it would say
  little.

## D61. Photos: read at full quality on the phone, upload small archive copies (planned, 2026-10-24)

Hosting online (D55, [hosting-options.md](hosting-options.md)) makes photo storage the main cost: ~14 MB per receipt,
~200 MB per month for one user, enough to fill Supabase's free 1 GB in ~5 months. The full resolution is needed only
to read per-item discounts (D54), and that already happens on the phone before upload.

- **Plan:** keep reading on the phone from the full-quality originals. Upload an archive copy cropped to the receipt
  with the capture mask, grayscale, WebP (Android encodes it natively; AVIF would need a library for ~2× more).
  Delete the originals on the phone once the receipt is confirmed (or after a few days). Upload photos only when they
  prove something the SEFAZ page doesn't (per-item discounts); a receipt with no discount needs none online.
- **Measured on one real 4096×3072 phone photo (5.7 MB):** grayscale JPEG q60 760 KB; half resolution 380 KB;
  receipt crop as WebP q50 290 KB (q70 390 KB) or AVIF q35 140 KB. The "Desconto" lines stayed clearly legible at
  half resolution and in AVIF. That puts a receipt under ~1 MB and one user near 10 MB per month.
- **Also found:** the current server stores 55 photos twice (old `receipts` photo field and `receipt_photos`), half of
  its 250 MB, apparently left by the migration to `receipt_photos`. Clean up when this is built.
- **When:** after the camera and ingest flow settle; not built yet. Archive quality (50 vs 70) to choose then.

## D62. Host the online service on Supabase, São Paulo region (2026-10-24)

Chosen by the user from [hosting-options.md](hosting-options.md) (D55: data online, nothing on the user's computer).

- **Why:** accounts, per-user isolation (Row Level Security), file storage, SQL and backups come managed. What we buy
  is not running a server, not a lower price.
- **Not a reason:** pgvector. Similar-item examples help the LLM (D58), but the LLM only reviews in batches (D57) and
  the worker can find similar items in memory.
- **Cost if it grows:** rough estimate, not rechecked: under ~US$100/month for 1,000 active users with D61's small
  photos; a VPS would be ~US$20–40 plus our time; Firebase is the hardest to predict. Exits if the bill grows:
  Supabase is open source and self-hostable (same Postgres), and photos alone can move to cheaper storage (R2).
- **Unchanged:** categorization runs in a separate worker (on the user's PC for now). PocketBase stays for local
  development until the move. Still open: photos online from the start or later (D61 makes them cheap), and the free
  tier vs Pro.

## D63. Supabase setup: photos online from the start, free tier for now (2026-10-24)

The two choices D62 left open, made by the user.

- **Photos go online from the start,** in D61's small form (receipt crop, grayscale, WebP; full quality read on the
  phone only). So D61 has to be built before or with the move to Supabase, not after.
- **Free tier for now.** Limits to watch: 500 MB database, 1 GB files (years of one user's D61 photos), and the
  project pauses after a week without requests. Move to Pro (US$25/month) when friends join or a limit bites.

## D64. IPCI starts from two numbers: category spending and real price change; the gap is lifestyle (2026-10-24)

The user wants both: spending per category that rises when they trade up (cheap coffee to gourmet) regardless of
prices, and the actual inflation of the products they buy. The data model already supports both without new tables:
receipt lines point to store items, store items to products, and linked store items share one product across stores.

- **Category spending:** sum of net line values per category and period. Trading up shows here by design.
- **Real price change:** per product, price per kg/l (or per unit) over time, using the product size; combined into a
  category index weighted by what the user spends on each product.
- **Lifestyle (mix) effect:** spending change minus price change. A product with no earlier price (the new gourmet
  coffee) counts entirely as mix, which is the intended reading.
- **Accuracy depends on identity:** a repackaged product with a new GTIN starts a new history unless linked to the old
  one (then price per kg also catches shrinkflation). Proposing such links is a job for the LLM review (D51, D57).
  Grouping different brands ("coffee 500 g, any brand") stays out of this, as in data-model.md.
- **Still open:** the index method (fixed basket vs chained monthly) and how to treat promotion prices. Decide when
  stage 7 (reports) is built.

## D65. Database health on Supabase: our own nightly backup, restore drills, migrations only (planned, 2026-10-24)

Today's safety net is manual: PocketBase backups before risky migrations (copied to `~/getnf-backups/`), the phone's
export/restore zip (D47), migrations in the repo, and the parser's total checks. On Supabase's free tier (D63) we
should assume no backups we can download or restore ourselves (Pro has daily ones; not rechecked).

- **Nightly backup off Supabase:** the categorization worker on the user's PC (D62) dumps the database (`pg_dump`)
  and copies new photos into `~/getnf-backups/`, keeping a few generations. It keeps doing so on Pro: a copy outside
  the provider.
- **Restore drills:** now and then, restore a backup into a local Supabase (CLI, Docker) and compare counts and
  totals with the live project. A backup is trusted only after a restore worked.
- **Schema only through migrations** in the repo (Supabase CLI), never edited by hand in the dashboard.
- **Health report from the worker:** usage against free-tier limits (500 MB database, 1 GB files), time since the last
  request (the free project pauses after a week), receipts whose lines don't add up to the total, and backup age.
- **When:** built with the move to Supabase (status item 11).

## D66. Open Food Facts can't name items without barcodes; link them to generic items instead (2026-10-24)

Of the user's 82 items, 24 have no GTIN: 16 foods (produce by kg or bunch, pão francês, beef) and 8 clothes and a
suitcase, which Open Food Facts doesn't cover. The 16 food descriptions went to Open Food Facts' text search, cleaned
(KG, MC, GRANEL removed), limited to Brazil, 16 requests; results in `test-media/categorize/off-search.json`.

- **Result: no confident match in any top 3.** Mostly other products sharing a word (onion chips for CEBOLA, banana
  candy for BANANA, grape soda for UVA, apple tea for MACA). Closest: a packaged branded "Pão Francês", an organic
  packaged Italian tomato, and a packaged Friboi contra filé; each is a different, barcoded product, not the store's
  bulk item.
- **Why:** Open Food Facts catalogs packaged products identified by barcode. Bulk produce, bakery bread and butcher
  cuts aren't products there, and a match wouldn't give identity anyway.
- **So:** link barcode-less items to a short list of generic items (banana prata, banana caturra, maçã nacional, pão
  francês, contrafilé), with the LLM proposing and the user confirming once per store item (D51). Store items in
  different stores that land on the same generic item become one product (data-model.md, flow 2). A candidate seed
  for the list is the subitem list of the official IPCA, which would also let the IPCI (D64) be compared with official
  inflation item by item; to verify when built.

## D67. Jev (TypeSafe, hosted) benchmark: 96% untrained with one flat question, calibrated confidence (2026-10-25)

The user got a Jev API key with US$5 to try it. Jev speaks the same `/v1/systemone` protocol as Laya, so
`tools/categorize-eval.py laya` gained `--key-env` (bearer key from the environment or `server/.env.local`, never
printed), `--flat` (one choice question over all 62 leaf categories; Jev takes up to 255 options, Laya's head ~20) and
`--context` (unit, price, store and the receipt's other items in the state). Model `jev-latest`, the user's 82 items,
English descriptions of the categories (`descriptions-en.tsv`).

| Run | Exact | Top level |
|---|---|---|
| Tree walk, hint lexicon (Laya's best untrained setup: 61–63%) | 90% | 95% |
| Flat, hint lexicon | 96% | 99% |
| Flat, hint lexicon, context | 96% | 99% |
| Flat, translation-only lexicon (`lexicon-pure.tsv`) | 96% | 99% |
| Flat, raw receipt text | 70% | 90% |
| Flat, raw receipt text, context | 85% | 96% |

For comparison: trained Laya 90% (D58), local LLM with context, examples and notes 95% (D58).

- **Confidence is usable** (translation-only flat run): median 1.0 when right, 0.87 when wrong; at ≥0.9, 71 of 82
  assigned with 70 right. The unknown brand (ST FRANCIS) came out at 0.14, so it would go to review. The other two
  misses are boundary calls (chicken breast and smoked loin as cold cuts), the kind D57 leaves to the LLM's rules.
- **Caveat:** the lexicons were written while looking at these same 82 items, so 96% is an upper bound. The honest
  no-help number is raw text: 85% with context, about what the plain local LLM reached (84–87%).
- **Cost and speed:** ~1.06 M input tokens for all six runs, about US$0.045 at US$0.042 per million; ~240 ms per
  item. One item with context is ~2,500 tokens, so 1,000 items cost about US$0.10.
- **So:** Jev is a strong candidate for the "system one" role (D57): untrained, it matches the LLM and beats trained
  Laya, its confidence can route items to review, and it is cheap and fast. Data leaves to TypeSafe: item
  descriptions, and with context the store name, prices and the receipt's other items. Not decided: Jev vs Laya;
  next would be a lexicon kept by the LLM instead of by hand, measured on items not seen when writing it.

## D68. Open Food Facts names fed to Jev: raw text 85% → 90%; with the lexicon no gain (2026-10-25)

The user noticed that D60's lookups had never reached a model: they only measured coverage. `categorize-eval.py laya
--lookup` now adds Open Food Facts' name (with brand and quantity) and its last few categories to the state when the
barcode is known (14 of the 82 items). Same Jev setup as D67, flat question.

- **Raw receipt text with context: 85% → 90%.** All three misses that Open Food Facts knew got fixed: LING CALAB SEARA
  (sausage), PEITO FGO EXCELSIOR (its "peito de frango defumado" made it a cold cut) and DET LIMPOL (cleaning).
- **Translation-only lexicon: 96% → 95%.** The detergent flipped to laundry: Open Food Facts calls it just
  "detergente", vaguer than the lexicon's "dish detergent". The other misses had no lookup.
- **So:** lookup names help exactly where the receipt text is unreadable and the barcode is known; coverage is the
  limit. 11 of the 12 raw-text misses have barcodes, only 3 of them in Open Food Facts; the rest are mainstream
  Brazilian brands (Tirol, Carolina, Duetto), so Cosmos (needs the user's token) is the next test.

## D69. A standing data-cleanliness routine for category assignments (planned, 2026-10-25)

The user's goal: bad assignments must not stay around long. Paid barcode lookups are not the answer (Cosmos starts at
R$ 499,99/month for 100 lookups a day); user corrections are the data to learn from, and with enough of them we become
the authority ourselves. Builds on D51 (suggestions with source and review state), D57 (Laya or Jev assigns, the LLM
reviews), D65 (worker health report) and D67 (Jev's usable confidence).

1. **Provenance on every assignment:** source (Jev, Laya, LLM, rule, user), model version, confidence, time and
   review state (automatic, confirmed, corrected). Any assignment can be traced and redone.
2. **New items:** above a confidence threshold, accepted as "automatic, not confirmed"; below, the review list. (D67:
   at ≥0.9, 71 of 82 accepted with one wrong.)
3. **Weekly audit by the worker:** the LLM rechecks automatic assignments, highest spending first (a wrong category on
   an expensive item distorts reports most), then oldest unaudited, plus a small random sample that measures the true
   error rate. A disagreement goes to the review list; nothing changes silently.
4. **Learning from corrections:** each correction becomes a labeled example; the LLM proposes the rule or lexicon entry
   it implies ("sliced cured meats are cold cuts", "IOG = iogurte") and sends other items with the same pattern to
   review. Across users, many corrections of the same barcode make that answer authoritative.
5. **Regression gate:** confirmed items form a growing test set. A new model, lexicon, rule or taxonomy change must
   score at least as well on it before it goes live (the guard against conflicting abbreviations).
6. **Re-runs on change:** a model, lexicon or taxonomy change re-runs automatic assignments; differences go to review.
7. **Metrics in the health report (D65):** share accepted automatically, review backlog, sampled error rate, age of
   the oldest unaudited assignment. A rising error rate raises the acceptance threshold.
8. **User confirmations are never overwritten** (D51); confirmations that contradict each other are flagged.

History heals itself: lines point to products and reports compute categories at query time, so fixing a product's
category fixes every past month. The routine only has to keep errors from going unnoticed.

- **When:** with stage 4's review screen (provenance, review states) and the worker (audit, gate, metrics).

## D70. Stage 4 plan: Supabase first, then Jev suggestions and full editing on the phone (2026-10-25)

Chosen by the user while planning stage 4; details in [stage-4-plan.md](stage-4-plan.md). Replaces the roadmap's
manual-only stage 4 with PocketBase as the catalog's owner.

- **Supabase first (4a).** Today PocketBase owns categories and the phone's mirror overwrites local values on every
  refresh (D46), so a phone edit would be lost. The owner moves straight to Supabase (D62) instead of building sync
  twice: schema by migrations with Row Level Security, provenance fields (D51, D69), one account, Room plus an
  outbox on the phone, D61's small photos, a one-time migration of the real data (receipts from the phone,
  categories from PocketBase), and D65's nightly backup from the first day.
- **Jev from the start (4b),** not manual-only first: new store items get a suggestion with confidence (D67);
  ≥0.9 becomes "automatic, not confirmed", the rest go to review. Manual picking stays complete on its own.
- **Jev runs in a Supabase Edge Function,** since it's a plain HTTP API: suggestions appear with the PC off. The local
  LLM's review (D69) stays on the PC worker.
- **Edit scope (4c):** category, product identity (rename, link and unlink store items across stores), size and
  discounts (manual correction, back to prorated), and the category tree itself (create, rename, move, delete).
- **Unchanged:** Portuguese UI; the review list comes first in the phone's work.

## D71. Stage 4a built: Supabase schema online, the phone syncs, PocketBase is out of the app (2026-10-25)

Implements the 4a part of D70 ([stage-4-plan.md](stage-4-plan.md)). Real data not moved yet.

- **Schema** (`supabase/migrations/`, applied to the online project in São Paulo): the phone's tables with `user_id` in
  every key, composite foreign keys that include it (a row can't point at another user's row even without RLS), Row
  Level Security on everything, explicit grants (the project doesn't expose new tables automatically), tombstones
  and a clock-stamped `updated_at` for pulls. Provenance columns per D51/D69 (`value_source`, `review_state`). A
  trigger keeps a user's category, manual size and store-item link from being replaced by any non-user write. New
  accounts get the taxonomy (`taxonomy_node`, regenerated as a migration by `tools/taxonomy-sql.py`); the phone
  calls `sync_taxonomy()` to pick up later changes. Pages, archive photos and masks go to a private bucket, one
  folder per user. pgTAP tests (`npx supabase test db`) cover isolation and the user-choice rule. Checked online: the
  publishable key alone reads nothing.
- **Phone (Room v4):** `dirty` flags, tombstones, provenance columns, pull cursors in the database (they travel with
  a backup). The 3→4 migration marks everything for upload to the new server and labels PocketBase's categories
  `import`/`automatic` (set by an agent or by hand outside the app, not confirmed in it).
- **Sync** (`sync/SyncEngine`): pull the seeded categories and tags first, adopting the server's ids for ones the
  phone knew under its own (by path or slug); push dirty rows parents first, receipts with their page when their
  version changed, lines with removal of lines the page no longer has, and photos one by one as D61 archive copies
  (receipt crop from the capture outline, grey, WebP quality 70, `WebpArchiver`); then pull changes since the
  cursor, minus a minute of overlap, never over a row still dirty. A pushed row is cleaned only if it's still what
  was pushed. Plain OkHttp against Auth, PostgREST and Storage (`SupabaseClient`), no SDK.
- **Account:** Settings signs in with e-mail and password and keeps only the refresh token (rotated on every sync).
  Without an account the phone works alone. Debug builds carry the project URL and publishable key from
  `server/.env.local`; `-Pgetnf.supabaseUrl/-Pgetnf.supabaseKey` point the emulator at a local Supabase.
- **Removed:** `PocketBaseClient`, `ServerMirror`, `UploadWorker`, `ServerSettings` (`SyncWorker` replaces the
  worker). The PocketBase server keeps running until the real data is verified on Supabase.
- **Backups (D65):** `tools/supabase-backup.py` (data-only `pg_dump` of the app and accounts, 14 generations, plus an
  incremental mirror of the bucket) and `tools/supabase-restore-drill.sh` (into the local Supabase, compares counts
  with the live project); a systemd user timer for 03:30 in `tools/systemd/`.
- **Tested:** a fake-server suite for the sync rules; one test against a real local Supabase (two "phones" on one
  account: push, line removal, adoption with the page); the 3→4 migration, the backup round trip and the WebP copy on
  the emulator; sign-in and a first sync from the emulator to the local Supabase.
- **Not yet:** deleting photo originals on the phone after confirmation (D61), a sign-up screen (the account is
  created in the Supabase dashboard), and store-item clashes between two devices that create the same code offline
  (one device for now).

## D72. Real data moved to Supabase and verified; first restore drill caught a backup bug (2026-10-25)

With the user's go-ahead. Before: the old app opened once to refresh its copy of PocketBase's catalog (84 products),
then backups `~/getnf-backups/pre-supabase-2026-10-25.zip` (PocketBase, 260 MB) and `phone-pre-supabase-2026-10-25.tar`
(the app's data, 133 MB). The new APK upgraded the phone's database to v4; the user signed in; the first sync sent
everything.

- **Supabase vs the phone before:** 9 receipts, 135 lines, 61 photos, 4 issuers, 84 products and store items, 75
  categories, 7 tags, 4 product tags; net R$ 1.927,75 on both sides, and the receipts' paid totals add up to it; the
  4 photo-read discounts kept their source. Nothing left dirty or unuploaded on the phone, no errors.
- **Supabase vs PocketBase, item by item:** all 84 products have the same category (82 categorized, 2 not), and the
  3 manual sizes (one of them "no size") match. Categories arrived as `import`/`automatic` (D71).
- **Storage:** 106 objects, 11 MB (9 pages, 61 WebP copies averaging 166 KB, 36 masks), against ~250 MB of photos on
  PocketBase (half of them duplicates, D61). The free tier's 1 GB now lasts years.
- **Backup bug found by the drill:** `pg_dump --schema=public --table=auth.users` dumped only the accounts, since
  `--schema` is ignored once a `--table` is given. Fixed (`--table=public.*`, minus the taxonomy seed the migrations
  load); the drill then restored every table with matching counts and the same net total.
- **Build fix:** a debug build inside a git worktree found no `server/.env.local` (gitignored, only in the main
  checkout), so the first APK had no project address; the build now falls back to the main checkout's file.
- **PocketBase** still runs, untouched, until the user retires it.

## D73. PocketBase retired; nightly Supabase backup installed (2026-10-25)

Asked by the user after D72's verification. The PocketBase container is down (`docker compose -f server/compose.yaml
down`); its data stays in `server/pb_data/` and in `~/getnf-backups/pre-supabase-2026-10-25.zip`. `server/` (hooks,
migrations, the old dashboard) stays in the repo for reference until it's removed on purpose; nothing in the app uses
it. Bringing it back: `docker compose -f server/compose.yaml up -d`.

The nightly backup (D65) runs as a systemd user timer at 03:30 (`tools/systemd/`), from the main checkout's
`tools/supabase-backup.py`. Check with `systemctl --user list-timers getnf-backup` and
`python3 tools/supabase-backup.py --check`.

## D74. Stage 4b live: Jev categorizes new items online; 94% on the 82 items, every automatic one right (2026-10-25)

Implements 4b of D70. Live Jev calls with the user's go-ahead.

- **How it runs:** a store item of an uncategorized product queues a `categorize_job` (trigger). `pg_cron` checks every
  minute and, only if jobs wait, calls the `categorize` Edge Function through `pg_net`, with a shared secret kept in
  Vault (`categorize_url`, `categorize_secret`) and in the function's secrets (with `JEV_API_KEY`); nothing of it
  is in git. The function claims a batch (`for update skip locked`), translates each description with the shared
  `lexicon` table, adds Open Food Facts' name and categories for a barcode (`barcode_lookup`, once per code), asks
  Jev one choice over the user's leaf categories described by `taxonomy/descriptions-en.txt`, and writes back
  through `apply_categorization`: ≥ 0.9 becomes the category (`jev`/`automatic`), below it a suggestion
  (`suggestion_*` columns) that counts nowhere until the user takes it. Failures retry with backoff, six times.
- **Rules:** a product with a category or a suggestion isn't touched; the user's choice is final (D51); and a write
  without a source (a phone that hadn't pulled the answer yet) can't clear a sourced category. The phone (Room v5)
  pulls suggestions and never pushes them.
- **Check mode:** a POST with `{"check": {user_id, items}}` answers items through the same code and writes nothing:
  the regression check of D69. On the user's 82 items: 77 right (94%, as D68's 95%); at ≥ 0.9, 73 assigned
  automatically and all 73 right; the 5 misses all fell below and would go to review (chicken breast and smoked loin
  as cold cuts, the detergent, the unknown brand, and a T-shirt whose `DET FRONTAL`, "frontal detail", the lexicon
  read as detergent: the conflicting abbreviation the user warned about in D59).
- **First real run:** the two products that had no category (pharmacy items) got suggestions (over-the-counter
  medicine, 0.66 and 0.80).
- **Bug found on deploy:** the online project grants new tables to no role (D70's choice), and the service role had
  none on the new tables, so the function failed with a 500 while all local tests passed (the local Supabase grants
  everything). Fixed by a migration granting the service role on `public`, now and by default.
- **Costs:** Jev ~US$0.0001 per item; the cron job runs SQL only, and calls the function only when there is work.
- **Data to TypeSafe:** the translated description and Open Food Facts' name and categories. Not the store, prices
  or chave.

## D75. Category tree edits are recorded as events; merge and split join the editor; categories get descriptions (2026-10-25)

Proposed in a review of how users edit their tree, accepted by the user. Until now a category carried only
`user_modified` and `deleted`, with no record of what changed, so tree edits taught nothing.

- **An append-only `category_event` log:** one row per edit (create, rename, move, merge, split, delete) with the
  category before and after (several "after" for a split), the old and new label or parent, the time and the user.
  The phone writes it in the same transaction as the edit and pushes it like other rows; nothing rewrites it.
- **Merge and split** join stage 4c's tree editor (D70 had create, rename, move, delete).
  - **Merge:** confirmed products follow to the resulting category and stay confirmed.
  - **Split:** the old category's products go to the review list to pick one of the new ones; their earlier
    confirmations leave D69's test set until picked.
  - **Any tree change** triggers D69's re-run of automatic assignments.
- **Descriptions:** each category gets a one-line description apart from its label. For a new category the LLM
  drafts it and the user accepts or edits it. Jev reads the description instead of the label (today a user-created
  category is described to Jev by its label only, D74).
- **Later:** the same log feeds learning a user's preferences from their restructurings, and, across users (stages
  9–10), shows which seed categories people keep splitting or merging: the signal to improve the shared taxonomy.
- **When:** at the start of 4c, before the tree editor, so the first edit is already logged. Needs a migration and
  a Room version.

## D76. Stage 4c built: review list, product and line editing, and the category tree editor on the phone (2026-10-25)

Implements 4c of D70 with D75. Portuguese UI on every new screen; Home, receipt detail and Settings translated too.

- **Data (`CatalogEditor`):** every hand edit in one place, in a transaction, marking rows dirty with the user as
  the source: confirm a model's category or suggestion (`user`/`confirmed`, the model kept for provenance), pick
  another (`corrected` when it disagrees with what a model said), rename, size by hand / "no size" / back to the
  description's, tags, link a store item to another product (the emptied product is tombstoned unless something
  about it was set by hand) and unlink, line discount by hand with the rest of the receipt's discount spread again,
  back to prorated, confirm a photo-read discount, and "this line only is another product".
- **Tree (D75):** create, rename and describe, move (not into itself), merge (products keep their review state,
  subcategories follow), split (new subcategories under it; its products stay there, so reports still add up, and
  go to the review list as "pick a part"), delete (only without subcategories; products go to a chosen destination,
  the parent suggested). Each edit writes a `category_event` in the same transaction, pushed append-only
  (`ignore-duplicates`; users can't update or delete events online). Categories carry a description the user writes
  (`category.description`); the categorize function prefers it to the seed's and to the label. The LLM drafting
  descriptions waits for the LLM worker (D69).
- **Screens:** Home shows "Produtos · N para escolher" and "Categorias". Produtos: "Para escolher" (no category,
  only a suggestion, or a split category), "Automáticas" (model-set, unconfirmed), "Todos", by amount spent, with
  search, one-tap Confirmar and a searchable category picker. Produto: name, category with its origin, size, tags,
  its store items (Separar) and "Juntar outro produto a este" (warns when one is sold by weight and the other by
  unit), purchases. Receipt lines open a dialog for discount and product. Categorias: the tree with product counts
  and the edit actions.
- **Tested:** unit tests for every edit and for pushing the log once; on the emulator against the local Supabase
  with the stand-in Jev: sign-in adopted a receipt and its suggestions, a correction arrived online as
  `user`/`corrected`, a split logged its event (2 parts, 1 product) and sent the apple to "Para escolher", and a
  hand discount kept the receipt's total. Server side deployed online (category events, descriptions, function).
- **Not yet:** installing this build on the user's phone; D69's re-run of automatic assignments after a tree change;
  the LLM's description drafts; undo.
