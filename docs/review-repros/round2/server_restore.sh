#!/usr/bin/env bash
# ONLY disposable review container; schema and data are synthetic.
set -euo pipefail
container=supabase_db_getnf-review-round2
docker exec "$container" dropdb -U supabase_admin --if-exists review_restore
docker exec "$container" createdb -U supabase_admin review_restore
docker exec "$container" psql -X -U supabase_admin -d review_restore -v ON_ERROR_STOP=1 -c "DROP SCHEMA public;"
docker exec "$container" pg_dump -U supabase_admin --schema-only --schema=public --schema=auth postgres > /tmp/getnf-review-round2/schema.sql
docker exec -i "$container" psql -X -U supabase_admin -d review_restore -v ON_ERROR_STOP=1 < /tmp/getnf-review-round2/schema.sql >/tmp/getnf-review-round2/schema-restore.log 2>&1
# Same pg_dump selection/format as tools/supabase-backup.py; no production credentials or URLs.
docker exec "$container" pg_dump -U supabase_admin --format=custom --data-only '--table=public.*' '--exclude-table=public.taxonomy_*' --table=auth.users --table=auth.identities postgres > /tmp/getnf-review-round2/synthetic.dump
docker exec -i "$container" pg_restore -U supabase_admin --data-only --disable-triggers --no-owner --exit-on-error --dbname=review_restore < /tmp/getnf-review-round2/synthetic.dump
for database in postgres review_restore; do
 docker exec "$container" psql -X -U supabase_admin -d "$database" -Atc "select '$database', (select count(*) from auth.users), (select count(*) from public.receipt), (select count(*) from public.receipt_line), (select coalesce(sum(gross_cents-discount_cents),0) from public.receipt_line);"
done
