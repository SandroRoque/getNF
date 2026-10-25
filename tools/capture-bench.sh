#!/usr/bin/env bash
# The capture benchmark (D89): replays every recorded session in test-media/bench/corpus.tsv through the capture and
# scores it against the receipts' pages, compared with the accepted baseline. No network.
#   tools/capture-bench.sh                 the dev sessions; writes test-media/bench/runs/<time>-run.json
#   tools/capture-bench.sh --accept        ... and makes this run the baseline
#   tools/capture-bench.sh --holdout       the held-out sessions, totals only
#   tools/capture-bench.sh --only <text>   sessions whose path contains <text> (not compared, not kept)
#   tools/capture-bench.sh --label <name>  names the run file
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
media="$(git -C "$root" rev-parse --path-format=absolute --git-common-dir | xargs dirname)/test-media"
. "$root/tools/env.sh" >/dev/null
export GETNF_BENCH="$media/bench"
while [ $# -gt 0 ]; do
  case "$1" in
    --accept) export GETNF_BENCH_ACCEPT=1 ;;
    --holdout) export GETNF_BENCH_HOLDOUT=1 ;;
    --only) export GETNF_BENCH_ONLY="$2"; shift ;;
    --label) export GETNF_BENCH_LABEL="$2"; shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
  shift
done
cd "$root/android"
./gradlew -q testDebugUnitTest --tests '*CaptureBenchTest*' --rerun -i 2>&1 \
  | sed -n 's/^    //p' | grep -vE '^(Caching|Input property|and more|Task |Skipping|Resolve|Custom actions|Build cache|Watching|Up-to-date|Finished|Starting|Gradle|Successfully|Closing|Stopped|[A-Z][a-z]+ [a-z]+ for )' || true
