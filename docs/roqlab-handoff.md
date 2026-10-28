# Handoff: update the getNF entry on roqlab.com.br

Paste this into a session started in `/home/roque/Documents/Projects/roqlab`.

---

Update the getNF entry of the portfolio (`index.html`, `#getnf`; `app.js` for its demo; `assets/`; the README's
"Origem das imagens"). The current entry describes a version of getNF that no longer exists: its screenshot is the
retired PocketBase web dashboard, it says extraction happens on the server, and its stack line is out of date. The
product is now an Android app.

**Read first**, in the getNF repo at `/home/roque/Documents/Projects/getNF` (branch `main`, also on GitHub at
SandroRoque/getNF):

- `docs/portfolio-brief.md`: what's wrong with the current entry, suggested Portuguese copy, alt text per
  screenshot, the demo's numbers for an interactive section, and what the entry must not claim.
- `docs/screenshots/`: 1080×2400 WebP screenshots of the real app with a made-up demo caderneta (7 light, 3 dark).
  The README there says how they were made.

**Keep:**

- The heading **getNF** and the status "Em desenvolvimento" / "Publicação em breve". The app will be renamed, but
  the name isn't chosen; don't use any other name.
- No link to the getNF code repository.
- The site's structure and conventions (README "Manutenção"): no build step, no production dependencies, values in
  integer cents in `app.js`, the no-JavaScript version in the HTML matching the initial demo state, captions saying
  "Interface real · dados fictícios".
- Only invented data. Use the numbers in the brief; never anything from real receipts.

**To decide in the work, and show the user before finishing:**

- How to fit portrait phone screenshots where the figure is now a landscape window frame (a phone frame, or two
  shots side by side). Copy the WebPs you use into `assets/` (resize/compress to the size the layout needs).
- The interactive demo: the brief suggests the IPCI ("Sua inflação, não a média"). Its monthly series dips in June
  and August (store discounts); the coffee price series only rises and may read better. Pick one, or keep a
  month × category demo with the new numbers.

**Check:** `node tests/browser.cjs` (update its expected totals and texts for the new demo), and look at the result
at 360, 768 and 1440 px in light and dark. Update the README's "Origem das imagens" (the getNF images now come from
the Android app on the emulator, demo caderneta; the old note that no emulator was available no longer applies).

**Careful:** `README.md` and `wrangler.jsonc` had uncommitted changes on 2026-10-28 that aren't part of this
work; look at them before editing and don't discard them. Don't deploy or change DNS; commit only when the user asks.
