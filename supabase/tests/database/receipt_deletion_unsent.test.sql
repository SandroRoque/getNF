-- Deleting a receipt the caderneta never had (D210): the tombstone is left all the same.
-- Run: npx supabase test db
begin;
select plan(6);

insert into auth.users (id, email) values ('00000000-0000-0000-0000-0000000000d6', 'unsent@example.test');
insert into auth.users (id, email) values ('00000000-0000-0000-0000-0000000000d7', 'outsider@example.test');
set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000d6", "role": "authenticated"}';
select set_config('t.l', (select ledger_id::text from public.ledger_member where user_id = auth.uid()), false);
select set_config('t.c', '42261011222333000181650010000000011000000027', false);

select is(public.delete_receipt(current_setting('t.l')::uuid, current_setting('t.c'), '2026-11-05T10:00:00Z'),
  '{}'::text[], 'deleting a receipt that was never sent names no files');
select is((select deleted::text || ' ' || deleted_at::text from public.receipt where chave = current_setting('t.c')),
  'true 2026-11-05 10:00:00+00', 'and leaves its tombstone, with the time of the deletion');

-- Another phone's copy, scanned before the deletion and sent after it.
insert into public.receipt (ledger_id, chave, captured_at, page_path)
  values (current_setting('t.l')::uuid, current_setting('t.c'), '2026-11-01T10:00:00Z', 'x/page.html')
  on conflict (ledger_id, chave) do update set captured_at = excluded.captured_at, page_path = excluded.page_path;
select is((select deleted::text || ' ' || coalesce(page_path, '-') from public.receipt where chave = current_setting('t.c')),
  'true -', 'an older copy sent afterwards changes nothing');

-- A scan made after the deletion.
insert into public.receipt (ledger_id, chave, captured_at)
  values (current_setting('t.l')::uuid, current_setting('t.c'), '2026-11-06T10:00:00Z')
  on conflict (ledger_id, chave) do update set captured_at = excluded.captured_at;
select is((select deleted::text from public.receipt where chave = current_setting('t.c')), 'false', 'a scan after it brings the receipt back');

-- Someone outside the caderneta can't leave a tombstone in it.
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000d7", "role": "authenticated"}';
select is(public.delete_receipt(current_setting('t.l')::uuid, '42261011222333000181650010000000011000000035', now()), '{}'::text[],
  'an outsider is answered as before');
reset role;
select is((select count(*)::int from public.receipt where chave = '42261011222333000181650010000000011000000035'), 0,
  'and leaves no tombstone');

select * from finish();
rollback;
