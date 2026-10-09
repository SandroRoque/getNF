#!/usr/bin/env bash
# Start the getnf AVD in the background and wait until it has booted.
#
#   tools/emulator.sh                                   # windowed, emulated camera
#   tools/emulator.sh --camera test-media/video/X.mp4   # back camera plays this video (looped)
#   tools/emulator.sh --camera test-media/photos/X.jpg  # back camera shows this still
#   tools/emulator.sh --headless ...                    # no window
#
# The camera source is fixed for the emulator's lifetime; restart to change it.
# Log: /tmp/getnf-emulator.log. Stop with: adb -e emu kill
set -euo pipefail
. "$(dirname "$0")/env.sh"

args=(-avd "$GETNF_AVD" -no-snapshot-save -no-boot-anim -netdelay none -netspeed full)
while [[ $# -gt 0 ]]; do
  case "$1" in
    --headless) args+=(-no-window -no-audio) ;;
    --camera)
      src="$(realpath "${2:?--camera needs a file}")"; shift
      case "${src,,}" in
        *.jpg|*.jpeg|*.png|*.webp)
          # Bake EXIF orientation into the pixels; the emulator ignores the tag.
          oriented="/tmp/getnf-camera-$(basename "${src%.*}").png"
          magick "$src" -auto-orient "$oriented"
          args+=(-camera-back "imagefile:$oriented") ;;
        *) args+=(-camera-back "videofile:$src") ;;
      esac ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
  shift
done

[[ -r /dev/kvm ]] || { echo "No /dev/kvm: enable SVM (AMD-V) in BIOS. See docs/dev-environment.md." >&2; exit 1; }
# A USB phone may be attached too, so only look for emulators and talk to them with -e.
adb devices | grep -q '^emulator-' && { echo "An emulator is already running; stop it first (adb -e emu kill)." >&2; exit 1; }

nohup emulator "${args[@]}" >/tmp/getnf-emulator.log 2>&1 &
pid=$!
echo "emulator pid $pid; waiting for boot..."
until adb -e get-state >/dev/null 2>&1; do
  kill -0 "$pid" 2>/dev/null || { echo "emulator exited:" >&2; tail -5 /tmp/getnf-emulator.log >&2; exit 1; }
  sleep 2
done
until [[ "$(adb -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do sleep 2; done
echo "booted: $(adb devices | grep '^emulator-')"
