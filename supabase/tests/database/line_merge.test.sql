-- Receipt lines merge by field (D182, review L1): the upserts the phone sends, as PostgREST writes them.
-- Run: npx supabase test db
begin;
select plan(5);

insert into auth.users (id, email) values ('00000000-0000-0000-0000-0000000000e1', 'eva@example.test');
set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000e1", "role": "authenticated"}';
select set_config('t.l', (select id::text from public.ledger limit 1), false);

insert into public.receipt (ledger_id, chave, captured_at)
  values (current_setting('t.l')::uuid, '42261011222333000181650010000000011000000010', now());
insert into public.product (ledger_id, id) values
  (current_setting('t.l')::uuid, '10000000-0000-0000-0000-000000000001'),
  (current_setting('t.l')::uuid, '10000000-0000-0000-0000-000000000002');
insert into public.store_item (ledger_id, id, code_kind, code, store_cnpj, description, product_id) values
  (current_setting('t.l')::uuid, '20000000-0000-0000-0000-000000000001', 'store', '243', '11222333000181', 'MACA',
   '10000000-0000-0000-0000-000000000001');

-- Another phone typed the discount and named the product.
insert into public.receipt_line (ledger_id, chave, line_no, description, code, qty_e4, unit_price_e4, gross_cents,
                                 store_item_id, product_override_id, discount_cents, discount_source, discount_review)
  values (current_setting('t.l')::uuid, '42261011222333000181650010000000011000000010', 1, 'MACA', '243', 5750, 129900,
          747, '20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', 50, 'manual', 'confirmed');

-- A stale phone's re-read: first the whole line if the server lacks it (ignore-duplicates)...
select lives_ok($$
  insert into public.receipt_line (ledger_id, chave, line_no, description, code, qty_e4, unit_price_e4, gross_cents,
                                   store_item_id, product_override_id, discount_cents, discount_source, discount_review)
  values (current_setting('t.l')::uuid, '42261011222333000181650010000000011000000010', 1, 'MACA', '243', 5750, 129900,
          747, '20000000-0000-0000-0000-000000000001', null, 0, 'prorated', 'unreviewed')
  on conflict (ledger_id, chave, line_no) do nothing
$$, 'a line sent whole to an existing row');
select is((select discount_source || ' ' || product_override_id from public.receipt_line where line_no = 1),
          'manual 10000000-0000-0000-0000-000000000002', 'leaves it as it was');

-- ...then its extracted columns and the corrections it changed (none), as a merge-duplicates upsert.
select lives_ok($$
  insert into public.receipt_line (ledger_id, chave, line_no, description, code, qty_e4, unit, unit_price_e4,
                                   gross_cents, store_item_id, orphaned, surcharge_cents)
  values (current_setting('t.l')::uuid, '42261011222333000181650010000000011000000010', 1, 'MACA', '243', 5750, null,
          129900, 747, '20000000-0000-0000-0000-000000000001', false, 12)
  on conflict (ledger_id, chave, line_no) do update set description = excluded.description, code = excluded.code,
    qty_e4 = excluded.qty_e4, unit = excluded.unit, unit_price_e4 = excluded.unit_price_e4,
    gross_cents = excluded.gross_cents, store_item_id = excluded.store_item_id, orphaned = excluded.orphaned,
    surcharge_cents = excluded.surcharge_cents
$$, 'an upsert without the correction columns passes NOT NULL');
select is((select surcharge_cents from public.receipt_line where line_no = 1), 12::bigint, 'it writes what the re-read changed');
select is((select discount_cents || ' ' || discount_source || ' ' || discount_review || ' ' || product_override_id
           from public.receipt_line where line_no = 1),
          '50 manual confirmed 10000000-0000-0000-0000-000000000002', 'and keeps the corrections it didn''t send');

select * from finish();
rollback;
