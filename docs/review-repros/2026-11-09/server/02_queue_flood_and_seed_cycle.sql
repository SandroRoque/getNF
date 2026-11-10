-- Server review 2026-11-09, area "server". Synthetic users only; everything is rolled back.
-- Run: docker exec -i supabase_db_getnf psql -U postgres -d postgres -v ON_ERROR_STOP=1 < 02_queue_flood_and_seed_cycle.sql
--
-- A. One signed-in account floods the shared categorize queue: every store_item insert queues a job, jobs are claimed
--    globally oldest-first, 25 a minute, with no per-caderneta share and no size cap on the description sent to Jev.
-- B. sync_taxonomy_for writes a category parent cycle by itself after a seed move, when the user had moved the other
--    node (an ordinary edit): X under Y by the user, then a seed migration puts Y under X.
-- C. receipt_scan.user_id / receipt_photo.created_by take any login's id, member or not (and FK errors say whether an
--    id is a login).
begin;
insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-00000000f101', 'flooder@example.test'),
  ('00000000-0000-0000-0000-00000000f102', 'tester@example.test'),
  ('00000000-0000-0000-0000-00000000f103', 'outsider@example.test');

-- ---------- A ----------
set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-00000000f101", "role": "authenticated"}';
select set_config('t.f', (select ledger_id::text from public.ledger_member where user_id = auth.uid()), true);
insert into public.product (ledger_id, id) values (current_setting('t.f')::uuid, '00000000-0000-0000-0000-0000000aaaa1');
insert into public.store_item (ledger_id, code_kind, code, description, product_id)
select current_setting('t.f')::uuid, 'store', 'F' || g, 'ITEM ' || g, '00000000-0000-0000-0000-0000000aaaa1'
from generate_series(1, 2000) g;
-- One more with a 200,000-character description: nothing refuses it, and the categorizer sends it to the provider.
insert into public.store_item (ledger_id, code_kind, code, description, product_id)
values (current_setting('t.f')::uuid, 'store', 'BIG', repeat('ARROZ ', 33334), '00000000-0000-0000-0000-0000000aaaa1');

set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-00000000f102", "role": "authenticated"}';
select set_config('t.t', (select ledger_id::text from public.ledger_member where user_id = auth.uid()), true);
insert into public.product (ledger_id, id) values (current_setting('t.t')::uuid, '00000000-0000-0000-0000-0000000bbbb1');
insert into public.store_item (ledger_id, id, code_kind, code, description, product_id)
values (current_setting('t.t')::uuid, '00000000-0000-0000-0000-0000000bbbb2', 'store', 'T1', 'LEITE INTEGRAL 1L',
        '00000000-0000-0000-0000-0000000bbbb1');
reset role;

-- All jobs older than the 10-minute grace (as they will be soon), the flood a minute before the tester's item.
-- (Placed before anything else already pending on the shared local stack, so the counts are only these two accounts'.)
update public.categorize_job set created_at = timestamptz '2000-01-01' + (created_at - now()) where ledger_id = current_setting('t.f')::uuid;
update public.categorize_job set created_at = timestamptz '2000-01-01 00:01' where ledger_id = current_setting('t.t')::uuid;

select 'A1 jobs queued by one account' as check, count(*) from public.categorize_job where ledger_id = current_setting('t.f')::uuid;
select 'A2 longest description accepted' as check, max(length(description)) from public.store_item where ledger_id = current_setting('t.f')::uuid;
select 'A3 tester''s item position in the global queue' as check,
       (select count(*) from public.categorize_job q where q.status = 'pending'
          and q.created_at <= (select created_at from public.categorize_job where store_item_id = '00000000-0000-0000-0000-0000000bbbb2')) as position,
       'claimed at 25/min => minutes of wait' as note,
       ceil((select count(*) from public.categorize_job q where q.status = 'pending'
          and q.created_at <= (select created_at from public.categorize_job where store_item_id = '00000000-0000-0000-0000-0000000bbbb2')) / 25.0) as minutes;
set local role service_role;
select 'A4 one cron run claims (by ledger)' as check, (ledger_id = current_setting('t.f')::uuid) as flooder, count(*)
from public.claim_categorize_jobs(25) group by 2;
reset role;

-- ---------- B ----------
-- Two seed leaves X and Y with different parents, neither an ancestor of the other.
select set_config('t.x', (select n.path from public.taxonomy_node n where n.parent_path is not null
  and not exists (select 1 from public.taxonomy_node c where c.parent_path = n.path) order by n.path limit 1), true);
select set_config('t.y', (select n.path from public.taxonomy_node n where n.parent_path is not null
  and n.parent_path <> (select parent_path from public.taxonomy_node where path = current_setting('t.x'))
  and not exists (select 1 from public.taxonomy_node c where c.parent_path = n.path) order by n.path desc limit 1), true);
select 'B0 seed nodes' as check, current_setting('t.x') as x, current_setting('t.y') as y;

set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-00000000f102", "role": "authenticated"}';
-- The owner moves X under Y (what the app's category editor writes: new parent, user_modified).
update public.category c set parent_id = (select id from public.category where ledger_id = c.ledger_id and seed_path = current_setting('t.y')),
       user_modified = true
where c.ledger_id = current_setting('t.t')::uuid and c.seed_path = current_setting('t.x');
reset role;

-- A later taxonomy migration (tools/taxonomy-sql.py) moves Y under X in the seed.
update public.taxonomy_node set parent_path = current_setting('t.x') where path = current_setting('t.y');

-- The phone calls sync_taxonomy before every sync.
set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-00000000f102", "role": "authenticated"}';
select public.sync_taxonomy(current_setting('t.t')::uuid);
reset role;

select 'B1 X.parent is Y' as check, (select p.seed_path from public.category c join public.category p
  on p.ledger_id = c.ledger_id and p.id = c.parent_id where c.ledger_id = current_setting('t.t')::uuid and c.seed_path = current_setting('t.x')) = current_setting('t.y') as holds;
select 'B2 Y.parent is X (cycle written by the server)' as check, (select p.seed_path from public.category c join public.category p
  on p.ledger_id = c.ledger_id and p.id = c.parent_id where c.ledger_id = current_setting('t.t')::uuid and c.seed_path = current_setting('t.y')) = current_setting('t.x') as holds;

-- ---------- C ----------
set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-00000000f102", "role": "authenticated"}';
insert into public.receipt (ledger_id, chave, captured_at)
  values (current_setting('t.t')::uuid, '00000000000000000000000000000000000000000001', now());
insert into public.receipt_scan (ledger_id, chave, user_id, scanned_at)
  values (current_setting('t.t')::uuid, '00000000000000000000000000000000000000000001', '00000000-0000-0000-0000-00000000f103', now());
select 'C1 scan mark attributed to a non-member login accepted' as check, count(*) from public.receipt_scan
  where ledger_id = current_setting('t.t')::uuid and user_id = '00000000-0000-0000-0000-00000000f103';
savepoint s;
\set ON_ERROR_STOP 0
insert into public.receipt_scan (ledger_id, chave, user_id, scanned_at)
  values (current_setting('t.t')::uuid, '00000000000000000000000000000000000000000001', '00000000-0000-0000-0000-00000000dead', now());
\set ON_ERROR_STOP 1
rollback to savepoint s;
reset role;

rollback;
