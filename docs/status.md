# Status and next steps

Last updated 2026-10-09. A handoff for picking the project up again. For *why* things are the way they are, read
`docs/decisions.md` (D1–D17). For the portal's behaviour, read `docs/sefaz-sc.md`.

## Where it stands

**Working end to end, tested on the real phone:** scan QR → full-resolution photos → SEFAZ page in a WebView (the user
taps Validar if a challenge shows) → saved on the phone → uploaded to PocketBase → parsed into products and item lines →
categorized → reportable through the `spending_lines` view.

- **App (`android/`):** scan, photos, page capture. Receipts can be queued ("Later") and fetched afterwards ("Fetch next").
  The app paces SEFAZ lookups per network and warns before a fetch would probably get the IP blocked (D17). It skips the
  portal's scripts, styles and images it doesn't need (D16), recognizes the rate-limit page, and pauses the WebView in the background.
- **Server (`server/`):** PocketBase with hooks that parse every uploaded page (`pb_hooks/lib/`), sync `taxonomy/*.txt`
  at startup, and expose `spending_lines` (D15). Prorated discounts until a photo pass exists (D14).
- **Local data** (not in git): the receipts captured so far, every product categorized, in `server/pb_data/` plus the
  photos on the phone. **Back up `server/pb_data/`.** The categorizations exist only there.
- **Tests:** `cd android && ./gradlew testDebugUnitTest` (19 tests) · `node --test "server/test/*.test.js"` (12 tests).
- **Repo:** public at github.com/SandroRoque/getNF, branch `main`. History was squashed before publishing to remove
  personal data. Commits use the GitHub noreply address.

## Resuming

```bash
. tools/env.sh
docker compose -f server/compose.yaml up -d            # PocketBase; admin UI http://127.0.0.1:8090/_/
tools/emulator.sh --headless --camera test-media/video/<video>.mp4   # optional; works with the phone plugged in too
adb -d reverse tcp:8090 tcp:8090                       # phone → PocketBase over USB. Lost on unplug/reconnect: redo it.
```

The phone's server URL is set to `http://127.0.0.1:8090` (the USB tunnel). The emulator uses the build default `http://10.0.2.2:8090`.

Categorizing new products: PocketBase admin UI → `products`, filter `category = ""`. Or ask Claude to propose a mapping
and apply it through the API (as was done for the first 75 products).

## SEFAZ rules of thumb (learned the hard way, see docs/sefaz-sc.md)

- Blocks are **per IP**, after roughly **5 lookups per hour**. A lookup is opening a receipt's page, plus Validar after a
  challenge. Asset count doesn't seem to matter. Home Wi-Fi and each SIM are separate budgets, and switching SIM worked immediately.
- Scan a pile of receipts with **Later**, then use **Fetch next**, switching network when the budget runs out.
- **Never probe the live portal for development.** Use saved pages, fixtures, or DevTools on an already-open WebView
  (`tools/webview-eval.mjs`). The PC, emulator and phone share the home IP.

## Open items, roughly in priority order

1. **Uploads only work with the phone plugged into this PC** (adb tunnel). Next step: Tailscale (D6) on the phone and
   the PC/NAS, and point the app's server URL at the tailnet address.
2. **Untested live:** the page screen's **Next** (to the next waiting receipt) only appears after a real save.
3. **Is the home IP shared (CGNAT)?** Check the router's WAN address: anything in 100.64.0.0/10 means yes.
   Adjust `LookupBudget.LIMIT` once more blocks (or non-blocks) tell us more.
4. **Reports:** nothing renders `spending_lines` yet (weekly/monthly/quarterly/yearly per category). Options: a small
   static page, a notebook, or a dashboard tool reading PocketBase's SQLite/API.
5. **Photo pass for per-item discounts** (D14): read "Desconto" lines from the photos with a vision model and store them with
   `discount_source = photo`. The re-parse logic already preserves them.
6. **Category suggestions:** have an LLM propose categories for new products, for the user to confirm.
7. **Size parsing gaps** (`server/pb_hooks/lib/derive.js` `parseSize`): "1K" (= 1 kg) and "1 25KG" (= 1.25 kg) aren't
   understood. Those were fixed by hand (`size_source = manual`).
8. **Manual-chave fallback** for damaged QR codes (D3/D13): not built.
9. **Minor:** a few details-page assets slip past the blocking before `onPageStarted` fires (D16).

## Gotchas

- PocketBase rejects view queries with a bare `||` or `CASE` in a column: wrap them in parentheses (D15).
- Schema changes made in the admin UI or through the collections API write migration files into `server/pb_migrations/`
  (root-owned). Commit or delete them (`docs/dev-environment.md`).
- A failing migration stops PocketBase from starting. Validate view SQL first by creating a throwaway collection via the API.
- New test fixtures must go through `tools/make-nfce-fixture.py`, which replaces chave, store, dates and taxes with fake values.
  Never commit a real chave: anyone can look it up on the portal.
