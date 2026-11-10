-- Review 2026-11-09 (sync): a plain member's tree edit is refused by RLS (42501), which PostgREST answers with 403
-- for an authenticated caller. SyncEngine.pushEvents doesn't catch it, so the whole sync fails at that step, every run.
-- Synthetic users and ledger; everything rolls back.
-- Run: docker exec -i supabase_db_getnf psql -U postgres -d postgres < member-category-event-refused.sql
begin;
insert into auth.users (id, instance_id, aud, role, email) values
  ('00000000-0000-4000-8000-0000000c0001', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'owner-1109@example.invalid'),
  ('00000000-0000-4000-8000-0000000c0002', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'member-1109@example.invalid');
insert into public.ledger (id, name) values ('00000000-0000-4000-8000-0000000c00aa', 'review-1109') on conflict do nothing;
insert into public.ledger_member (ledger_id, user_id, role) values
  ('00000000-0000-4000-8000-0000000c00aa', '00000000-0000-4000-8000-0000000c0001', 'owner'),
  ('00000000-0000-4000-8000-0000000c00aa', '00000000-0000-4000-8000-0000000c0002', 'member')
  on conflict do nothing;
select ledger_id, user_id, role from public.ledger_member where ledger_id = '00000000-0000-4000-8000-0000000c00aa';

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"00000000-0000-4000-8000-0000000c0002","role":"authenticated"}', true);
-- What pushEvents sends for a rename made on the member's phone (no actor: the column defaults to auth.uid()).
\set ON_ERROR_STOP off
insert into public.category_event (id, ledger_id, op, category_id, target_ids, old_label, new_label, products, at)
values (gen_random_uuid(), '00000000-0000-4000-8000-0000000c00aa', 'rename', gen_random_uuid(), '{}', 'a', 'b', 0, now())
on conflict (id) do nothing;
\echo 'sqlstate of the insert above:' :LAST_ERROR_SQLSTATE
rollback;
