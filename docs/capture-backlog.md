# Capture: ideas waiting their turn

Done and waiting for a live check: guidance toward the most unseen content (D92); the torch by itself (D93).

Each idea goes through the benchmark loop in [capture-bench.md](capture-bench.md) when it's picked up: one at a time,
kept only without regressions.

- **Torch off on glare.** D93 turns the torch on in the dark and never off. If glare on the paper turns out to hurt
  (a blown-out patch where print was readable), turn it off again, or don't turn it on for glossy paper.
- **Session time on the phone.** D91 halved the paper finder (PC: 30 → 17 ms a frame in all). Next: the working
  copy (about 3.5 ms a frame on the PC) and `required()`, which the decisions recompute several times a frame.
- **Rows on curled receipts.** OCR boxes are grouped into rows by their position down the print. On a curled, tilted
  receipt, a short box far from a long line's centre (a price, an item's number) can land a row off. Grouping along
  each line's own axis was tried and dropped: 5 regressions. Another try: let the assembly match a row read whole
  against the same row read in pieces.
- **More than one stretch at a time.** A frame that doesn't overlap what's assembled (the user jumped from the bottom to
  the top) is lost until a frame joins them. Keep separate stretches and join them when a frame overlaps both. Measured
  2026-10-26: only one dev session loses frames this way (10 frames, about 7 s); worth it when more sessions do.
- **Full-resolution photos.** Saved photos are analysis frames (1440×1920). A still from ImageCapture at the moment of
  commit would give the OCR afterwards more to work with.
- **Answer key for the receipt without a page.** One of the three receipts in the corpus was never fetched, so its
  sessions are scored against their own reads (`*`). Fetching it is one SEFAZ lookup, only with the user's go-ahead.
- **Footer line at the bottom edge.** The software line under the last row is required like any row. A
  session that never shows it again stays incomplete. Decide whether it matters.
- **Score the QR code.** Since D94 `live.tsv` logs the codes read per frame and how many inside the paper (since D95
  with the right corners). The benchmark could take a session as done only from the first frame with the code read
  inside the paper as well as all rows.
- **A digit misread at a confident score.** One session (2026-10-26T21-19-03) completes with an item whose only saved
  read has its code one digit off ("7360" for "7380", row confidence 0.74), while earlier frames read it right at
  0.81–0.86 and were replaced as the held frame by one reading more rows. Tried and dropped (each broke more sessions
  than it fixed): requiring the surest read's digits; 0.8 for rows with codes; saving the held frame when it read rows
  clearly better; requiring the digits most frames agree on. Ideas left: score the confidence of the digits' own
  boxes (OCR's elements) rather than the row's; or, at the end, check each kept row's digits against the reads of all
  frames and keep a second photo only for rows whose kept read disagrees.
- **Photos for improvement (D103).** Missing: (1) turning the setting off deletes what was sent (sync needs a storage
  delete; the photos already online fall under the same question); (2) keep an abandoned capture's photos for users
  who opted in, since those are failures too; (3) the small sample of receipts the app read fine, and how to pick it.
