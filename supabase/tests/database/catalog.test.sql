-- Isolation between cadernetas, seeding and the "user's choice is final" rule (D51, D70, D104). Run: npx supabase test db
begin;
select plan(16);

insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-00000000000a', 'a@example.test'),
  ('00000000-0000-0000-0000-00000000000b', 'b@example.test');

select set_config('t.la', (select ledger_id::text from public.ledger_member
                            where user_id = '00000000-0000-0000-0000-00000000000a'), false);
select is((select count(*) from public.category where ledger_id = current_setting('t.la')::uuid),
          (select count(*) from public.taxonomy_node), 'a new user gets a caderneta with the whole taxonomy');
select is((select count(*) from public.category c join public.category p on p.ledger_id = c.ledger_id and p.id = c.parent_id
           where c.ledger_id = current_setting('t.la')::uuid and c.seed_path = 'food.produce.fruit'
             and p.seed_path = 'food.produce'), 1::bigint, 'seeded categories keep their parents');

-- As user A.
set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-00000000000a", "role": "authenticated"}';

select is((select count(*) from public.category), (select count(*) from public.taxonomy_node),
          'a user sees only their caderneta''s categories');

insert into public.issuer (ledger_id, cnpj, name) values (current_setting('t.la')::uuid, '11222333000181', 'Mercado Teste');
insert into public.receipt (ledger_id, chave, captured_at, issuer_cnpj)
  values (current_setting('t.la')::uuid, '42261011222333000181650010000000011000000010', now(), '11222333000181');
insert into public.product (ledger_id, id, category_id, category_source, category_review)
  values (current_setting('t.la')::uuid, '10000000-0000-0000-0000-000000000001',
          (select id from public.category where seed_path = 'food.produce.fruit'), 'user', 'confirmed');
insert into public.store_item (ledger_id, id, code_kind, code, store_cnpj, description, product_id, link_source)
  values (current_setting('t.la')::uuid, '20000000-0000-0000-0000-000000000001', 'store', '243', '11222333000181', 'MACA NAC KG',
          '10000000-0000-0000-0000-000000000001', 'regex');
insert into public.receipt_line (ledger_id, chave, line_no, description, code, qty_e4, unit_price_e4, gross_cents, store_item_id,
                                 discount_source)
  values (current_setting('t.la')::uuid, '42261011222333000181650010000000011000000010', 1, 'MACA NAC KG', '243', 5750, 129900, 747,
          '20000000-0000-0000-0000-000000000001', 'prorated');
select pass('a user writes a receipt, a product, a store item and a line');

-- A model can't replace the user's category, nor a manual size.
update public.product set category_id = (select id from public.category where seed_path = 'food.produce.vegetables'),
  category_source = 'jev', category_review = 'automatic', category_confidence = .95
  where id = '10000000-0000-0000-0000-000000000001';
select is((select c.seed_path from public.product p join public.category c on c.id = p.category_id
           where p.id = '10000000-0000-0000-0000-000000000001'), 'food.produce.fruit', 'a model never overwrites a user category');
select is((select category_source::text from public.product where id = '10000000-0000-0000-0000-000000000001'), 'user',
          'and the source stays the user');
update public.product set category_id = (select id from public.category where seed_path = 'food.produce.vegetables'),
  category_source = 'user', category_review = 'corrected' where id = '10000000-0000-0000-0000-000000000001';
select is((select c.seed_path from public.product p join public.category c on c.id = p.category_id
           where p.id = '10000000-0000-0000-0000-000000000001'), 'food.produce.vegetables', 'the user can change their own choice');

update public.product set size_e6 = 1000000, size_unit = 'kg', size_source = 'manual' where id = '10000000-0000-0000-0000-000000000001';
update public.product set size_e6 = 2000000, size_source = null where id = '10000000-0000-0000-0000-000000000001';
select is((select size_e6 from public.product where id = '10000000-0000-0000-0000-000000000001'), 1000000::bigint,
          'a manual size survives a non-manual write');

select ok((select updated_at from public.product where id = '10000000-0000-0000-0000-000000000001') > now(),
          'writes stamp updated_at with the clock, for the pull cursor');

-- As user B: A's rows are invisible and can't be referenced.
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-00000000000b", "role": "authenticated"}';
select is((select count(*) from public.receipt), 0::bigint, 'another user sees none of A''s receipts');
select is((select count(*) from public.product), 0::bigint, 'nor A''s products');
update public.product set name = 'hijacked' where id = '10000000-0000-0000-0000-000000000001';
select throws_ok($$insert into public.product (id, ledger_id) values (gen_random_uuid(), current_setting('t.la')::uuid)$$,
                 '42501', null, 'B can''t write into A''s caderneta');
select throws_ok($$insert into public.store_item (ledger_id, code_kind, code, description, product_id)
                   values ((select public.my_ledgers() limit 1), 'gtin', '7891000100103', 'X', '10000000-0000-0000-0000-000000000001')$$,
                 '23503', null, 'B can''t point a store item at A''s product');
select lives_ok($$select public.sync_taxonomy((select public.my_ledgers() limit 1))$$,
                'a member can sync their caderneta''s taxonomy');
select throws_ok($$select public.sync_taxonomy(current_setting('t.la')::uuid)$$, '42501', null,
                 'but not another caderneta''s');

reset role;
select is((select name from public.product where id = '10000000-0000-0000-0000-000000000001'), null,
          'B''s update didn''t touch A''s product');

select * from finish();
rollback;
