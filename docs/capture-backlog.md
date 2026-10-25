# Capture: ideas waiting their turn

Each idea goes through the benchmark loop in [capture-bench.md](capture-bench.md) when it's picked up: one at a time,
kept only without regressions.

- **Guide toward the most unseen content.** The hint now points to the nearest row not captured. Instead, point to
  where a frame would add the most: the stretch with the most rows not captured, or the end not seen yet, weighed by
  distance. Each clean frame should read as many rows as it can (the user's suggestion, 2026-10-25).
- **Torch by itself.** Turn the LED on when the paper is dark: when the paper level the finder measures, or OCR
  confidence, stays low for about a second. Turn it off on glare (a blown-out patch on the paper). The user's toggle
  overrides. The bench can't judge this; it needs phone sessions in dim light.
- **Session time on the phone.** About 330 ms a frame on the Motorola (OCR is about 500 ms, in parallel since D88). The
  paper finder walks the full frame; the unread-print check samples every gap. Profile before changing.
- **Rows on curled receipts.** OCR boxes are grouped into rows by their position down the print. On a curled, tilted
  receipt, a short box far from a long line's centre (a price, an item's number) can land a row off. Grouping along
  each line's own axis was tried and dropped: 5 regressions. Another try: let the assembly match a row read whole
  against the same row read in pieces.
- **More than one stretch at a time.** A frame that doesn't overlap what's assembled (the user jumped from the bottom to
  the top) is lost until a frame joins them. Keep separate stretches and join them when a frame overlaps both.
- **Full-resolution photos.** Saved photos are analysis frames (1440×1920). A still from ImageCapture at the moment of
  commit would give the OCR afterwards more to work with.
- **Answer key for the receipt without a page**: one of the three receipts in the corpus has no saved page was never fetched, so its sessions are
  scored against their own reads (`*`). Fetching it is one SEFAZ lookup, only with the user's go-ahead.
- **Footer line at the bottom edge.** The software line under the last row is required like any row. A
  session that never shows it again stays incomplete. Decide whether it matters.
