-- Scan marks and photo rows name the login that wrote them (T-46).
-- Run: npx supabase test db
begin;
select plan(5);

insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-0000000000a1', 'ana@example.test'),
  ('00000000-0000-0000-0000-0000000000b1', 'bia@example.test'),
  ('00000000-0000-0000-0000-0000000000c1', 'out@example.test');
-- Bia joins Ana's caderneta.
insert into public.ledger_member (ledger_id, user_id, role)
  select ledger_id, '00000000-0000-0000-0000-0000000000b1', 'member' from public.ledger_member
  where user_id = '00000000-0000-0000-0000-0000000000a1';
select set_config('t.l', (select ledger_id::text from public.ledger_member
  where user_id = '00000000-0000-0000-0000-0000000000a1' and role = 'owner'), false);
select set_config('t.c', '42261011222333000181650010000000031000000030', false);
insert into public.receipt (ledger_id, chave, captured_at) values (current_setting('t.l')::uuid, current_setting('t.c'), now());

set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000a1", "role": "authenticated"}';
insert into public.receipt_photo (ledger_id, chave, local_name, position)
  values (current_setting('t.l')::uuid, current_setting('t.c'), 'photo-1.jpg', 0);

set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000b1", "role": "authenticated"}';
-- Bia writes a scan mark in Ana's name, and one in the name of someone outside, then of no login at all.
insert into public.receipt_scan (ledger_id, chave, user_id, scanned_at)
  values (current_setting('t.l')::uuid, current_setting('t.c'), '00000000-0000-0000-0000-0000000000a1', now());
select is((select user_id::text from public.receipt_scan where chave = current_setting('t.c')),
  '00000000-0000-0000-0000-0000000000b1', 'a scan mark names whoever wrote it, not the id sent');
select lives_ok($$insert into public.receipt_scan (ledger_id, chave, user_id, scanned_at)
  values (current_setting('t.l')::uuid, current_setting('t.c'), '00000000-0000-0000-0000-00000000dead', now())
  on conflict (ledger_id, chave, user_id) do nothing$$, 'an id that is no login says nothing about it');
update public.receipt_scan set user_id = '00000000-0000-0000-0000-0000000000c1' where chave = current_setting('t.c');
select is((select string_agg(user_id::text, ',') from public.receipt_scan where chave = current_setting('t.c')),
  '00000000-0000-0000-0000-0000000000b1', 'an update can''t move the mark to another login');
-- Bia sends Ana's photo again (archived once more): it stays Ana's, and a new one is Bia's.
insert into public.receipt_photo (ledger_id, chave, local_name, position, object_path, created_by)
  values (current_setting('t.l')::uuid, current_setting('t.c'), 'photo-1.jpg', 0, 'x/1.webp', '00000000-0000-0000-0000-0000000000b1')
  on conflict (ledger_id, chave, local_name) do update set object_path = excluded.object_path, created_by = excluded.created_by;
select is((select created_by::text from public.receipt_photo where local_name = 'photo-1.jpg'),
  '00000000-0000-0000-0000-0000000000a1', 'a photo row keeps its sender when another member updates it');
insert into public.receipt_photo (ledger_id, chave, local_name, position, created_by)
  values (current_setting('t.l')::uuid, current_setting('t.c'), 'photo-2.jpg', 1, '00000000-0000-0000-0000-0000000000a1');
select is((select created_by::text from public.receipt_photo where local_name = 'photo-2.jpg'),
  '00000000-0000-0000-0000-0000000000b1', 'a new photo row names whoever sent it');

select * from finish();
rollback;
