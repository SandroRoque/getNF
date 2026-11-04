# Status and next steps

Last updated 2026-11-04. A handoff for picking the project up again. For *why* things are the way they are, read
`docs/decisions.md` (D1–D150). For the portal's behaviour, read `docs/sefaz-sc.md`. For the data flow and analytical
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
- **First start (D115):** a three-page welcome, a how-to before the first scan, "Primeiros passos" on Início until the
  first page is fetched (D116), and a read-only demo caderneta (bundled, never synced) one tap away.
- **Ajustes (D123):** an index. The account in a card at the top (what an account gives, or who and when it last
  synced), then rows that open their own screens: the open caderneta and Cadernetas, Cópia de segurança, the photo
  switch, privacy. Entrar / Criar conta is its own screen ("Confira seu e-mail" after a sign-up, "Esqueci a senha" by
  e-mail to `/nova-senha/` on the site); the account screen signs out and deletes. Servidor, Saúde dos dados and
  Testando a captura are under Desenvolvimento, in debug builds only.
- **After a scan (D99–D101):** "Nota salva" offers the page at once or reminds 3 h later; a receipt waiting for its
  page has its own screen; once fetched, the receipt opens and its photos' discounts are read by themselves.
- **Photos (D98, D103):** stay on the phone. Copies go online only if the user opts in, and then only for receipts the
  app failed with, with the card slip, any CPF and the operator blanked.
- **Cadernetas (D104):** a person can own several cadernetas and be in others; everyone in one sees everything. Each
  has its own database and receipts folder on the phone; opening another restarts the app. Ajustes › Cadernetas
  creates, joins by code, invites, changes roles, copies receipts to another caderneta and leaves; Entrar /
  Criar conta makes a login and the account screen deletes it. Other cadernetas this phone has files for sync in the background; receipts another member
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

## Next: milestone 0.1, the closed test (set 2026-10-31)

Google lets a new personal account apply for production only after a closed test: at least 12 testers, each opted
in continuously for the 14 days before applying (per tester: one who leaves and rejoins starts over; new versions
during the test don't restart it; Google then decides, see play-console.md). The user wants it
under way within 7 days, so 0.1 is the build that opens the closed test, Santa Catarina only, with the name
Caderneta. New ideas go to "After the open store", not into the code. Exception, asked by the user on 2026-11-02:
**Rio de Janeiro's receipts are read too (D155)**, so testers there capture real receipts; 0.1.0 is built from a
commit that has it. The site's copy changed with it: **the user redeploys the site** (`web/README.md`), and the Play
listing's description (play-console.md) says SC and RJ.

**Handoff, 2026-11-04 (PC session)**

Where things are:
- **Testers have 0.1.0 (code 3, `play/3`)**; **0.1.1 (code 4, `play/4`, 2c09f55) was approved on the closed track on
  2026-11-03** (Publishing overview, checked 2026-11-04) and is what a tester installs now. Its manifest stays
  `submitted` until an account in the closed test installs it from Play (releases/4.md).
- **D164 is on main (e82f30e), not on any track:** sync pages by key (T-1), catalog rows merge by field (T-2), removed
  lines reach every phone (T-3); the three tickets are resolved. Room 13 → 14, no server change. It's the reason for
  the next candidate (code 5): shared cadernetas lose or keep wrong data without it.
- What testers send lands in the `contributions` bucket and the `contribution_file` table (secret key only):
  `tools/contributions.py list` shows it per install and item, `fetch` downloads into `test-media/contributions/`
  (D163). Nothing had arrived by 2026-11-04.
- The Play Developer API answers (D159, `tools/play.py`); uploads and track changes go through it. Data safety and the
  listing are edited in the console (the built-in browser is signed in to it).

Do next, in order:
1. **The phone on code 4:** the user's phone updates from code 3 to 4 through Play (a sideload can't replace a Play
   install, D162); with it on the cable, check that October still shows R$ 821,85 in 5 receipts, 7 decisions, the
   account, and that the photos question appears. Record it in releases/4.md and set `state: closed`.
