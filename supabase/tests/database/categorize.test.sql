-- Stage 4b's queue and write-back (D70, D108): what gets queued, what an answer changes, and what it may not.
begin;
select plan(22);

insert into auth.users (id, email) values ('00000000-0000-0000-0000-00000000000a', 'a@example.test');
set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-00000000000a", "role": "authenticated"}';
select set_config('t.la', (select public.my_ledgers()::text limit 1), false);

insert into public.product (ledger_id, id) values (current_setting('t.la')::uuid, '10000000-0000-0000-0000-000000000001'),
  (current_setting('t.la')::uuid, '10000000-0000-0000-0000-000000000002');
update public.product set category_id = (select id from public.category where ledger_id = current_setting('t.la')::uuid and seed_path = 'beverages.coffee'),
  category_source = 'user', category_review = 'confirmed' where id = '10000000-0000-0000-0000-000000000002';
insert into public.store_item (ledger_id, id, code_kind, code, description, product_id) values
  (current_setting('t.la')::uuid, '20000000-0000-0000-0000-000000000001', 'gtin', '7891000100103', 'MACA NAC KG', '10000000-0000-0000-0000-000000000001'),
  (current_setting('t.la')::uuid, '20000000-0000-0000-0000-000000000002', 'gtin', '7891000100104', 'CAFE PILAO', '10000000-0000-0000-0000-000000000002');
select throws_ok($$select * from public.categorize_job$$, '42501', null, 'users can''t read the queue');
select throws_ok($$select public.claim_categorize_jobs(10)$$, '42501', null, 'nor take jobs');

reset role;
select is((select count(*) from public.categorize_job where ledger_id = current_setting('t.la')::uuid), 2::bigint,
  'every new store item is queued, to be read (D108)');

-- Other cadernetas' jobs in the local database stay out of the way (rolled back with the rest).
update public.categorize_job set status = 'done' where ledger_id <> current_setting('t.la')::uuid;
set local role service_role;
select is((select count(*) from public.claim_categorize_jobs(10)), 0::bigint, 'a job waits for its item''s lines');
reset role;
update public.categorize_job set created_at = now() - interval '11 minutes' where store_item_id = '20000000-0000-0000-0000-000000000002';
insert into public.receipt (ledger_id, chave, captured_at) values (current_setting('t.la')::uuid, '42261000000000000000650010000000011000000016', now());
insert into public.receipt_line (ledger_id, chave, line_no, description, code, qty_e4, unit, unit_price_e4, gross_cents, store_item_id, discount_source)
  values (current_setting('t.la')::uuid, '42261000000000000000650010000000011000000016', 1, 'MACA NAC KG', '7891000100103', 12340, 'UN', 89900, 1109,
          '20000000-0000-0000-0000-000000000001', 'prorated');
set local role service_role;
select results_eq($$select store_item_id::text, wants_category, unit, qty_e4 from public.claim_categorize_jobs(10) order by store_item_id$$,
  $$values ('20000000-0000-0000-0000-000000000001', true, 'UN'::text, 12340::bigint),
           ('20000000-0000-0000-0000-000000000002', false, null, null)$$,
  'with a line, or after 10 minutes, it''s taken, with its unit and quantity and whether a category is wanted');
select is((select count(*) from public.claim_categorize_jobs(10)), 0::bigint, 'and nobody takes it twice');

select is(public.apply_categorization(current_setting('t.la')::uuid, '20000000-0000-0000-0000-000000000001',
  '10000000-0000-0000-0000-000000000001', (select id from public.category where ledger_id = current_setting('t.la')::uuid and seed_path = 'food.produce.vegetables'), .5, 'jev-latest', .9),
  'suggested', 'below the threshold, a suggestion');
select is((select category_id from public.product where id = '10000000-0000-0000-0000-000000000001'), null,
  'which doesn''t count as the category');
