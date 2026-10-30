# Status and next steps

Last updated 2026-10-30. A handoff for picking the project up again. For *why* things are the way they are, read
`docs/decisions.md` (D1–D115). For the portal's behaviour, read `docs/sefaz-sc.md`. For the data flow and analytical
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
| — | Cadernetas: catalogs shared by several people (D104) | Done, online and on the phone |
| 9 | Optional sharing and shared curation ([local-and-shared-modes.md](local-and-shared-modes.md)) | Not started |
| 10 | Analyses across users | Not started |

- **App (`android/`):** four tabs (D81): **Início** (this month), **Gastos** (period › category › product › purchase ›
  receipt, with Lojas as a second lens), **Para você** (every pending decision as a card), **Catálogo** (Produtos,
  Categorias, Lojas, Notas; all editing starts here), with scan in the middle of the bar and Ajustes behind the account
  icon on Início. The "Calmo" look in light and dark, Portuguese throughout. Room database (v13), export/restore to one
  zip in Ajustes. Works without an account; with one it syncs the open caderneta (`sync/SyncEngine`).
- **Capture (D82–D95, D97):** planned live from OCR of the print: the receipt is assembled from its rows as the camera
  moves, only frames that add rows are saved, and a finished capture keeps the fewest photos that read it. Changes go
  through the benchmark ([capture-bench.md](capture-bench.md)), scored against the receipts' pages; ideas wait in
  [capture-backlog.md](capture-backlog.md).
- **First start (D115):** a three-page welcome, a how-to before the first scan, "Primeiros passos" on Início until
  done, and a read-only demo caderneta (bundled, never synced) one tap away.
- **After a scan (D99–D101):** "Nota salva" offers the page at once or reminds 3 h later; a receipt waiting for its
  page has its own screen; once fetched, the receipt opens and its photos' discounts are read by themselves.
- **Photos (D98, D103):** stay on the phone. Copies go online only if the user opts in, and then only for receipts the
  app failed with, with the card slip, any CPF and the operator blanked.
- **Cadernetas (D104):** a person can own several cadernetas and be in others; everyone in one sees everything. Each
  has its own database and receipts folder on the phone; opening another restarts the app. Ajustes › Cadernetas
  creates, joins by code, invites, changes roles, copies receipts to another caderneta and leaves; Conta creates a
  login and deletes it. Other cadernetas this phone has files for sync in the background; receipts another member
  scanned say "por Ana". Store items and parser-made products have code-derived ids, so two phones make the same rows;
  a row the server refuses is parked instead of failing every sync. Pages lose the buyer's CPF and name when saved. A
  member claims a receipt before fetching its page; a page another member fetched is adopted. The pages already
  online were cleaned of buyers too (`tools/blank-buyers.py`).
- **SEFAZ lookups:** paced per network with a warning before a fetch would probably get the IP blocked (D17); the
  portal's unneeded assets are skipped (D16).
- **Supabase** (`supabase/`, project in São Paulo): every row belongs to a caderneta, with RLS by membership; a private
  bucket with a folder per caderneta (D71, D104). The real data moved and was verified (D72). The `categorize` Edge
  Function runs Jev on new items every minute: ≥ 0.9 sets the category, below it a suggestion (D74).
- **PC jobs** (systemd user timers, `tools/systemd/`): backup at 03:30 (`tools/supabase-backup.py`, D73) and the LLM
  worker at 04:00 (`tools/llm-worker.py` with LM Studio, D80). Check with `systemctl --user list-timers`.
- **Retired:** PocketBase, its dashboard and the server-side JS parser (D73); `server/` was removed 2026-10-29
  (D107). Old plans and comparisons are in [archive/](archive/).
- **Repo:** public at github.com/SandroRoque/getNF, branch `main`. History was squashed before publishing to remove
  personal data. Commits use the GitHub noreply address.

## Next

1. **The user's review of the redesign (D81) on the phone,** now including the caderneta screens. The SEFAZ page
   screen isn't redesigned yet.
2. **System-1 reads more per item (D108), live since 2026-10-29:** category, how it's sold and the size (with pack
   counts) for each new store item; the 94 existing ones were read. Live since 2026-10-29 (D110): a card for an unsure
   "how it's sold" and a pack's price per item; the phone has the v13 build. Open: the LLM auditing System-1's sizes; then the LLM's
   new role (a capable model, run rarely, for rules, lexicon and disagreements).