2. **Code 5 (0.1.2, 3cf6610, `play/5`), with D164:** in Google's review on the closed track since 2026-11-04 (uploaded
   straight there), live on internal. Watch the review (Publishing overview) and whether this upload got a pre-launch
   report. Then the user's phone updates through Play (D162; the user will plug it in later) and releases/5.md gets
   its phone check. Code 4's phone check is folded into it.
3. ~~T-4~~ discarded by D165: corrections follow a SEFAZ line's number; orphans wait for the photo reader.
4. **Watching** (rollout.md › Watching): each session, `tools/tickets.py inbox`, `tools/contributions.py list --days 1`, the
   sync and refused-rows reports, Vitals; findings become tickets.
5. **Recruiting, by the user:** the site's "Teste fechado" section; aim for more than 12.
6. **The photo reader:** a run on held-out sessions. The user keeps every paper receipt and re-scans on request:
   ask for a few captures by date and store, with the debug build dumping and the phone on the cable.
7. **The next candidate after that:** a weekly opportunity; `tools/release.py pending` shows what testers don't have.

Not done, waiting: dark screenshots for the sites (only Início has a current dark shot; the rest show light on both
sites, D151), and the steps under "The user" below.

**Next 7 days: open the closed test**

Claude:
1. ~~Parked sync rows shown in Ajustes~~ done (D145: "Não sincronizado", Tentar de novo, Enviar relatório).
2. ~~"Enviar comentário" in Ajustes~~ done (D146); since D152 it and the crash and refused-rows reports go to a
   private inbox on the server, triaged into tickets with `tools/tickets.py`. Online since 2026-11-01; the privacy page with its section is live. "Prefiro mandar por e-mail" writes to contato@roqlab.com.br
   (D154), or opens the share sheet on a phone without an e-mail app.
3. ~~The Play listing in pt-BR~~ done 2026-10-31: texts, release notes and review instructions in
   [play-console.md](play-console.md), screenshots retaken ([screenshots/play/](screenshots/play/)), the feature
   graphic kept. Data safety changed with D152 (reports reach the server: [play-data-safety.md](play-data-safety.md)).
   The contact e-mail is contato@roqlab.com.br (D154).
4. Fixes from the user's review of the screens and from the shared caderneta with the father: these come first.
   Done from the first review: one purchase per receipt and the unit price first (D141), tappable things look
   tappable and full-width swiping tabs (D142), chart scrubbing (D143), the package's price on the chart (D144).
   From the capture discussion (D149): a provisional attempt drops after 2.5 s without the paper (R1, R2, done);
   "Recomeçar" on the capture screen (R4, D150, done); next R3 (an offer when stuck after the QR code: worn print or
   no progress), which needs a few captures of a worn receipt to be measured.
5. 0.1.0 (code 3): the release checklist (CI ✓, `tools/check.sh`, the release build on the emulator and the phone,
   tag `play/3`, Android vitals three days later).
6. ~~Start the photo reader's measurement~~ started (D147, D148): `PhotoReaderBenchTest`, now 455 of 499 items
   exact, 11 of 29 sessions read exactly and trusted, no trusted reading wrong. Nothing in the app yet. Next for it:
   a run on the holdout sessions (totals only, as D89) to see whether it generalises beyond the 10 receipts it was
   tuned on; then measure on the second OCR pass (the saved JPEGs, as the app would read them) instead of the live
   frames' OCR the dumps keep.
7. Install the current build on the user's phone: it has everything up to D144; D145–D152 (refused rows, Enviar
   comentário, the capture's R1/R2/R4, reports to the server) aren't on it yet.

The user:
1. Review the screens on the phone (the redesign, D81) and pass on what bothers.
2. A shared caderneta with the father: invite, join, both scan, check "por" (code 2 already has it). The first real
   test of cadernetas with a second person.
