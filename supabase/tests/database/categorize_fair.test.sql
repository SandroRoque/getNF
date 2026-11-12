-- The categorize queue takes cadernetas in turn, and refuses descriptions no receipt prints (T-43, D192).
-- Run: npx supabase test db
begin;
select plan(3);

insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-0000000000f1', 'flood@example.test'),
  ('00000000-0000-0000-0000-0000000000f2', 'tester@example.test');
select set_config('t.f', (select ledger_id::text from public.ledger_member where user_id = '00000000-0000-0000-0000-0000000000f1'), false);
select set_config('t.t', (select ledger_id::text from public.ledger_member where user_id = '00000000-0000-0000-0000-0000000000f2'), false);
insert into public.product (ledger_id, id) values
  (current_setting('t.f')::uuid, 'f0000000-0000-0000-0000-000000000001'),
  (current_setting('t.t')::uuid, 'f0000000-0000-0000-0000-000000000002');
insert into public.store_item (ledger_id, code_kind, code, description, product_id)
select current_setting('t.f')::uuid, 'store', 'F' || g, 'ITEM ' || g, 'f0000000-0000-0000-0000-000000000001' from generate_series(1, 40) g;
insert into public.store_item (ledger_id, id, code_kind, code, description, product_id) values
  (current_setting('t.t')::uuid, 'f0000000-0000-0000-0000-0000000000aa', 'store', 'T1', 'LEITE INTEGRAL 1L', 'f0000000-0000-0000-0000-000000000002');
-- Everything else on the local stack out of the way; the flood a minute older than the tester's item, all past the grace.
update public.categorize_job set status = 'done' where ledger_id not in (current_setting('t.f')::uuid, current_setting('t.t')::uuid);
update public.categorize_job set created_at = now() - interval '2 hours' where ledger_id = current_setting('t.f')::uuid;
update public.categorize_job set created_at = now() - interval '1 hour' where ledger_id = current_setting('t.t')::uuid;

set local role service_role;
select ok((select bool_or(store_item_id = 'f0000000-0000-0000-0000-0000000000aa') from public.claim_categorize_jobs(5)),
  'a caderneta with one item waiting is served in the first batch, behind 40 older items of another');
select is((select count(distinct ledger_id) from public.claim_categorize_jobs(5)), 1::bigint, 'and then the rest of the flood');
reset role;

select throws_ok($$insert into public.store_item (ledger_id, code_kind, code, description, product_id)
  values (current_setting('t.t')::uuid, 'store', 'BIG', repeat('ARROZ ', 50), 'f0000000-0000-0000-0000-000000000002')$$,
  '23514', null, 'a description longer than any receipt prints is refused');

select * from finish();
rollback;
