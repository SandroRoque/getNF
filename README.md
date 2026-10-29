# getNF

An Android app that keeps a household's grocery receipts (NFC-e, *notas fiscais de consumidor*) and turns them into
spending reports and a personal inflation index. Portuguese throughout; Santa Catarina for now. "getNF" is a working
name; the project's site is https://caderneta.roqlab.com.br.

1. **Scan.** Point the camera at the receipt and move along it. The app reads the QR code and the print as it goes,
   and keeps the fewest photos that read every line.
2. **Fetch.** The app opens the receipt's page at SEFAZ from the QR code (you tap Validar if a check appears) and
   reads the items, store, totals and payments from it, on the phone. Discounts per item are read from the photos.
3. **Organize.** Items get categories, from an online categorizer or by hand; what needs a decision waits in
   "Para você".
4. **See.** Spending by period, category, product and store, and the IPCI: how the prices of what you actually buy
   change.

It works without an account. With one, a *caderneta* (a shared catalog of receipts) syncs to the cloud and can be
shared with the people you shop with. Photos stay on the phone unless you choose otherwise.

## Docs

- [docs/status.md](docs/status.md): where things stand, how to resume, what's open
- [docs/architecture.md](docs/architecture.md): how the parts fit together
- [docs/decisions.md](docs/decisions.md): why things are the way they are
- [docs/dev-environment.md](docs/dev-environment.md): toolchain, emulator, daily commands
- [docs/sefaz-sc.md](docs/sefaz-sc.md): how the Santa Catarina portal behaves
- [docs/archive/](docs/archive/): plans and comparisons from earlier stages

## Layout

`android/` the app · `supabase/` schema, tests and the categorizer · `tools/` scripts and the PC's nightly jobs ·
`web/` the site · `taxonomy/` the category tree · `docs/` · `test-media/` real receipts (local only, never committed)

## Build

```bash
. tools/env.sh
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Debug builds read the Supabase URL and publishable key from `.env.local` at the repo root (not in git).
