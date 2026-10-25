# Capture coverage plan (D82)

Goal: capture a whole NFC-e with the fewest photos that make every printed line readable, decided live, and show
the person a map of the receipt saying which height bands are captured, need a closer look, or are unseen.

## The model

- **Paper:** a quadrilateral per frame (the existing mask and polygon, D39–D41). Its width at each row is the local
  horizontal scale.
- **Print lines:** rows of ink inside the paper, found from the row profile of dark pixels. Thermal printers use a
  fixed font and line pitch, so detected lines are evenly spaced except for the logo, the QR code and blank gaps.
- **Receipt coordinate `u`:** distance from the paper's top edge in print lines, `u(y) = ∫ dy / pitch(y)`, with
  `pitch(y)` fitted over the frame's lines (linear in `y` under perspective). A frame that sees the bottom edge but
  not the top is placed from the bottom once the total length is known. A frame with neither end needs content
  registration (later step).
- **Accepted stretch (D83):** the rows where both sides of the paper are in view, accepted as a whole where the print
  is in focus and large enough. Focus and scale come from the lines' height, edge width (blur), contrast and glare,
  judged in zones of about ±3 print lines, so single odd lines don't punch holes. Thresholds calibrated on real
  frames, cross-checked with OCR.
- **Coverage map:** along the receipt at one print line's resolution, the best frame and its state: captured, needs
  a closer look, unseen. Drawn as solid stretches.

## Steps

1. **Offline replay** (JVM, pure Kotlin so the same code runs live): read a pulled session (JPEGs, masks, segment
   JSON from the app database), detect lines per frame, place them, build the map, pick the minimal set of frames
   that covers every line, and render the map as an image. Local only, `GETNF_CAPTURE_REPLAY=<dir>`; synthetic
   fixtures for the committed tests.
2. **Calibrate the bar** on the replayed sessions (OCR of line crops as a reference).
3. **Live:** run the line model on analysis frames (downscaled), keep the map in the capture session, and save only
   frames that capture new lines. Render the map on the capture screen.
4. **Full-resolution stills** with `ImageCapture` when the view would capture new lines; the stills are what get
   saved.
5. **Content registration** for views that see neither end, and long receipts on the phone.
6. **The map on the receipt's screen**, from the saved photos.

## Progress

- 2026-10-25: D82 recorded. Reference session: 26 frames of a short receipt; frame 1 (6 s) alone was
  sufficient, frame 0 missed the last four footer lines.
- 2026-10-25: Step 1, first cut. `PrintLines` (plain Kotlin) finds the paper's print lines per frame: ink against a
  local paper level, deskew by shear search, runs split at valleys only when about two local periods tall (the period
  from the ink profile's autocorrelation), a pitch fitted against row. `CoverageMap` places frames in print lines from
  the paper's top edge (or bottom, once the length is known), keeps each line's state and picks the fewest frames.
  `CaptureReplayTest` (`GETNF_CAPTURE_REPLAY=<dir>`) draws an overlay per frame and the map; `PrintLinesTest` covers
  a synthetic receipt (pitch, length in lines at two scales, blur, one sufficient view, two close-ups placed by an
  overview).
  On the reference session: length 80.8 print lines, width 31.4; frame 1 alone captures all 81 cells, and the
  replay picks only frame 1. Frame 13's out-of-focus lower lines are flagged unreadable, as they are.
  Not yet handled: 9 of 26 frames are skipped because the paper leans 18–31° (tilted close-ups and the shot at 30 s),
  and 7 are not placed because neither end is in view. Next: rectify each frame's paper quad to an upright strip before
  reading lines; then content registration (match line sequences between frames); then calibrate the bar.
- 2026-10-25: D83: stretches are accepted as a whole (zones of ±3 print lines), not line by line; the map and the
  frame overlays show solid stretches. The reference session still resolves to frame 1 alone.
- 2026-10-25: Step 1, straightening. Each frame's paper outline is mapped onto an upright rectangle (a homography)
  before reading; readability uses the line's height in camera pixels, so the stretched far end of a tilted view
  isn't overrated. An outline edge along the frame's border is a cut, not the paper's end; an edge where the paper's
  sides carry on past it is the mask stopping at a shadow (D41), not the end either. Blur takes the worse of the
  edges across and along the line, which catches motion blur. The pitch comes from the ink profile's periodicity in
  windows (no need to separate lines), with spacing between lines as the fallback.
  Reference session: 19 of 26 frames read and placed (was 10); the tilted shot at 30 s and two smeared close-ups
  are now judged unreadable, as they are. Length 84.5 print lines; Tesseract's line positions on frame 1 suggest
  about 82, and a line seen at the edge of two different views is placed within 1.5 lines in both. Still frame 1
  alone. Not placed: 6 frames that show neither end (content registration), 1 with too little regular print.
- 2026-10-25: Step 2, replay of the other 9 sessions on the phone (36 frames: 8 sessions of 2026-10-15 on one
  pharmacy receipt, over a red patterned board and in shadow; one of 2026-10-24 on the reference receipt).
  2026-10-24: length 82.6 print lines (84.5 from the reference session), consistent. 2026-10-15: the same receipt
  measures 20 to 82 lines from session to session. The cause is the paper outline recorded with each photo (the
  capture's mask, D39–D41), not the line model: on several frames it outlines the red board instead of the receipt,
  stops at a shadow without the paper's sides showing past it, or covers a sideways receipt without its orientation.
  Length, placement and readability all inherit that. Calibrating the bar on these frames isn't meaningful until the
  outline is right.
  Next: let the print find the paper. The print block's rows give the receipt's orientation (the ink profile is
  periodic along the receipt, not across it), and its extent and the paper's brightness around it bound the outline;
  the capture's outline becomes a hint to check rather than the truth.

