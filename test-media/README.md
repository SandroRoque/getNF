# test-media

Drop real-world inputs here. **Everything in this folder except this README is gitignored.**
These are real receipts, so they contain purchase data and partial card numbers.

| Folder    | What goes in                                                      | Used for                                           |
|-----------|-------------------------------------------------------------------|----------------------------------------------------|
| `video/`  | Short phone videos panning over a receipt                          | `tools/emulator.sh --camera` → emulator back camera      |
| `photos/` | Stills of receipts (crisp, blurry, angled, bad light...)           | Camera stills, QR-decode and photo-pipeline tests |
| `pages/`  | HTML captured from the SEFAZ portal (`sc-detalhes-<chave>.html`, challenge pages) | Parser and challenge-detection tests |

Current contents (2026-10-08):

- `video/VID_20261008_211557559.mp4`: 8 s, 4K, portrait (rotation metadata). The QR code decodes on only some frames (zxing-cpp at 2 fps: 4/16).
- `photos/crisp_whole_paper.jpg`: the whole receipt in one shot. The QR code decodes.
- `photos/blurry_whole_paper.jpg`: the same receipt, blurry. The QR code does **not** decode.
- `pages/sc-detalhes-4226...8180.html`: the NFCe_Detalhes page for that receipt, fetched with plain curl (no challenge that time).
- `pages/sc-securityverify-turnstile.html`: the SEFAZ-SC `SecurityVerify.aspx` page with a Cloudflare Turnstile, returned on the next request.
