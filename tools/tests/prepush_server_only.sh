#!/usr/bin/env bash
# T-44 (review 2026-11-09): the pre-push hook must not skip tools/check.sh -- and so the server tests D176 added --
# for a push that changes only supabase/ (a migration, an Edge Function, a pgTAP test).
# Runs the real tools/githooks/pre-push in a throwaway repo under $TMPDIR with a stub check.sh. Touches nothing else.
set -euo pipefail
# From a hook, GIT_DIR and friends point at the real repository: the throwaway one must not inherit them.
unset $(git rev-parse --local-env-vars)
hook="$(cd "$(dirname "$0")/.." && pwd)/githooks/pre-push"
t="$(mktemp -d)"; trap 'rm -rf "$t"' EXIT
cd "$t"; git init -q -b main .; git config user.email r@example.invalid; git config user.name r
mkdir -p tools android supabase/migrations
printf '#!/bin/sh\necho "check.sh RAN"\n' > tools/check.sh; chmod +x tools/check.sh
echo app > android/a.txt; git add -A; git commit -qm base; base="$(git rev-parse HEAD)"
git update-ref refs/remotes/origin/main "$base"
echo "create table x();" > supabase/migrations/20990101000000_x.sql; git add -A; git commit -qm migration
head="$(git rev-parse HEAD)"
echo "push of a migration-only commit:"
out="$(echo "refs/heads/main $head refs/heads/main $base" | bash "$hook")"; echo "  hook exit=$? output='${out}'"
[ -n "$out" ] && echo "  OK: check.sh ran" || { echo "  FAIL: expected check.sh (with the server tests, D176) to run before pushing a migration; it was skipped"; exit 1; }
