-- The catalog merges by field (D164, D183, review L8): the upserts the phone sends, as PostgREST writes them.
-- Run: npx supabase test db
begin;
select plan(7);

insert into auth.users (id, email) values ('00000000-0000-0000-0000-0000000000e2', 'eli@example.test');
set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000e2", "role": "authenticated"}';
select set_config('t.l', (select id::text from public.ledger limit 1), false);

insert into public.product (ledger_id, id) values
  (current_setting('t.l')::uuid, '10000000-0000-0000-0000-000000000001'),
  (current_setting('t.l')::uuid, '10000000-0000-0000-0000-000000000002');
insert into public.store_item (ledger_id, id, code_kind, code, store_cnpj, description, product_id) values
  (current_setting('t.l')::uuid, '20000000-0000-0000-0000-000000000001', 'store', '243', '11222333000181', 'MACA',
   '10000000-0000-0000-0000-000000000001');
insert into public.tag (ledger_id, id, slug, description) values
  (current_setting('t.l')::uuid, '30000000-0000-0000-0000-000000000001', 'organico', 'Orgânico');
select set_config('t.c', (select id::text from public.category where ledger_id = current_setting('t.l')::uuid limit 1), false);

select lives_ok($$
  insert into public.store_item (ledger_id, id, product_id)
  values (current_setting('t.l')::uuid, '20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002')
  on conflict (ledger_id, id) do update set product_id = excluded.product_id
$$, 'a store item moved to another product, the id and product alone');
select is((select code_kind || ' ' || code || ' ' || description || ' ' || product_id from public.store_item),
          'store 243 MACA 10000000-0000-0000-0000-000000000002', 'moves it and leaves the rest');
select is((select count(*) from public.store_item), 1::bigint, 'and makes no other row');

select lives_ok($$
  insert into public.category (ledger_id, id, sort) values (current_setting('t.l')::uuid, current_setting('t.c')::uuid, 7)
  on conflict (ledger_id, id) do update set sort = excluded.sort
$$, 'a category''s order alone');
select isnt((select label from public.category where id = current_setting('t.c')::uuid), '', 'keeps its name');

select lives_ok($$
  insert into public.tag (ledger_id, id, description) values (current_setting('t.l')::uuid, '30000000-0000-0000-0000-000000000001', 'Orgânicos')
  on conflict (ledger_id, id) do update set description = excluded.description
$$, 'a tag''s description alone');
select is((select slug || ' ' || description from public.tag where id = '30000000-0000-0000-0000-000000000001'),
          'organico Orgânicos', 'keeps its slug');

select * from finish();
rollback;
