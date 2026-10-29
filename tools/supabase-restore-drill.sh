#!/usr/bin/env bash
# Restore drill (D65): loads the newest backup into the LOCAL Supabase and compares row counts with the online
# project. A backup counts as trusted only after this passed. Wipes the local Supabase's data (db reset).
#
#   tools/supabase-restore-drill.sh [dump]      # default: newest ~/getnf-backups/supabase/db-*.dump
set -euo pipefail
cd "$(dirname "$0")/.."
dump=${1:-$(ls -1 ~/getnf-backups/supabase/db-*.dump | tail -1)}
local_db=postgresql://postgres:postgres@127.0.0.1:54322/postgres
# The local superuser: only it may switch off triggers on the auth tables.
local_admin=postgresql://supabase_admin:postgres@127.0.0.1:54322/postgres
envfile=.env.local; [ -f "$envfile" ] || envfile=../../../.env.local
set -a; . "$envfile"; set +a
ref=$(sed -E 's#https://([^.]+)\..*#\1#' <<<"$SUPABASE_URL")
live="postgresql://postgres:$(python3 -c 'import os,urllib.parse;print(urllib.parse.quote(os.environ["SUPABASE_DB_PASSWORD"],safe=""))')@db.$ref.supabase.co:5432/postgres?sslmode=require"

echo "restoring $dump into the local Supabase"
npx supabase db reset >/dev/null
# Triggers off while loading: the new-user seed would duplicate categories, and updated_at must keep its values.
pg_restore --data-only --disable-triggers --no-owner --dbname="$local_admin" "$dump"

tables="auth.users public.ledger public.ledger_member public.receipt public.receipt_scan public.receipt_line public.receipt_photo public.issuer public.product public.store_item public.category public.tag public.product_tag"
count() { for t in $tables; do printf '%s ' "$(psql "$1" -Atc "select count(*) from $t")"; done; }
restored=$(count "$local_db"); online=$(count "$live")
printf '%-22s %10s %10s\n' table restored online
paste <(tr ' ' '\n' <<<"$tables") <(tr ' ' '\n' <<<"$restored") <(tr ' ' '\n' <<<"$online") | while read -r t r o; do
  if [ -n "$t" ]; then printf '%-22s %10s %10s%s\n' "$t" "$r" "$o" "$([ "$r" = "$o" ] || echo '  <- differs')"; fi
done
sum() { psql "$1" -Atc "select coalesce(sum(gross_cents - discount_cents), 0) from public.receipt_line"; }
echo "net spending (cents): restored $(sum "$local_db"), online $(sum "$live")"
[ "$restored" = "$online" ] && echo "drill passed" || { echo "drill FAILED: counts differ (changes since the dump?)"; exit 1; }
