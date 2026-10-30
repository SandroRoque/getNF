# Screenshots

The app on the emulator (1080×2400) with the made-up demo caderneta: four invented stores in Florianópolis, May to
October 2026, 86 receipts, 47 products. No real receipt, store, chave or brand. Taken 2026-10-28; see
[portfolio-brief.md](../portfolio-brief.md) for what each shows.

To take them again after a UI change:

1. Write the demo backup (seeded, the same every run):
   `cd android && GETNF_DEMO_OUT=/tmp/caderneta-demo.zip ./gradlew :app:testDebugUnitTest --tests '*DemoCaderneta*' --rerun`
2. On the **emulator, signed out** (so the demo never syncs anywhere): `adb push /tmp/caderneta-demo.zip
   /sdcard/Download/`, then Ajustes › Cópia de segurança › Restaurar and pick it.
3. A clean status bar: `adb shell settings put global sysui_demo_allowed 1`, then
   `adb shell am broadcast -a com.android.systemui.demo -e command enter` and `... -e command clock -e hhmm 0930`.
   Dark mode: `adb shell cmd uimode night yes`.
4. `adb exec-out screencap -p > name.png`, then `magick name.png -quality 90 name.webp`.

## For Play (`play/`)

Taken 2026-10-30 after D112–D114, in upload order: Início, Gastos, a product (Café Montanha), the IPCI, Para você,
a receipt, the Catálogo, Início in dark mode. PNG at **1080×2160**: Play refuses a side more than twice the other, and
the emulator's 1080×2400 is 2.22:1, so the screen is set to 2:1 while taking them (the app lays itself out at that
size; nothing is cropped). Same steps as above, plus:

- `adb -e shell wm size 1080x2160` before, `adb -e shell wm size reset` after.
- A clean status bar also needs `-e command network -e wifi show -e level 4 -e fully true` (or the Wi-Fi icon shows
  "!" on the emulator) and `-e command notifications -e visible false`.
- Keep the files as PNG (Play takes PNG or JPEG, not WebP). The WebP ones above are from 2026-10-28 and still show
  the "JEV" marks and the old account button.