3. Recruit 15–20 testers, from SC if possible: the critical item. They join themselves (D153): the site's
   "Teste fechado" section, the group testadores-da-caderneta, then Play's opt-in link.
4. Play Console: the listing, the app content tasks, a closed testing track (Brazil, testers: the Google Group
   `testadores-da-caderneta@googlegroups.com`, D153), the upload.
5. Rotate the database password; back up the upload key (see "Pending, by the user").
6. ~~Pick the public contact address~~ contato@roqlab.com.br (D154): routing to Gmail works (tested
   2026-11-01) and the site carries it. Gmail sends as it through Resend's SMTP, and
   Play Console's contact uses it. Done.
6b. ~~`npx supabase db push` for D152's tables~~ done 2026-11-01; next, Play Console › Data safety updated as [play-data-safety.md](play-data-safety.md) says, before the 0.1.0 upload.
7. Decide on the legal read: suggested, the closed test with the published privacy policy, the legal read before the
   open store.
8. ~~RJ receipts~~ read since D155 (videos received 2026-11-02, in `test-media/video-rj/`, WhatsApp-compressed); RJ
   captures made with the app, phone connected, would still help the photo reader.
9. Two or three captures of a worn (faded) receipt, phone connected: R3 (the offer when the capture is stuck after
   the QR code) can't be measured without them.

**During the 14 days (0.1.x updates)**
- Reports and tickets (D152): `python3 tools/tickets.py inbox` at the start of each session, triage, then
  `tools/tickets.py summary` here. Play's private feedback and Android vitals come in by hand (the user pastes them,
  or Claude reads the Console) and become tickets too.
- Fixes from the testers and Android vitals, first.
- The photo reader (D106): photos → the same receipt the page parser makes, checked by the print's own arithmetic,
  scored against the saved SC pages. The printed DANFE NFC-e follows a national layout, so the reader is also the way
  to other states: QR codes of other states accepted, then RJ once the reader is good. Whether SEFAZ becomes optional
  is decided by the reader's numbers.
- Reconsider the development Supabase (see "Pending, by the user").
- Remove the 93 MB benchmark APK from git history (b821915), which needs a force-push: only with the user's go-ahead.

**After the open store (frozen until then)**
- System-1 / LLM: the LLM auditing System-1's sizes (D108, D110); then the LLM's new role (a capable model, run
  rarely, for rules, lexicon and disagreements).
- Going public beyond the closed test (D105): positioning, the final name, the portfolio entry at roqlab.com.br
  ([portfolio-brief.md](portfolio-brief.md); the entry was renamed and refreshed in D151), a short manual. Running costs in [costs.md](costs.md). The site is live
  at https://caderneta.roqlab.com.br (landing page, `/privacidade/`, `/apagar-conta/`; `web/README.md` to redeploy).
- Products: stores that print their own code instead of the GTIN can't be found by barcode
  ([camera-plan.md](camera-plan.md), parked: deterministic first); items without a barcode (scale labels) recognised
  by machine vision, to discuss.
- The capture backlog ([capture-backlog.md](capture-backlog.md)).
- The open items below.

How the capture behaves, as a state machine: [capture-fsm.md](capture-fsm.md); the idealised redesign that was
discussed and set aside for four rules: [capture-fsm-ideal.md](capture-fsm-ideal.md) (D149).

Both sites refreshed (D151, 2026-11-01): roqlab's entry is Caderneta with current screens and a link; the site has
how it works, questions and a closed-test section. Deployed 2026-11-01. Testers join a Google Group instead of
e-mailing (D153).

Done before the milestone: the camera plan (D129–D138: a product's barcode looked up in every caderneta; one camera
for receipts and products), CI on GitHub and the crash log (D140), Play upload tags (D139), the workflow review of
2026-10-31 (merged branches and stale worktrees removed, one branch per task in CLAUDE.md, the development Supabase
left for later, the history rewrite awaiting a go-ahead).

