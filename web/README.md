# The project's site

`caderneta.roqlab.com.br` (D105): a static Cloudflare Worker, like the roqlab portfolio. Plain HTML and CSS, no
framework, no third-party code, nothing kept in the browser.

- `/` (`index.html`): what the app is, five screenshots of the demo caderneta, how it works in three steps, what it
  does, the closed test (join the testers' Google Group, then Play's opt-in, D153), questions (`<details>`, no script) and the
  privacy points (D151). Its claims follow the Play listing (`docs/play-console.md`): change one, check the other.
- `/privacidade/`: built from `docs/privacy-policy.md` (cmark), which stays the one source; its draft note is dropped
  and its blanks filled in.
- `/apagar-conta/`: deleting an account without the app, the "delete account URL" Play asks for. Signs in with
  e-mail and password, lists the person's cadernetas, stops if they're the only owner of one that has other people
  (as the app does), and otherwise deletes their files and calls `delete_me`, exactly like Ajustes › sua conta › Apagar
  a conta (`sync/LedgerService.deleteMe`). Change one, change the other.
- `/conta-confirmada/`: where the sign-up confirmation e-mail's link lands (D120). Says whether it worked from what
  Supabase puts in the address, drops the tokens from the address at once, keeps and sends nothing. The app asks for
  it with `redirect_to`; the online project must list it in Authentication › URL Configuration › Redirect URLs.
- `/nova-senha/`: where the reset-password e-mail's link lands (D123). Reads the short-lived session from the address,
  drops it at once, and uses it only to set the new password (`PUT /auth/v1/user`); nothing kept. Needs the project's
  URL and publishable key (`config.js`, written by the build, as for `/apagar-conta/`), and the address in Redirect
  URLs.
- `assets/`: the shared stylesheet, the app's icon (`docs/brand/icon.svg`, also the favicon) and the screenshots: the
  Play ones (`docs/screenshots/play/`) without the status bar, 540 px wide. Only Início has a current dark shot.
- `_headers`: security headers the pages can't set themselves (`frame-ancestors`, HSTS).

## Build and deploy

```bash
tools/build-web.py --contact contato@roqlab.com.br     # D154; web/dist/, with the online project's URL and publishable key
cd web && npx wrangler@4.150.0 deploy            # Cloudflare; the custom domain gets its DNS record by itself
```

`web/dist/` is gitignored. `tools/build-web.py --local --contact teste@example.test` points the deletion page at the
local Supabase for testing; `.claude/launch.json` › `web-pages` serves `web/dist` on port 8099.
