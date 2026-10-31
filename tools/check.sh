#!/usr/bin/env bash
# Everything a change must pass before it's pushed (camera plan, step 0), on the PC, no phone, no network: the app
# builds, the benchmark module builds, the JVM tests pass, and the capture benchmark (D89) has no regression against
# its accepted baseline. The git pre-push hook runs it (tools/githooks/pre-push; install with `tools/check.sh --install`).
#   tools/check.sh              everything
#   tools/check.sh --quick      without the capture benchmark (about 2 minutes shorter)
# The capture benchmark needs test-media/ (real receipts, so never on GitHub); without it, it's skipped with a warning.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
media="$(git -C "$root" rev-parse --path-format=absolute --git-common-dir | xargs dirname)/test-media"
quick=
case "${1:-}" in
  --install) git -C "$root" config core.hooksPath tools/githooks; echo "pre-push hook installed (core.hooksPath = tools/githooks)"; exit 0 ;;
  --quick) quick=1 ;;
  "") ;;
  *) echo "unknown option $1" >&2; exit 2 ;;
esac
. "$root/tools/env.sh" >/dev/null

echo "check: build and JVM tests"
(cd "$root/android" && ./gradlew -q assembleDebug :benchmark:assembleReleaseAndroidTest testDebugUnitTest) \
  || { echo "check: FAILED (build or JVM tests)" >&2; exit 1; }

if [ -n "$quick" ]; then echo "check: ok (capture benchmark skipped: --quick)"; exit 0; fi
if [ ! -f "$media/bench/corpus.tsv" ]; then echo "check: ok, but the capture benchmark was skipped: no $media/bench" >&2; exit 0; fi
echo "check: capture benchmark"
out="$("$root/tools/capture-bench.sh" --label check)"
summary="$(grep -E 'regression\(s\) against the baseline|no baseline yet' <<<"$out" || true)"
[ -n "$summary" ] || { echo "$out"; echo "check: FAILED (the capture benchmark didn't finish)" >&2; exit 1; }
n="$(grep -oE '[0-9]+ regression' <<<"$summary" | grep -oE '[0-9]+' || echo 0)"
if [ "$n" -gt 0 ]; then
  echo "$out"
  echo "check: FAILED ($n capture regression(s); see above, then fix them or accept with tools/capture-bench.sh --accept)" >&2
  exit 1
fi
echo "check: ok ($summary)"
