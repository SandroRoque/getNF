#!/usr/bin/env bash
# Copies the capture sessions a debug build dumped on the phone (files/capture-dump/) to test-media/live-dumps/phone/
# and adds the new ones to the benchmark corpus as held out (D89): they are scored blind first, with
# `tools/capture-bench.sh --holdout`, before anyone looks into them. Reads only the app's own files; no network.
#   tools/pull-capture-dumps.sh [serial]     (default: the only device attached)
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
media="$(git -C "$root" rev-parse --path-format=absolute --git-common-dir | xargs dirname)/test-media"
. "$root/tools/env.sh" >/dev/null
serial=(); [ $# -gt 0 ] && serial=(-s "$1")
app=br.com.roqlab.caderneta.debug
dest="$media/live-dumps/phone"; corpus="$media/bench/corpus.tsv"
mkdir -p "$dest"
for d in $(adb "${serial[@]}" shell run-as $app ls files/capture-dump | tr -d '\r' | grep -v '^enabled$'); do
  [ -d "$dest/$d" ] && continue
  adb "${serial[@]}" exec-out run-as $app tar c -C files/capture-dump "$d" | tar x -C "$dest"
  n=$(ls "$dest/$d" | grep -c '\.jpg$' || true)
  if [ "$n" -lt 3 ]; then echo "skipped $d: $n frames"; continue; fi
  printf 'live-dumps/phone/%s\tholdout\t360\n' "$d" >> "$corpus"
  echo "added $d ($n frames) as holdout"
done
