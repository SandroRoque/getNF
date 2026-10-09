# getNF

Personal Android app to archive Brazilian NFC-e receipts (*notas fiscais de consumidor*).

1. Point the phone at a paper receipt: the app reads the QR code and takes full-resolution photos.
2. It opens the SEFAZ portal page from the QR code in an in-app browser. The user solves the Cloudflare Turnstile if
   one appears, and the app grabs the page (and the XML, if available).
3. Photos + raw page + chave de acesso are stored on the phone and uploaded to a self-hosted PocketBase server.

**Status:** working end to end on a real phone, including server-side parsing and categorization. See
[docs/status.md](docs/status.md) for the current state and open items.

Raw captures are the source of truth. Structured data (items, totals, discounts) is derived and can be
re-extracted later.

- [docs/decisions.md](docs/decisions.md): why things are the way they are
- [docs/dev-environment.md](docs/dev-environment.md): toolchain, emulator, fake camera, daily commands
- [docs/sefaz-sc.md](docs/sefaz-sc.md): how the Santa Catarina portal behaves

Layout: `android/` app · `server/` PocketBase · `tools/` dev scripts · `docs/` · `test-media/` (local only).
