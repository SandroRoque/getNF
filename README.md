# getNF

Personal Android app to archive Brazilian NFC-e receipts (*notas fiscais de consumidor*).

1. Point the camera at the receipt and move along it. The app detects visible edges, measures sharpness only
   inside the paper, and automatically saves overlapping segments. It returns home when top-to-bottom coverage
   and a valid QR are complete; the whole receipt does not have to fit in one frame.
2. From home, request the SEFAZ page from the QR code in an in-app browser. The user solves the Cloudflare Turnstile if
   one appears, and the app grabs the page (and the XML, if available).
3. Photos + raw page + chave de acesso are stored on the phone and uploaded to a self-hosted PocketBase server.

**Status:** original pipeline working end to end on a real phone, including server-side parsing and categorization;
continuous capture has emulator coverage and two completed Motorola scans with persisted images. Review found a
blurred image accepted by the quality filter, so image legibility still needs work. See
[docs/status.md](docs/status.md) for the current state and open items.

Raw captures are the source of truth. Structured data (items, totals, discounts) is derived and can be
re-extracted later.

- [docs/decisions.md](docs/decisions.md): why things are the way they are
- [docs/architecture.md](docs/architecture.md): data flow, processing, and analytical semantics
- [docs/objectives.md](docs/objectives.md): product objectives, Android autonomy, manual catalog management, IPCI, and possible assistance APIs
- [docs/capture-validation.md](docs/capture-validation.md): capture evidence, tests, and known quality limitations
- [docs/capture-retention-plan.md](docs/capture-retention-plan.md): development retention and future quality/redundancy selection
- [docs/roadmap.md](docs/roadmap.md): proposed implementation stages, deliverables, and dependencies
- [docs/local-and-shared-modes.md](docs/local-and-shared-modes.md): autonomous local use, optional curation contributions, and collective analysis access
- [docs/dev-environment.md](docs/dev-environment.md): toolchain, emulator, fake camera, daily commands
- [docs/sefaz-sc.md](docs/sefaz-sc.md): how the Santa Catarina portal behaves

## Spending dashboard

With PocketBase running, open <http://127.0.0.1:8090/dashboard/> on the PC, or `/dashboard/` at the phone's configured
server address on home Wi-Fi. Sign in with the phone app account or your PocketBase admin login saved for this server.

The dashboard starts with the latest 12 months. Click a month in the stacked bar chart, then a category in its pie
chart or legend to see products bought that month. Expand a product to see individual purchases. The date controls,
All history, and category depth selector change the breakdown. Refresh reloads the live receipt data; signing out
clears the browser session and displayed data.

**Receipts this month** lists the selected month's NFs across all categories, with issue dates, stores, item counts,
and totals paid. Expand a store row to see that NF's items, categories, quantities, and net amounts. Parser issues and
differences between parsed item sums and NF totals are flagged in the receipt details.

Amounts are net spending from captured receipts, in BRL, grouped by the receipt's store-local issue month. Months
without receipts do not imply zero actual spending. Item discounts may be prorated. New uncategorized products appear
in their own group. The dashboard is read-only and uses the existing server on port 8090.

The static assets live in `server/pb_public/dashboard/` and require no build or external libraries. After adding the
static mount to an existing installation, run `docker compose -f server/compose.yaml up -d` once; later asset edits are
served directly. Run dashboard tests with `node server/test/dashboard.test.mjs`.

Layout: `android/` app · `server/` PocketBase · `tools/` dev scripts · `docs/` · `test-media/` (local only).
