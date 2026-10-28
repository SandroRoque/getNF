# Status and next steps

Last updated 2026-10-27. A handoff for picking the project up again. For *why* things are the way they are, read
`docs/decisions.md` (D1–D104). For the portal's behaviour, read `docs/sefaz-sc.md`. For the data flow and analytical
semantics, read `docs/architecture.md`. Objectives are in [objectives.md](objectives.md), the stages in
[roadmap.md](roadmap.md).

## Where it stands

A self-contained Android app, synced to Supabase, with an LLM worker on the PC:

scan QR → the capture assembles the receipt from its printed rows and keeps the fewest photos that read it → SEFAZ page
in a WebView (the user taps Validar if a challenge shows) → parsed on the phone into lines, products and store items,
discounts read from the photos → synced to Supabase, inside a caderneta → Jev categorizes new items online → reports
and the IPCI on the phone → the nightly LLM worker audits and suggests; the user decides on the phone.

| Stage | What | State |
|---|---|---|
| 1 | Data model ([data-model.md](data-model.md)) | Done (D44) |
| 2 | Local database (Room), export/restore | Done (D45–D47) |
| 3 | Parsing on the phone, discounts from photos | Done (D48–D54, D101) |
| 4 | Supabase, Jev suggestions, editing on the phone ([stage-4-plan.md](stage-4-plan.md)) | Done (D70–D76) |
| 5 | Spending reports on the phone | Done (D77) |
| 6 | PC dashboard | Dropped for now (D79) |
| 7 | IPCI, the personal inflation index | Done (D79) |
| 8 | LLM worker on the PC ([stage-8-plan.md](stage-8-plan.md)) | Done (D80) |
| — | Cadernetas: catalogs shared by several people (D104) | Built; not yet checked live nor applied online |
| 9 | Optional sharing and shared curation ([local-and-shared-modes.md](local-and-shared-modes.md)) | Not started |
| 10 | Analyses across users | Not started |

- **App (`android/`):** four tabs (D81): **Início** (this month), **Gastos** (period › category › product › purchase ›
  receipt, with Lojas as a second lens), **Para você** (every pending decision as a card), **Catálogo** (Produtos,
  Categorias, Lojas, Notas; all editing starts here), with scan in the middle of the bar and Ajustes behind the account
  icon on Início. The "Calmo" look in light and dark, Portuguese throughout. Room database (v10), export/restore to one
  zip in Ajustes. Works without an account; with one it syncs the open caderneta (`sync/SyncEngine`).
- **Capture (D82–D95, D97):** planned live from OCR of the print: the receipt is assembled from its rows as the camera
  moves, only frames that add rows are saved, and a finished capture keeps the fewest photos that read it. Changes go
  through the benchmark ([capture-bench.md](capture-bench.md)), scored against the receipts' pages; ideas wait in
  [capture-backlog.md](capture-backlog.md).
- **After a scan (D99–D101):** "Nota salva" offers the page at once or reminds 3 h later; a receipt waiting for its
  page has its own screen; once fetched, the receipt opens and its photos' discounts are read by themselves.
- **Photos (D98, D103):** stay on the phone. Copies go online only if the user opts in, and then only for receipts the
  app failed with, with the card slip, any CPF and the operator blanked.
- **Cadernetas (D104):** a person can own several cadernetas and be in others; everyone in one sees everything. Each
  has its own database and receipts folder on the phone; opening another restarts the app. Ajustes › Cadernetas
  creates, joins by code, invites, changes roles, copies receipts to another caderneta and leaves; Conta creates a
  login and deletes it. Store items and parser-made products have code-derived ids, so two phones make the same rows;
  a row the server refuses is parked instead of failing every sync. Pages lose the buyer's CPF and name when saved. A
  member claims a receipt before fetching its page; a page another member fetched is adopted.
- **SEFAZ lookups:** paced per network with a warning before a fetch would probably get the IP blocked (D17); the
  portal's unneeded assets are skipped (D16).
- **Supabase** (`supabase/`, project in São Paulo): every row belongs to a caderneta, with RLS by membership; a private
  bucket with a folder per caderneta (D71, D104). The real data moved and was verified (D72). The `categorize` Edge
  Function runs Jev on new items every minute: ≥ 0.9 sets the category, below it a suggestion (D74).
- **PC jobs** (systemd user timers, `tools/systemd/`): backup at 03:30 (`tools/supabase-backup.py`, D73) and the LLM
  worker at 04:00 (`tools/llm-worker.py` with LM Studio, D80). Check with `systemctl --user list-timers`.
