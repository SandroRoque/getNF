-- The categorize queue's limits (T-22, D196): a job whose runs keep dying fails, and a late failure can't reopen it.
-- Run: npx supabase test db
begin;
select plan(5);

insert into auth.users (id, email) values ('00000000-0000-0000-0000-0000000000e1', 'queue@example.test');
select set_config('t.l', (select ledger_id::text from public.ledger_member where user_id = '00000000-0000-0000-0000-0000000000e1'), false);
insert into public.product (ledger_id, id) values (current_setting('t.l')::uuid, 'e0000000-0000-0000-0000-000000000001');
insert into public.store_item (ledger_id, id, code_kind, code, description, product_id) values
  (current_setting('t.l')::uuid, 'e0000000-0000-0000-0000-0000000000a1', 'store', 'E1', 'ARROZ 5KG', 'e0000000-0000-0000-0000-000000000001'),
  (current_setting('t.l')::uuid, 'e0000000-0000-0000-0000-0000000000a2', 'store', 'E2', 'FEIJAO 1KG', 'e0000000-0000-0000-0000-000000000001');
update public.categorize_job set status = 'done' where ledger_id <> current_setting('t.l')::uuid;
update public.categorize_job set created_at = now() - interval '1 hour' where ledger_id = current_setting('t.l')::uuid;

-- Item 1: its run died on the sixth attempt, more than 10 minutes ago.
alter table public.categorize_job disable trigger touch;
update public.categorize_job set status = 'running', attempts = 6, updated_at = now() - interval '11 minutes'
  where store_item_id = 'e0000000-0000-0000-0000-0000000000a1';
alter table public.categorize_job enable trigger touch;
set local role service_role;
select is((select count(*) from public.claim_categorize_jobs(10) where store_item_id = 'e0000000-0000-0000-0000-0000000000a1'), 0::bigint,
  'a stale job on its last attempt isn''t taken up again');
select is((select status from public.categorize_job where store_item_id = 'e0000000-0000-0000-0000-0000000000a1'), 'failed',
  'it fails instead');

-- Item 2 was claimed (attempt 1) by the claim above; a later run took it up again (attempt 2) and finished it.
select is((select attempts from public.categorize_job where store_item_id = 'e0000000-0000-0000-0000-0000000000a2'), 1,
  'the other job was claimed once');
update public.categorize_job set attempts = 2, status = 'done' where store_item_id = 'e0000000-0000-0000-0000-0000000000a2';
-- The first run's failure arrives now, with either signature.
select public.fail_categorize_job(current_setting('t.l')::uuid, 'e0000000-0000-0000-0000-0000000000a2', 'late', 1);
select public.fail_categorize_job(current_setting('t.l')::uuid, 'e0000000-0000-0000-0000-0000000000a2', 'late');
select is((select status from public.categorize_job where store_item_id = 'e0000000-0000-0000-0000-0000000000a2'), 'done',
  'a late failure doesn''t reopen a job another run finished');
-- Taken up again and running on attempt 3: the failure of attempt 2 changes nothing, attempt 3's does.
update public.categorize_job set attempts = 3, status = 'running' where store_item_id = 'e0000000-0000-0000-0000-0000000000a2';
select public.fail_categorize_job(current_setting('t.l')::uuid, 'e0000000-0000-0000-0000-0000000000a2', 'old', 2);
select public.fail_categorize_job(current_setting('t.l')::uuid, 'e0000000-0000-0000-0000-0000000000a2', 'now', 3);
select is((select status || ' ' || last_error from public.categorize_job where store_item_id = 'e0000000-0000-0000-0000-0000000000a2'),
  'pending now', 'only the running attempt''s failure counts');

select * from finish();
rollback;
