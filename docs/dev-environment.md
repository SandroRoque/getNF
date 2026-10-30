# Dev environment

Host: Arch Linux (omarchy), Ryzen 5 5500, 32 GB RAM. Run `tools/doctor.sh` at any time to see what's missing.

## Status (2026-10-08, updated 2026-10-29): all green

| Item | State |
|---|---|
| JDK 21 (system); JDK 17 for Gradle (auto-downloaded) | ✅ |
| Android SDK in `~/Android/Sdk`: cmdline-tools, platform-tools, emulator, platforms 36 + 37.0, build-tools 37.0.0 | ✅ |
| System image `android-36;google_apis_playstore;x86_64`, AVD `getnf` (Pixel 8 profile, 4 GB RAM) | ✅ boots in ~1 min |
| KVM (SVM enabled in BIOS) | ✅ |
| Camera input from receipt video/photo (`--camera`) | ✅ verified in stock camera app |
| Template app builds (`./gradlew assembleDebug testDebugUnitTest`) | ✅ |
| Docker, for the local Supabase (`npx supabase start`) | ✅ |
| `.env.local` at the repo root: the online Supabase URL and keys, LM Studio's and Jev's keys | ✅ |
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
npx supabase start                      # optional: a local Supabase (Studio at http://127.0.0.1:54323)

tools/emulator.sh --camera test-media/video/VID_20261008_211557559.mp4   # or a photo; add --headless for no window

cd android && ./gradlew installDebug    # or: android run (with the phone plugged in too, Gradle installs on both)
adb -e exec-out screencap -p > /tmp/s.png  # -e = the emulator, -d = the USB phone; or: android screen
adb -e emu kill                         # stop emulator (needed to change the camera file)
```

Debug builds talk to the online Supabase. To use the local one instead, build with
`-Pgetnf.supabaseUrl=http://10.0.2.2:54321 -Pgetnf.supabaseKey=<its publishable key>`: inside the emulator, the
host machine is `10.0.2.2`.

## Local Supabase stack: keep it off the network (D109)

`npx supabase start` publishes Kong (54321), Postgres (54322), Studio (54323) and Mailpit (54324) on **all** interfaces
and has no setting to change that. Studio and the database use development passwords, so nothing here may be
reachable from the LAN. Consumers are all host-local: tools on `127.0.0.1:54322`, the emulator at `10.0.2.2:54321`,
the Android debug build, `supabase functions serve` (its container joins the stack's Docker network).

- `tools/local-stack-check.sh [--bindings]`: read-only check of API, auth, storage, DB, Studio, Mailpit and
  functions (functions are skipped unless `supabase_edge_runtime_getnf` is running), plus how each port is published.
- `sudo bash tools/harden-local-stack.sh` (applied 2026-10-25): a DOCKER-USER jump into a small chain (also persisted in `/etc/ufw/after.rules`) that drops
  new connections to ports 54320-54329 unless they arrive from loopback or a Docker bridge. No restart, no data touched.
  `--status` shows it, `--remove` rolls it back (the script keeps a timestamped `after.rules` backup).
- After applying, from **another device** on the LAN: `nc -zv -w3 192.168.4.4 54321` (also 54322-54324, and the
  host's global IPv6 address). Every port should time out. The host cannot test this on itself.
- Start and stop the stack from the main checkout (`/home/roque/Documents/Projects/getNF`), not from a worktree: the CLI
  records the directory in the container labels and bind-mounts `supabase/snippets` from it into Studio.

## Notes

- The app's files are private to it. Look at them with `adb -e shell run-as io.github.sandroroque.getnf ls -R files/receipts`.
- Debugging the in-app WebView: debug builds expose a DevTools socket. Forward it and query the live page with
  `tools/webview-eval.mjs` (usage in the file header), or open `chrome://inspect` in desktop Chrome.
- Debug builds only: change a setting with the phone locked by editing `shared_prefs/supabase.xml` (or `dev.xml`, `sefaz.xml`) through
  `adb -d shell run-as io.github.sandroroque.getnf` (force-stop the app first).
- Schema changes go in `supabase/migrations/` as new files; never edit the online schema by hand. New tables need
  explicit grants (D71, D74). Test with `npx supabase test db`.

- `sdkmanager` says it's deprecated in favour of `android sdk ...`. Both work.
- AVD files live in `~/.config/.android/avd/getnf.avd/` (XDG location, not `~/.android`).
- AVDs are in the XDG location, but the emulator only looks in `~/.android/avd`, so `env.sh` sets `ANDROID_AVD_HOME`.
- The stock camera app shows a one-time "Remember photo locations?" screen on first launch.
- Leftover from an abandoned approach (D9): the `v4l2loopback` module is installed and loads at boot as `/dev/video42`.
  It's harmless. To remove it: `sudo rm /etc/modules-load.d/v4l2loopback.conf /etc/modprobe.d/v4l2loopback.conf && sudo pacman -Rns v4l2loopback-dkms`.