- **Retired:** PocketBase and its dashboard (D73). `server/` stays for reference and the parser parity tests.
- **Repo:** public at github.com/SandroRoque/getNF, branch `main`. History was squashed before publishing to remove
  personal data. Commits use the GitHub noreply address.

## Next

1. **Cadernetas live (D104):** apply `supabase/migrations/20261027000000_ledgers.sql` to the local Supabase, run
   `SupabaseLocalTest` and two emulators as two people (invite, join, scan the same receipt, leave), then online after
   a backup, together with installing the phone build (the old build can't sync once the migration is online).
2. **The user's review of the redesign (D81) on the phone.** The SEFAZ page screen isn't redesigned yet.
3. **Capture:** held-out sessions of receipts the capture hasn't seen (D89).

## Open items

- **Cadernetas, not built yet:** the "por Ana" mark on receipts another member scanned (scan marks are on the server,
  not pulled yet); syncing cadernetas other than the open one in the background; cleaning buyer data from pages already
  online (new pages are clean); a screen for the setup history (`ledger_event`).
- **Photos (D103):** turning the opt-in off should delete what was sent; abandoned captures aren't kept for opted-in
  users; the photos uploaded before D103 stay until the user decides. How long the phone keeps its own copies is open.
- **Discounts from photos:** confirming each value against its photo snippet, and undo back to prorated.
- **Stage 4 leftovers:** automatic assignments aren't re-run after a category tree change (D69).
- **Manual-chave fallback** for damaged QR codes (D3/D13): not built.
- **Size parsing gaps:** "1K" (= 1 kg) and "1 25KG" (= 1.25 kg) aren't understood; those were fixed by hand.
- **Is the home IP shared (CGNAT)?** Check the router's WAN address: anything in 100.64.0.0/10 means yes. Adjust
  `LookupBudget.LIMIT` once more blocks (or non-blocks) tell us more.
- **IPCI:** with four months of receipts in two years, only June 2026 has a reading so far (+2,3%); the rest say
  "dados insuficientes", as intended.
- **Minor:** a few details-page assets slip past the blocking before `onPageStarted` fires (D16).

## Resuming

```bash
. tools/env.sh
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
tools/emulator.sh --headless --camera test-media/video/<video>.mp4   # optional; works with the phone plugged in too
npx supabase start                                                  # local Supabase for development (Docker)
```

Debug builds talk to the online Supabase with the URL and publishable key from `server/.env.local` (a worktree falls
back to the main checkout's copy). `-Pgetnf.supabaseUrl/-Pgetnf.supabaseKey` point the emulator at a local Supabase.

## Tests

- **Android:** JVM unit tests (`testDebugUnitTest`) and instrumented tests (the focus benchmark and the real-capture
  eval, which skip without local files). **Never run connectedAndroidTest with the phone attached:** it uninstalls the
  app and wipes its receipts. On the emulator only.
- **Supabase:** `npx supabase test db` (pgTAP: isolation between cadernetas, roles, invites, leaving, the user-choice
  rule, the categorize queue). `SupabaseLocalTest` runs the phone's sync against the local Supabase when
  `GETNF_SUPABASE_LOCAL` is set.
- **Capture:** `tools/capture-bench.sh` (D89).
- **Parser parity:** after changing either parser, `node tools/nfce-golden.mjs` (Kotlin vs the old JS, D48).

## Pending, by the user

- Run `sudo bash tools/setup-system.sh` once (udev rules for the phone, Docker at boot for the local Supabase, adb and
  kvm groups).

## SEFAZ rules of thumb (learned the hard way, see docs/sefaz-sc.md)

- Blocks are **per IP**, after roughly **5 lookups per hour**. A lookup is opening a receipt's page, plus Validar after a
  challenge. Asset count doesn't seem to matter. Home Wi-Fi and each SIM are separate budgets, and switching SIM worked immediately.
- Scan a pile of receipts and fetch their pages later, switching network when the budget runs out. In a caderneta,
  other members' networks are other budgets.
- **Never probe the live portal for development.** Use saved pages, fixtures, or DevTools on an already-open WebView
  (`tools/webview-eval.mjs`). The PC, emulator and phone share the home IP.

## Gotchas

- Moving a seed category needs a path-rename migration; `tools/taxonomy-sql.py` alone would create a second category
  and leave the old one (D78).
- New Supabase tables aren't exposed automatically: grant them explicitly in the migration (D71, D74).
- Ids of catalog rows are unique per caderneta, not globally (D104): PostgREST upserts use `ledger_id,id`.
- New test fixtures must go through `tools/make-nfce-fixture.py`, which replaces chave, store, dates and taxes with fake
  values. Never commit a real chave: anyone can look it up on the portal.
- Installing an APK older than D45 shows an empty list; restore from backup.
