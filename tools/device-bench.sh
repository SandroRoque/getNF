#!/usr/bin/env bash
# The on-device frame analysis benchmark (camera plan, step 0): times OCR and the code scanners over recorded capture
# frames, on a real phone, before each release. Installs only its own test APK (br.com.roqlab.caderneta.benchmark.test),
# pushes the frames to that app's files, runs it, and uninstalls it again: the app and its receipts are never touched.
# Results go to test-media/bench/device/<time>-<model>.json and a summary is printed. No network.
#   tools/device-bench.sh [serial]            (default: the only device attached)
#   GETNF_DEVICE_FRAMES=48                     how many frames, spread over the recorded phone sessions
# On an emulator it runs (to check the plumbing), but its timings mean nothing.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
media="$(git -C "$root" rev-parse --path-format=absolute --git-common-dir | xargs dirname)/test-media"
. "$root/tools/env.sh" >/dev/null
adb=(adb); [ $# -gt 0 ] && adb=(adb -s "$1")
pkg=br.com.roqlab.caderneta.benchmark.test
apk="$root/android/benchmark/build/outputs/apk/androidTest/release/benchmark-release-androidTest.apk"
count="${GETNF_DEVICE_FRAMES:-48}"

(cd "$root/android" && ./gradlew -q :benchmark:assembleReleaseAndroidTest)

# Every sixth frame of each recorded phone session, until there are enough: paper, text, codes and empty table alike.
mapfile -t frames < <(for d in "$media"/live-dumps/phone/*/; do ls "$d"*.jpg 2>/dev/null | awk 'NR % 6 == 1'; done | head -n "$count")
[ "${#frames[@]}" -gt 0 ] || { echo "no recorded frames in $media/live-dumps/phone" >&2; exit 1; }

model="$("${adb[@]}" shell getprop ro.product.model | tr -d '\r' | tr ' ' '_')"
suppress="${GETNF_BENCH_SUPPRESS:-}"
if [ "$("${adb[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ]; then
  model="emulator"; suppress="${suppress:+$suppress,}EMULATOR"
  echo "emulator: checking the plumbing only, the timings mean nothing"
fi

"${adb[@]}" install -r -t "$apk" >/dev/null
trap '"${adb[@]}" uninstall "$pkg" >/dev/null || true' EXIT
# Compiled ahead of time, as Play installs end up: just-in-time compilation would be timed too.
"${adb[@]}" shell cmd package compile -f -m speed "$pkg" >/dev/null
dir="/sdcard/Android/data/$pkg/files/frames"
"${adb[@]}" shell mkdir -p "$dir"
i=0
for f in "${frames[@]}"; do "${adb[@]}" push -q "$f" "$dir/$(printf '%03d' $i).jpg" >/dev/null; i=$((i + 1)); done
echo "${#frames[@]} frames pushed; running (a few minutes; keep the phone still, screen on, charging is fine)"

out="$("${adb[@]}" shell am instrument -w ${suppress:+-e androidx.benchmark.suppressErrors "$suppress"} \
  "$pkg/androidx.benchmark.junit4.AndroidBenchmarkRunner" | tr -d '\r')"
grep -qE '^OK \(' <<<"$out" || { echo "$out" >&2; exit 1; }

mkdir -p "$media/bench/device"
result="$media/bench/device/$(date +%Y%m%d-%H%M%S)-$model.json"
json="$("${adb[@]}" shell "ls /sdcard/Android/media/$pkg/*benchmarkData.json" | tr -d '\r' | tail -1)"
"${adb[@]}" pull "$json" "$result" >/dev/null 2>&1
"${adb[@]}" shell rm -f "/sdcard/Android/media/$pkg/*benchmarkData.json" || true
python3 - "$result" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
print(f"{'benchmark':<24}{'median':>9}{'min':>9}{'max':>9}  runs   (ms per frame)")
for b in data["benchmarks"]:
    t = b["metrics"]["timeNs"]
    ms = lambda v: f"{v / 1e6:9.1f}"
    print(f"{b['name']:<24}{ms(t['median'])}{ms(t['minimum'])}{ms(t['maximum'])}  {len(t['runs'])}")
PY
echo "written to ${result#$media/}"
