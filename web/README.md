# Web pages

Static pages that live outside the app (D105). Plain HTML, CSS and JavaScript: no framework, no build step beyond
filling in the project's address, no third-party scripts, nothing kept in the browser.

- `apagar-conta/`: deleting an account without the app, the "delete account URL" Play asks for. Signs in with
  e-mail and password, lists the person's cadernetas, stops if they're the only owner of one that has other people
  (as the app does), and otherwise deletes their files and calls `delete_me`, exactly like Ajustes › Conta › Apagar
  minha conta (`sync/LedgerService.deleteMe`). Change one, change the other.

## Build and publish

```bash
tools/build-web.py --contact <e-mail> --privacy <privacy policy URL>
```

writes `web/dist/` (gitignored) with the online project's URL and publishable key from `server/.env.local`. Upload
`web/dist/` to any static host; `apagar-conta/` then works at `<host>/apagar-conta/`. `--local` builds against the
local Supabase for testing (`.claude/launch.json` › `web-pages` serves `web/dist` on port 8099).

The host should also send `Content-Security-Policy: frame-ancestors 'none'` (it can't be set from the page) and serve
over HTTPS. The page's own policy only lets it talk to the Supabase project.
