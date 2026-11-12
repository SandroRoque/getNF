-- Deleting a receipt (T-50, D194): a tombstone the older copies can't undo, and a later scan can.
-- Run: npx supabase test db
begin;
select plan(13);

insert into auth.users (id, email) values ('00000000-0000-0000-0000-0000000000d5', 'del@example.test');
set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000d5", "role": "authenticated"}';
select set_config('t.l', (select ledger_id::text from public.ledger_member where user_id = auth.uid()), false);
select set_config('t.c', '42261011222333000181650010000000011000000010', false);

insert into public.receipt (ledger_id, chave, captured_at, page_path, page_url)
  values (current_setting('t.l')::uuid, current_setting('t.c'), '2026-11-01T10:00:00Z', 'x/page.html', 'https://p');
insert into public.product (ledger_id, id) values (current_setting('t.l')::uuid, '10000000-0000-0000-0000-0000000000d1');
insert into public.store_item (ledger_id, id, code_kind, code, store_cnpj, description, product_id) values
  (current_setting('t.l')::uuid, '20000000-0000-0000-0000-0000000000d1', 'store', '243', '11222333000181', 'MACA',
   '10000000-0000-0000-0000-0000000000d1');
insert into public.receipt_line (ledger_id, chave, line_no, description, code, qty_e4, unit_price_e4, gross_cents, store_item_id)
  values (current_setting('t.l')::uuid, current_setting('t.c'), 1, 'MACA', '243', 10000, 1000, 100, '20000000-0000-0000-0000-0000000000d1');
insert into public.receipt_photo (ledger_id, chave, local_name, position, object_path)
  values (current_setting('t.l')::uuid, current_setting('t.c'), 'photo-1.jpg', 0, 'x/photo-1.webp');
insert into public.receipt_scan (ledger_id, chave, scanned_at) values (current_setting('t.l')::uuid, current_setting('t.c'), now());

select is(public.delete_receipt(current_setting('t.l')::uuid, current_setting('t.c'), '2026-11-05T10:00:00Z'),
  '{x/page.html,x/photo-1.webp}'::text[], 'a member deletes the receipt, and learns which files to delete');
select is((select deleted::text || ' ' || coalesce(page_path, '-') from public.receipt where chave = current_setting('t.c')),
  'true -', 'the row stays as a tombstone, without its page');
select is((select count(*)::int from public.receipt_line where chave = current_setting('t.c'))
        + (select count(*)::int from public.receipt_photo where chave = current_setting('t.c'))
        + (select count(*)::int from public.receipt_scan where chave = current_setting('t.c')), 0, 'its lines, photos and scans go');
select is((select count(*)::int from public.product), 1, 'the products stay');

-- A phone that hadn't pulled the removal sends its copy (scanned before the deletion), then its lines.
insert into public.receipt (ledger_id, chave, captured_at, page_path)
  values (current_setting('t.l')::uuid, current_setting('t.c'), '2026-11-01T10:00:00Z', 'x/page.html')
  on conflict (ledger_id, chave) do update set captured_at = excluded.captured_at, page_path = excluded.page_path;
select is((select deleted::text || ' ' || coalesce(page_path, '-') from public.receipt where chave = current_setting('t.c')),
  'true -', 'an older copy changes nothing');
insert into public.receipt_line (ledger_id, chave, line_no, description, code, qty_e4, unit_price_e4, gross_cents, store_item_id)
  values (current_setting('t.l')::uuid, current_setting('t.c'), 1, 'MACA', '243', 10000, 1000, 100, '20000000-0000-0000-0000-0000000000d1');
insert into public.receipt_photo (ledger_id, chave, local_name, position)
  values (current_setting('t.l')::uuid, current_setting('t.c'), 'photo-1.jpg', 0);
select is((select count(*)::int from public.receipt_line where chave = current_setting('t.c'))
        + (select count(*)::int from public.receipt_photo where chave = current_setting('t.c')), 0, 'and its lines and photos are dropped');
select is(public.delete_receipt(current_setting('t.l')::uuid, current_setting('t.c'), now()), '{}'::text[],
  'deleting again changes nothing');
select is((select deleted_at from public.receipt where chave = current_setting('t.c')), '2026-11-05T10:00:00Z'::timestamptz,
  'the first deletion time stands');

-- The receipt scanned again after the deletion comes back, without the old page.
insert into public.receipt (ledger_id, chave, captured_at)
  values (current_setting('t.l')::uuid, current_setting('t.c'), '2026-11-06T10:00:00Z')
  on conflict (ledger_id, chave) do update set captured_at = excluded.captured_at, page_path = excluded.page_path;
select is((select deleted::text || ' ' || coalesce(page_path, '-') from public.receipt where chave = current_setting('t.c')),
  'false -', 'a scan after the deletion brings it back');
insert into public.receipt_line (ledger_id, chave, line_no, description, code, qty_e4, unit_price_e4, gross_cents, store_item_id)
  values (current_setting('t.l')::uuid, current_setting('t.c'), 1, 'MACA', '243', 10000, 1000, 100, '20000000-0000-0000-0000-0000000000d1');
select is((select count(*)::int from public.receipt_line where chave = current_setting('t.c')), 1, 'with its lines');
-- A phone offline since before that scan sends its deletion now: the later scan stands.
select is(public.delete_receipt(current_setting('t.l')::uuid, current_setting('t.c'), '2026-11-05T12:00:00Z'), '{}'::text[],
  'a deletion older than the scan is ignored');
select is((select deleted::text || ' ' || (select count(*) from public.receipt_line where chave = current_setting('t.c'))
  from public.receipt where chave = current_setting('t.c')), 'false 1', 'and leaves the receipt and its lines');

-- Someone outside the caderneta deletes nothing.
insert into public.receipt (ledger_id, chave, captured_at) values (current_setting('t.l')::uuid, '42261011222333000181650010000000021000000020', now());
reset role;
insert into auth.users (id, email) values ('00000000-0000-0000-0000-0000000000d6', 'out@example.test');
set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000d6", "role": "authenticated"}';
select public.delete_receipt(current_setting('t.l')::uuid, '42261011222333000181650010000000021000000020', now());
reset role;
select is((select deleted from public.receipt where chave = '42261011222333000181650010000000021000000020'), false,
  'an outsider deletes nothing');

select * from finish();
rollback;