## Open items

- **Cadernetas, not built yet (D104):**
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
Testing something that needs an account (D145's card) is done that way, against the local Supabase with a user made
through its admin API (`SupabaseLocalTest.newUser` shows how), never on the online project. As of 2026-10-31 the
emulator's debug build is the plain one again (online project, signed out, the demo caderneta restored for the
screenshots).

## Tests

- **Android:** JVM unit tests (`testDebugUnitTest`) and instrumented tests (the focus benchmark and the real-capture
  eval, which skip without local files). **Never run connectedAndroidTest with the phone attached:** it uninstalls the
  app and wipes its receipts. On the emulator only.
- **Supabase:** `npx supabase test db` (pgTAP: isolation between cadernetas, roles, invites, leaving, the user-choice
  rule, the categorize queue). `SupabaseLocalTest` runs the phone's sync against the local Supabase when
  `GETNF_SUPABASE_LOCAL` is set.
- **Photo reader:** `PhotoReaderBenchTest` with GETNF_BENCH (D147), after a capture benchmark run (it reads the
  frames each session committed); `PhotoReceiptReaderTest` runs in CI on made-up OCR.
- **Capture:** `tools/capture-bench.sh` (D89). Frame timings per branch: `tools/capture-timing.py` over the dumps'
  `timing.tsv` (D129). On the phone, before a release: `tools/device-bench.sh` (installs and removes only its own
  test APK, D129).
- **Before every push:** `tools/check.sh` runs from the pre-push hook (build, JVM tests, capture benchmark; D129).
- **On GitHub:** every push to main runs `tools/check.sh --quick` (`.github/workflows/check.yml`, D140); the result is
  the ✓/✗ next to each commit.
- **Crashes:** the app writes each crash to `files/crashes/` and offers it on Início at the next start (D140). Play
  Console › Android vitals has the crashes and ANRs of Play installs: look there after each release.
- **Parser:** `NfceParityTest` holds the parser to golden files first written from the old JS parser (D48); a
  deliberate parser change updates them.

## Pending, by the user

- Run `sudo bash tools/setup-system.sh` once (udev rules for the phone, Docker at boot for the local Supabase, adb and
  kvm groups).
- Back up `~/.config/getnf/` (the upload key, made 2026-10-30) and its password somewhere off this PC: without
  them the Play app can't be updated.
- The Play app (`br.com.roqlab.caderneta`, created again 2026-10-30, [play-console.md](play-console.md)) was in Internal
  testing: 0.0.1 (code 1, tag `play/1`) and code 2 (shows "1.1", D119–D128, tag `play/2`; D139), published 2026-10-30 22:xx and still reaching the
  phone. Code 3 (0.1.0, tag `play/3`) replaced it on 2026-11-02 ([releases/3.md](releases/3.md)). The user's father is to create an
  account from the Play app once code 2 reaches him; none existed at 22:25. Optional: a service account for
  publishing from the command line (play-console.md would need a `tools/play-publish.py`; not made). The old app (`getNF`, `io.github.sandroroque.getnf`) is to be
  uninstalled (decided 2026-11-03): its receipts are in the account, most photos online, and the user can re-scan any
  paper receipt. Before going public: the app content tasks, a review
  login, new screenshots, 12 testers each opted in for 14 days in a row in closed testing.
- From another device on the LAN (the phone on Wi-Fi works with any port-check app, or a laptop with `nc -zv -w3
  192.168.4.4 54321`), confirm 54321-54324 time out, on IPv4 and the PC's IPv6 address. The firewall rule that should
  make them time out is in place (D109); the PC can't test this on itself.
- A second Supabase project for development, so debug builds and tests stop using the database the Play app uses
  (the user agrees it's needed; not now). Then debug builds point at it and migrations go there first.
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