select is(public.apply_categorization(current_setting('t.la')::uuid, '20000000-0000-0000-0000-000000000001',
  '10000000-0000-0000-0000-000000000001', (select id from public.category where ledger_id = current_setting('t.la')::uuid and seed_path = 'food.produce.fruit'), .97, 'jev-latest', .9),
  'automatic', 'at or above it, the category, marked automatic');
select is((select category_source::text || '/' || category_review::text from public.product where id = '10000000-0000-0000-0000-000000000001'),
  'jev/automatic', 'with its source and review state');
select is(public.apply_categorization(current_setting('t.la')::uuid, '20000000-0000-0000-0000-000000000002',
  '10000000-0000-0000-0000-000000000002', (select id from public.category where ledger_id = current_setting('t.la')::uuid and seed_path = 'food.produce.fruit'), .99, 'jev-latest', .9),
  'skipped', 'a product that has a category keeps it');
select is((select status from public.categorize_job where store_item_id = '20000000-0000-0000-0000-000000000001'), 'done', 'the job is done');

-- A phone that pushes the product before it pulled Jev's answer (no source, no category) doesn't erase it.
reset role;
set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-00000000000a", "role": "authenticated"}';
update public.product set name = 'Maçã', category_id = null, category_source = null, category_review = null
  where id = '10000000-0000-0000-0000-000000000001';
select is((select name || ' ' || (select seed_path from public.category c where c.ledger_id = p.ledger_id and c.id = p.category_id) from public.product p
           where id = '10000000-0000-0000-0000-000000000001'), 'Maçã food.produce.fruit',
          'an unsourced write keeps the category and still changes the rest');


-- Readings (D108).
reset role;
set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-00000000000a", "role": "authenticated"}';
select throws_ok($$select public.apply_reading(null, null, null, null, null, null, false, null, null, null, null, null, .9)$$,
  '42501', null, 'users can''t write readings');
reset role;
update public.store_item set parsed_size_e6 = 500000, parsed_size_unit = 'kg' where id = '20000000-0000-0000-0000-000000000002';
set local role service_role;
select is(public.apply_reading(current_setting('t.la')::uuid, '20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
  'weight', 'parser', 1, false, null, null, null, null, 'jev-latest', .9), 'sold_by', 'the unit''s own answer is kept');
select is(public.apply_reading(current_setting('t.la')::uuid, '20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002',
  null, null, null, true, 10000000, 'kg', 1, .04, 'jev-latest', .9), 'nothing', 'a size Jev all but rejects isn''t suggested');
select is(public.apply_reading(current_setting('t.la')::uuid, '20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002',
  'unit', 'jev', .6, true, 250000, 'kg', 1, .5, 'jev-latest', .9), 'size_suggested',
  'an unsure way of selling isn''t; an unsure size becomes a suggestion');
select is((select coalesce(sold_by, '-') || ' ' || coalesce(read_size_source, '-') from public.store_item where id = '20000000-0000-0000-0000-000000000002'),
  '- -', 'and neither is applied');
select is((select size_suggestion_e6 || ' ' || size_suggestion_model from public.product where id = '10000000-0000-0000-0000-000000000002'),
  '250000 jev-latest', 'the suggestion is on the product');
select is(public.apply_reading(current_setting('t.la')::uuid, '20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002',
  'unit', 'jev', .95, true, null, null, 4, .97, 'jev-latest', .9), 'sold_by size', 'sure answers are applied');
select is((select sold_by || ' ' || read_size_source || ' ' || coalesce(read_size_e6::text, 'none') || ' ×' || read_pack_count
           from public.store_item where id = '20000000-0000-0000-0000-000000000002'), 'unit jev none ×4',
  'a sure "no weight or volume" included, with its pack count');
select is((select sold_by || '/' || sold_by_source from public.store_item where id = '20000000-0000-0000-0000-000000000001'),
  'weight/parser', 'the parser''s reading stays on the other item');

select * from finish();
rollback;
