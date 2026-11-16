#!/usr/bin/env bash
# The sync simulation (D210, SyncSimulationTest) by hand, against the local Supabase (`npx supabase start`); never the
# online project.
#   tools/sync-sim.sh                     the fixed seeds, as tools/check.sh runs them
#   tools/sync-sim.sh 17:23 [42 ...]      these seeds (seed, or seed:events to stop after that many events)
#   tools/sync-sim.sh --random 200 [--from 1000] [--events 40]
#                                         200 seeds in a row, from the clock's or from 1000; each failure is printed
#                                         with the shortest run of events that still fails
# A failure prints the seed, the events and the rules broken. The same seed gives the same events.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
. "$root/tools/env.sh" >/dev/null
(exec 3<>/dev/tcp/127.0.0.1/54321) 2>/dev/null || { echo "no local Supabase on 127.0.0.1:54321 (npx supabase start)" >&2; exit 2; }
test='*SyncSimulationTest.theFixedSeedsKeepTheRules'
seeds=
while [ $# -gt 0 ]; do
  case "$1" in
    --random) export GETNF_SIM_RANDOM="$2"; test='*SyncSimulationTest.randomSeedsKeepTheRules'; shift 2 ;;
    --from) export GETNF_SIM_FROM="$2"; shift 2 ;;
    --events) export GETNF_SIM_STEPS="$2"; shift 2 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) seeds="${seeds:+$seeds,}$1"; shift ;;
  esac
done
[ -z "$seeds" ] || export GETNF_SIM_SEEDS="$seeds"
cd "$root"
status() { npx supabase status -o env 2>/dev/null | grep -E '^(PUBLISHABLE_KEY|SECRET_KEY)='; }
# The shell may lack the docker group, which the CLI needs to answer.
keys="$(status || echo 'npx supabase status -o env 2>/dev/null' | newgrp docker 2>/dev/null | grep -E '^(PUBLISHABLE_KEY|SECRET_KEY)=')" \
  || { echo "the Supabase CLI didn't answer; try \`npx supabase status\`" >&2; exit 2; }
eval "$keys"
cd android
GETNF_SUPABASE_LOCAL="$PUBLISHABLE_KEY,$SECRET_KEY" ./gradlew -q :app:testDebugUnitTest --rerun --tests "$test" -i 2>&1 \
  | grep -vE '^(Gradle Test|Finished generating|Starting process|Successfully started|> Task|Executing|Skipping task|Caching disabled|Task .* is not up-to-date|Resolve mutations|work action resolve|Build cache key|file or directory|Found locally|The input changes|Watching)' \
  | grep -E 'sync simulation|^  |broken rules|only in|only on|only online|PASSED|FAILED|BUILD|error:|e: ' || true
exit "${PIPESTATUS[0]}"
