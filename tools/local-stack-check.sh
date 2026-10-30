#!/usr/bin/env bash
# Read-only health + exposure check of the local Supabase stack (D109). Changes nothing, prints no keys.
#   tools/local-stack-check.sh              # consumer checks (API, auth, storage, DB, Studio, mail, functions)
#   tools/local-stack-check.sh --bindings   # also show how each port is published and probe it via this host's own addresses
# Run from a worktree that has the supabase/ folder; the stack's keys come from `supabase status`.
set -uo pipefail
cd "$(dirname "$0")/.."

CLI=${SUPABASE_CLI:-}
if [[ -z $CLI ]]; then
  CLI=$(ls ~/.npm/_npx/*/node_modules/@supabase/cli-linux-x64/bin/supabase 2>/dev/null | head -1)
fi
[[ -x $CLI ]] || { echo "set SUPABASE_CLI to the supabase binary" >&2; exit 2; }

pass=0; fail=0
ok()   { echo "  ok    $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL  $1"; fail=$((fail+1)); }
code() { curl -s -o /dev/null -m 8 -w '%{http_code}' "$@"; }
expect() { # expect <label> <want-regex> <got>
  [[ $3 =~ ^($2)$ ]] && ok "$1 ($3)" || bad "$1 (got $3, wanted $2)"
}

env_out=$("$CLI" status -o env 2>/dev/null) || { echo "supabase status failed (is the stack up?)" >&2; exit 2; }
get() { sed -n "s/^$1=\"\(.*\)\"$/\1/p" <<<"$env_out"; }
API=$(get API_URL); ANON=$(get ANON_KEY); SERVICE=$(get SERVICE_ROLE_KEY); STUDIO=$(get STUDIO_URL); MAIL=$(get MAILPIT_URL)
DB_URL=$(get DB_URL)

echo "API gateway ($API)"
expect "auth health"    200 "$(code -H "apikey: $ANON" "$API/auth/v1/health")"
expect "rest (anon)"    200 "$(code -H "apikey: $ANON" "$API/rest/v1/")"
expect "storage buckets (service role)" 200 "$(code -H "apikey: $SERVICE" -H "Authorization: Bearer $SERVICE" "$API/storage/v1/bucket")"
# Auth: a wrong-password sign-in must be answered by GoTrue with 400, which proves the auth -> db path works
expect "auth password grant reaches GoTrue (bad creds -> 400)" 400 \
  "$(code -X POST -H "apikey: $ANON" -H 'Content-Type: application/json' -d '{"email":"nobody@example.invalid","password":"x"}' "$API/auth/v1/token?grant_type=password")"
fn=$(code -H "apikey: $ANON" "$API/functions/v1/")
if docker ps --format '{{.Names}}' | grep -q '^supabase_edge_runtime_getnf$'; then
  expect "edge functions gateway (runtime up)" '200|401|404' "$fn"
else
  echo "  skip  edge functions: supabase_edge_runtime_getnf is not running (it only runs under 'supabase functions serve'); gateway answers $fn"
fi

echo "Database ($DB_URL)"
if command -v psql >/dev/null; then
  [[ $(psql "$DB_URL" -Atc 'select 1' 2>/dev/null) == 1 ]] && ok "host psql over published port" || bad "host psql over published port"
else
  (exec 3<>/dev/tcp/127.0.0.1/54322) 2>/dev/null && ok "tcp connect 127.0.0.1:54322 (no psql installed)" || bad "tcp connect 127.0.0.1:54322"
fi
[[ $(docker exec supabase_db_getnf psql -U postgres -Atc 'select 1' 2>/dev/null) == 1 ]] && ok "in-container psql" || bad "in-container psql"
n=$(docker exec supabase_db_getnf psql -U postgres -Atc "select count(*) from supabase_migrations.schema_migrations" 2>/dev/null)
[[ $n =~ ^[0-9]+$ ]] && ok "migrations table readable ($n applied)" || bad "migrations table"

echo "Studio / mail"
expect "studio $STUDIO" '200|307' "$(code "$STUDIO")"
expect "mailpit $MAIL"  200 "$(code "$MAIL")"

if [[ ${1:-} == --bindings ]]; then
  echo "Published bindings (HostIp empty = all interfaces)"
  for c in supabase_kong_getnf supabase_db_getnf supabase_studio_getnf supabase_inbucket_getnf; do
    printf '  %-26s %s\n' "$c" "$(docker inspect "$c" --format '{{range $p,$b := .HostConfig.PortBindings}}{{$p}}->{{range $b}}[{{if .HostIp}}{{.HostIp}}{{else}}ALL{{end}}]{{.HostPort}} {{end}}{{end}}')"
  done
  echo "Listeners"
  ss -tln | awk 'NR==1 || /:5432[1-4]\>/'
  echo "Probes through this host's own non-loopback addresses (indicative only: the host's own traffic is not the"
  echo "path a LAN device takes; confirm from another device with tools/local-stack-check.sh on it, or nc -zv)"
  for a in $(ip -o -4 addr show scope global | awk '{print $4}' | cut -d/ -f1 | grep -v '^172\.\(1[6-9]\|2[0-9]\|3[01]\)\.'); do
    for p in 54321 54322 54323 54324; do
      (exec 3<>/dev/tcp/$a/$p) 2>/dev/null && echo "  OPEN   $a:$p" || echo "  closed $a:$p"
    done
  done
  for a in $(ip -o -6 addr show scope global | awk '{print $4}' | cut -d/ -f1); do
    for p in 54321 54322 54323 54324; do
      (exec 3<>/dev/tcp/$a/$p) 2>/dev/null && echo "  OPEN   [$a]:$p" || echo "  closed [$a]:$p"
    done
  done
fi

echo; echo "$pass ok, $fail failed"
[[ $fail -eq 0 ]]
