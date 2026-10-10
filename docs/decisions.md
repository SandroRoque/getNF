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