3. **Going public (D105):** positioning, a new name, the portfolio entry at roqlab.com.br, Play listing, screenshots
   from made-up data, a short manual. Running costs are estimated in [costs.md](costs.md). Done: the demo
   caderneta, screenshots ([screenshots/](screenshots/)) and the brief for the roqlab entry
   ([portfolio-brief.md](portfolio-brief.md)). Still being discussed.
   Drafts of the privacy policy ([privacy-policy.md](privacy-policy.md)) and Play's Data safety answers
   ([play-data-safety.md](play-data-safety.md)); before publishing: the legal read. The site is live at
   https://caderneta.roqlab.com.br (landing page, `/privacidade/`, `/apagar-conta/`; `web/README.md` to redeploy).
4. **Cadernetas with a second real person:** only checked with a test user played through the API on the emulator.
   The first real member (a second phone) will be the first live test of invites, claims and "por".
5. **Later: reading a receipt from its photos alone (D106).** Camera and OCR rest first. A reader that makes the receipt from the saved photos, checked
   by the print's own sums, scored against the saved pages. SEFAZ stays the source until the numbers are in. The live
   capture is put down: held-out sessions (D89) and its backlog wait for a reason found by the reader.

## Open items

- **Cadernetas, not built yet (D104):**
  - Parked rows (`sync_parked`, a row the server refused) aren't shown anywhere yet; D104 says they are. They belong in
    Ajustes › Saúde dos dados, with a way to retry or drop one.
  - A caderneta joined but never opened on this phone isn't downloaded until it's opened.
  - No screen for a caderneta's setup history (`ledger_event`).
  - Leaving a caderneta (or being removed) leaves its database and receipts folder on the phone; nothing clears them.
  - LGPD: get a legal read before opening cadernetas to people outside the family (D104).
- **Photos (D103):** abandoned captures aren't kept for opted-in users; the photos uploaded before D103 stay until the
  user turns the opt-in on and off again, which takes them back too (D105). How long the phone keeps its own copies is open.
- **Discounts from photos:** confirming each value against its photo snippet, and undo back to prorated. Only a
  partial reading asks the user now (D111).
- **Stage 4 leftovers:** automatic assignments aren't re-run after a category tree change (D69).
- **Manual-chave fallback** for damaged QR codes (D3/D13): not built.
- **Size parsing gaps:** the parser still doesn't understand "1K" (= 1 kg) or "1 25KG" (= 1.25 kg). Since D108,
  System-1 reads "1K" as 1 kg; "1 25KG" comes back unsure (0.38), below the 0.5 floor for a suggestion. The two items
  that had them were fixed by hand (the user checked the yogurt's 1.25 kg on 2026-10-29).
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

Debug builds talk to the online Supabase with the URL and publishable key from `.env.local` at the repo root (a worktree falls
back to the main checkout's copy). `-Pgetnf.supabaseUrl/-Pgetnf.supabaseKey` point the emulator at a local Supabase.

## Tests

- **Android:** JVM unit tests (`testDebugUnitTest`) and instrumented tests (the focus benchmark and the real-capture
  eval, which skip without local files). **Never run connectedAndroidTest with the phone attached:** it uninstalls the
  app and wipes its receipts. On the emulator only.
- **Supabase:** `npx supabase test db` (pgTAP: isolation between cadernetas, roles, invites, leaving, the user-choice
  rule, the categorize queue). `SupabaseLocalTest` runs the phone's sync against the local Supabase when
  `GETNF_SUPABASE_LOCAL` is set.
- **Capture:** `tools/capture-bench.sh` (D89).
- **Parser:** `NfceParityTest` holds the parser to golden files first written from the old JS parser (D48); a
  deliberate parser change updates them.

## Pending, by the user

- Run `sudo bash tools/setup-system.sh` once (udev rules for the phone, Docker at boot for the local Supabase, adb and
  kvm groups).
- Back up `~/.config/getnf/` (the upload key, made 2026-10-30) and its password somewhere off this PC: without
  them the Play app can't be updated.
- The Play app (`br.com.roqlab.caderneta`, created again 2026-10-30, [play-console.md](play-console.md)) goes to Internal testing; the user starts fresh
  in it, as a new user would. The old app (`getNF`, `io.github.sandroroque.getnf`) stays installed, since its photos
  exist only on that phone, until the user decides about them. Before going public: the app content tasks, a review
  login, new screenshots, 12 testers for 14 days in closed testing.
- From another device on the LAN (the phone on Wi-Fi works with any port-check app, or a laptop with `nc -zv -w3
  192.168.4.4 54321`), confirm 54321-54324 time out, on IPv4 and the PC's IPv6 address. The firewall rule that should
  make them time out is in place (D109); the PC can't test this on itself.
- Rotate the Supabase database password: the 2026-10-27 03:30 backup failed on DNS and its traceback wrote the
  connection URL, password included, to the systemd journal. Fixed for the future (a2cb28f, `PGPASSWORD`); change the
  password in the dashboard, then in `.env.local`.

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
