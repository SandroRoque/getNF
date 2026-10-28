-- Stage 4b's queue and write-back (D70): what gets queued, what an answer changes, and what it may not.
begin;
select plan(12);

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
select is((select count(*) from public.categorize_job where ledger_id = current_setting('t.la')::uuid), 1::bigint, 'a new item of an uncategorized product is queued; a categorized one isn''t');

set local role service_role;
select is((select count(*) from public.claim_categorize_jobs(10)), 1::bigint, 'the function takes the waiting job');
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

select * from finish();
rollback;
