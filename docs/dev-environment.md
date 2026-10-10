# Dev environment

Host: Arch Linux (omarchy), Ryzen 5 5500, 32 GB RAM. Run `tools/doctor.sh` at any time to see what's missing.

## Status (2026-10-08): all green

| Item | State |
|---|---|
| JDK 21 (system); JDK 17 for Gradle (auto-downloaded) | ✅ |
| Android SDK in `~/Android/Sdk`: cmdline-tools, platform-tools, emulator, platforms 36 + 37.0, build-tools 37.0.0 | ✅ |
| System image `android-36;google_apis_playstore;x86_64`, AVD `getnf` (Pixel 8 profile, 4 GB RAM) | ✅ boots in ~1 min |
| KVM (SVM enabled in BIOS) | ✅ |
| Camera input from receipt video/photo (`--camera`) | ✅ verified in stock camera app |
| Template app builds (`./gradlew assembleDebug testDebugUnitTest`) | ✅ |
| PocketBase in Docker on `127.0.0.1:8090`, dev superuser in `server/.env.local` | ✅ |
| `android-udev` + `adbusers` group (for the real phone) | ✅ |
| Real phone over USB: Motorola Edge 50 Pro, Android 16 (API 36), arm64, 1220×2712 @ 450 dpi, WebView 154 | ✅ `adb devices` |
| Android skills for Claude in `.claude/skills/` | ✅ |

## One-time setup (done)

1. SVM Mode enabled in BIOS (it was off: `kernel: SVM disabled (by BIOS) in MSR_VM_CR`).
2. `sudo pacman -Syu`, then `sudo bash tools/setup-system.sh`. If pacman 404s on a package, the sync DB was stale and `-Syu` fixes it.
3. Phone: Settings → About phone, tap *Build number* 7×, then turn on *USB debugging*. It's easiest to find
   with the Settings search bar, since it's far down in Developer options. Plug in, accept the "Allow USB debugging?"
   prompt ("always allow") and check with `adb devices`. Motorola's "Diagnosis mode" toggle is not needed.
   With both the phone and the emulator connected, target one with `adb -s <serial>` (or `-d` for USB, `-e` for emulator).

## Daily use

```bash
. tools/env.sh                          # adds adb/emulator/sdkmanager/android to PATH, sets ANDROID_AVD_HOME
docker compose -f server/compose.yaml up -d     # PocketBase (http://127.0.0.1:8090/_/)

tools/emulator.sh --camera test-media/video/VID_20261008_211557559.mp4   # or a photo; add --headless for no window

cd android && ./gradlew installDebug    # or: android run (with the phone plugged in too, Gradle installs on both)
adb -e exec-out screencap -p > /tmp/s.png  # -e = the emulator, -d = the USB phone; or: android screen
adb -e emu kill                         # stop emulator (needed to change the camera file)
```

Inside the emulator, the host machine is `10.0.2.2` (so PocketBase is `http://10.0.2.2:8090`).

## Notes

- The app's files are private to it. Look at them with `adb -e shell run-as io.github.sandroroque.getnf ls -R files/receipts`.
- Debugging the in-app WebView: debug builds expose a DevTools socket. Forward it and query the live page with
  `tools/webview-eval.mjs` (usage in the file header), or open `chrome://inspect` in desktop Chrome.
- Real phone: on home Wi‑Fi it uses `http://192.168.4.4:8090` (D18). PocketBase listens there because `server/.env`
  (not in git) sets `GETNF_LAN_IP`. If the PC's address changes, update that file and the app's Settings.
  Away from home without Wi‑Fi, the USB tunnel still works: `adb -d reverse tcp:8090 tcp:8090` and set the URL to
  `http://127.0.0.1:8090` (lost on unplug).
- Debug builds only: change a setting with the phone locked by editing `shared_prefs/server.xml` through
  `adb -d shell run-as io.github.sandroroque.getnf` (force-stop the app first).
- Schema changes made in the PocketBase admin UI (or via the collections API) are written out as new files in
  `server/pb_migrations/` automatically. Commit them if the change is meant to stay. They're root-owned (written by the
  container), so delete unwanted ones with `docker compose -f server/compose.yaml exec pocketbase rm /pb/pb_migrations/<file>`.
  Editing *data* (e.g. assigning categories to products) doesn't create migrations.
- Uploaded receipts: PocketBase admin UI → `receipts` collection. The app logs in as `phone@getnf.local` (`DEV_APP_*` in `server/.env.local`).

- `sdkmanager` says it's deprecated in favour of `android sdk ...`. Both work.
- AVD files live in `~/.config/.android/avd/getnf.avd/` (XDG location, not `~/.android`).
- AVDs are in the XDG location, but the emulator only looks in `~/.android/avd`, so `env.sh` sets `ANDROID_AVD_HOME`.
- The stock camera app shows a one-time "Remember photo locations?" screen on first launch.
- Leftover from an abandoned approach (D9): the `v4l2loopback` module is installed and loads at boot as `/dev/video42`.
  It's harmless. To remove it: `sudo rm /etc/modules-load.d/v4l2loopback.conf /etc/modprobe.d/v4l2loopback.conf && sudo pacman -Rns v4l2loopback-dkms`.
