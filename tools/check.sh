#!/usr/bin/env bash
# Everything a change must pass before it's pushed (camera plan, step 0), on the PC, no phone, no network: the app
# builds, the benchmark module builds, the JVM tests pass, the server's tests pass against the local Supabase (D176),
# and the capture benchmark (D89) has no regression against its accepted baseline. The git pre-push hook runs it
# (tools/githooks/pre-push; install with `tools/check.sh --install`).
#   tools/check.sh              everything
#   tools/check.sh --quick      without the capture benchmark (about 2 minutes shorter)
# The server's tests need the local Supabase running (`npx supabase start`): migrations not yet applied to it are
# applied, then pgTAP, the Edge Functions' Deno tests and the JVM tests that talk to it (the phone's sync, withdrawals,
# reports). Without it they're skipped with a warning, as on GitHub.
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

# The local Supabase's API port. Only what's on this PC is ever tested: the online project is never touched from here.
if (exec 3<>/dev/tcp/127.0.0.1/54321) 2>/dev/null; then
  echo "check: server tests (local Supabase)"
  fail() { echo "check: FAILED (server tests: $1)" >&2; exit 1; }
  cd "$root"
  keys="$(npx supabase status -o env 2>/dev/null | grep -E '^(PUBLISHABLE_KEY|SECRET_KEY)=')" \
    || fail "the Supabase CLI didn't answer; try \`npx supabase status\`"
  eval "$keys"
  npx supabase migration up --local >/dev/null 2>&1 || fail "a migration didn't apply to the local database; see \`npx supabase migration up --local\`"
  out="$(npx supabase test db 2>&1)" || { echo "$out"; fail "pgTAP"; }
  command -v deno >/dev/null || fail "deno isn't installed"
  out="$(deno test supabase/functions 2>&1)" || { echo "$out"; fail "the Edge Functions' Deno tests"; }
  (cd android && GETNF_SUPABASE_LOCAL="$PUBLISHABLE_KEY,$SECRET_KEY" ./gradlew -q :app:testDebugUnitTest --rerun \
    --tests '*SupabaseLocalTest' --tests '*Round2HttpTest' --tests '*Round3*') || fail "the JVM tests against the local Supabase"
  server="server tests passed"
else
  echo "check: the server tests were skipped: no local Supabase on 127.0.0.1:54321 (npx supabase start)" >&2
  server="server tests skipped"
fi

if [ -n "$quick" ]; then echo "check: ok ($server; capture benchmark skipped: --quick)"; exit 0; fi
if [ ! -f "$media/bench/corpus.tsv" ]; then echo "check: ok ($server), but the capture benchmark was skipped: no $media/bench" >&2; exit 0; fi
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
echo "check: ok ($server; $summary)"
