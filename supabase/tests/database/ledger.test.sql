-- Cadernetas (D104): sharing, roles, invites, leaving, deleting a login, and the shared fetch queue.
-- Run: npx supabase test db
begin;
select plan(39);

-- Ana creates "Casa" and invites Bia (manager, by Ana) and Caio (member, by Bia). Davi stays outside.
insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-0000000000a1', 'ana@example.test'),
  ('00000000-0000-0000-0000-0000000000b1', 'bia@example.test'),
  ('00000000-0000-0000-0000-0000000000c1', 'caio@example.test'),
  ('00000000-0000-0000-0000-0000000000d1', 'davi@example.test');

set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000a1", "role": "authenticated"}';
select set_config('t.casa', public.create_ledger('Casa')::text, false);
select is((select count(*) from public.ledger), 2::bigint, 'a new login has "Pessoal"; Ana now also has "Casa"');
select is(public.my_role(current_setting('t.casa')::uuid)::text, 'owner', 'the creator owns it');
select is((select count(*) from public.category where ledger_id = current_setting('t.casa')::uuid),
          (select count(*) from public.taxonomy_node), 'a new caderneta gets the whole taxonomy');
select set_config('t.inv_b', public.create_invite(current_setting('t.casa')::uuid, 'manager'), false);

set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000b1", "role": "authenticated"}';
select is(public.join_ledger(lower(current_setting('t.inv_b'))), current_setting('t.casa')::uuid, 'Bia joins with the code');
select is(public.my_role(current_setting('t.casa')::uuid)::text, 'manager', 'as the invite said');
select throws_ok($$select public.create_invite(current_setting('t.casa')::uuid, 'manager')$$, '42501', null,
                 'a manager can''t make managers');
select set_config('t.inv_c', public.create_invite(current_setting('t.casa')::uuid, 'member'), false);

set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000c1", "role": "authenticated"}';
select throws_ok($$select public.join_ledger(current_setting('t.inv_b'))$$, 'P0002', null, 'an invite works once');
select lives_ok($$select public.join_ledger(current_setting('t.inv_c'))$$, 'Caio joins as a member');
select throws_ok($$select public.create_invite(current_setting('t.casa')::uuid)$$, '42501', null, 'members don''t invite');

-- Ana scans a receipt, Bia and Caio too (the same chave). Lines and the catalog are the caderneta's.
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000a1", "role": "authenticated"}';
insert into public.issuer (ledger_id, cnpj, name) values (current_setting('t.casa')::uuid, '11222333000181', 'Mercado Teste');
insert into public.receipt (ledger_id, chave, captured_at)
  values (current_setting('t.casa')::uuid, '42261011222333000181650010000000011000000010', now());
insert into public.receipt_scan (ledger_id, chave, scanned_at)
  values (current_setting('t.casa')::uuid, '42261011222333000181650010000000011000000010', now());
insert into public.receipt_photo (ledger_id, chave, local_name, position, object_path)
  values (current_setting('t.casa')::uuid, '42261011222333000181650010000000011000000010', 'photo-1.jpg', 0, 'x/ana.webp');

set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000c1", "role": "authenticated"}';
select is(public.claim_fetch(current_setting('t.casa')::uuid, '42261011222333000181650010000000011000000010'), true,
          'Caio claims the page fetch');
insert into public.receipt_scan (ledger_id, chave, scanned_at)
  values (current_setting('t.casa')::uuid, '42261011222333000181650010000000011000000010', now());
insert into public.receipt_photo (ledger_id, chave, local_name, position, object_path, mask_path)
  values (current_setting('t.casa')::uuid, '42261011222333000181650010000000011000000010', 'photo-2.jpg', 0, 'x/caio.webp', 'x/caio.png');

set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000b1", "role": "authenticated"}';
select is(public.claim_fetch(current_setting('t.casa')::uuid, '42261011222333000181650010000000011000000010'), false,
          'Bia can''t fetch it while Caio holds the claim');
insert into public.receipt_scan (ledger_id, chave, scanned_at)
  values (current_setting('t.casa')::uuid, '42261011222333000181650010000000011000000010', now());
select is(public.claim_fetch(current_setting('t.casa')::uuid, '42261011222333000181650010000000099000000010'), true,
          'a receipt the server doesn''t know yet may be fetched by the phone that has it');

set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000c1", "role": "authenticated"}';
update public.receipt set page_path = 'x/page.html', issuer_cnpj = '11222333000181', paid_cents = 747
  where chave = '42261011222333000181650010000000011000000010';
select is((select fetched_by::text || ' ' || coalesce(fetch_claimed_by::text, '-') from public.receipt
           where chave = '42261011222333000181650010000000011000000010'),
          '00000000-0000-0000-0000-0000000000c1 -', 'the page records its fetcher and frees the claim');
update public.receipt set page_path = null, paid_cents = null, captured_at = now()
  where chave = '42261011222333000181650010000000011000000010';
select is((select page_path || ' ' || paid_cents from public.receipt where chave = '42261011222333000181650010000000011000000010'),
          'x/page.html 747', 'a later write without the page doesn''t clear it');

insert into public.product (ledger_id, id) values (current_setting('t.casa')::uuid, '10000000-0000-0000-0000-000000000001');
insert into public.store_item (ledger_id, id, code_kind, code, store_cnpj, description, product_id) values
  (current_setting('t.casa')::uuid, '20000000-0000-0000-0000-000000000001', 'store', '243', '11222333000181', 'MACA', '10000000-0000-0000-0000-000000000001');
insert into public.receipt_line (ledger_id, chave, line_no, description, code, qty_e4, unit_price_e4, gross_cents,
                                 store_item_id, discount_source)
  values (current_setting('t.casa')::uuid, '42261011222333000181650010000000011000000010', 1, 'MACA', '243', 5750, 129900,
          747, '20000000-0000-0000-0000-000000000001', 'prorated');
select pass('a member writes products, store items and lines');

-- Members categorize; only managers edit the tree.
update public.product set category_id = (select id from public.category where ledger_id = current_setting('t.casa')::uuid
  and seed_path = 'food.produce.fruit'), category_source = 'user', category_review = 'confirmed'
  where id = '10000000-0000-0000-0000-000000000001';
select is((select category_source::text from public.product where id = '10000000-0000-0000-0000-000000000001'), 'user',
          'a member picks a product''s category');
select throws_ok($$insert into public.category (ledger_id, label) values (current_setting('t.casa')::uuid, 'Lanches')$$,
                 '42501', null, 'a member can''t add a category');
update public.category set label = 'Frutinhas' where ledger_id = current_setting('t.casa')::uuid and seed_path = 'food.produce.fruit';
select is((select label from public.category where ledger_id = current_setting('t.casa')::uuid and seed_path = 'food.produce.fruit'),
          'Frutas', 'nor rename one');

set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000b1", "role": "authenticated"}';
update public.category set label = 'Frutinhas', user_modified = true
  where ledger_id = current_setting('t.casa')::uuid and seed_path = 'food.produce.fruit';
select is((select label from public.category where ledger_id = current_setting('t.casa')::uuid and seed_path = 'food.produce.fruit'),
          'Frutinhas', 'a manager can');
insert into public.category_event (id, ledger_id, op, category_id, at)
  values (gen_random_uuid(), current_setting('t.casa')::uuid, 'rename',
          (select id from public.category where ledger_id = current_setting('t.casa')::uuid and seed_path = 'food.produce.fruit'), now());
select is((select actor::text from public.category_event), '00000000-0000-0000-0000-0000000000b1', 'and the log says who');

-- Davi sees nothing of Casa.
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000d1", "role": "authenticated"}';
select is((select count(*) from public.receipt), 0::bigint, 'an outsider sees none of the caderneta''s receipts');
select is((select count(*) from public.ledger_member where ledger_id = current_setting('t.casa')::uuid), 0::bigint,
          'nor who is in it');

-- Bia removes Caio; Caio's photo and scan go, the receipt, its page and lines stay. Then Bia leaves.
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000b1", "role": "authenticated"}';
select set_eq($$select public.remove_member(current_setting('t.casa')::uuid, '00000000-0000-0000-0000-0000000000c1')$$,
              array['x/caio.webp', 'x/caio.png'], 'removing a member returns their files to delete');
select lives_ok($$select public.leave_ledger(current_setting('t.casa')::uuid)$$, 'Bia leaves');

set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000a1", "role": "authenticated"}';
select is((select count(*) from public.receipt), 1::bigint, 'one receipt, still there');
select is((select count(*) from public.receipt_line), 1::bigint, 'with its lines');
select is((select string_agg(user_id::text, ',') from public.receipt_scan), '00000000-0000-0000-0000-0000000000a1',
          'only Ana''s scan mark is left');
select is((select string_agg(object_path, ',') from public.receipt_photo), 'x/ana.webp', 'and only her photo');
select is((select page_path || ' ' || coalesce(fetched_by::text, '-') from public.receipt), 'x/page.html -',
          'the page stays, no longer credited to Caio');

-- Ana also runs "Escritório" with Davi. While she's its only owner she can't delete her login; once Davi owns it too,
-- she can, and Escritório stays with him.
select set_config('t.esc', public.create_ledger('Escritório')::text, false);
select set_config('t.inv_d', public.create_invite(current_setting('t.esc')::uuid), false);
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000d1", "role": "authenticated"}';
select lives_ok($$select public.join_ledger(current_setting('t.inv_d'))$$, 'Davi joins Escritório');
select throws_ok($$select public.set_role(current_setting('t.esc')::uuid, '00000000-0000-0000-0000-0000000000d1', 'owner')$$,
                 '42501', null, 'a member can''t make himself owner');
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000a1", "role": "authenticated"}';
select is((select string_agg(name, ',') from public.ledgers_to_hand_over()), 'Escritório', 'Ana must hand Escritório over');
select throws_ok($$select public.delete_me()$$, 'P0001', null, 'until then her login stays');
select throws_ok($$select public.set_role(current_setting('t.esc')::uuid, '00000000-0000-0000-0000-0000000000a1', 'member')$$,
                 'P0001', null, 'and she can''t step down as the last owner');
select lives_ok($$select public.set_role(current_setting('t.esc')::uuid, '00000000-0000-0000-0000-0000000000d1', 'owner')$$,
                'she makes Davi an owner');

-- Deleting the login: Casa (Ana alone now) goes with her; Escritório stays with Davi.
select lives_ok($$select public.delete_me()$$, 'Ana deletes her login');
reset role;
select is((select count(*) from public.ledger where id = current_setting('t.casa')::uuid), 0::bigint,
          'a caderneta left without members is deleted');
select is((select count(*) from auth.users where id = '00000000-0000-0000-0000-0000000000a1'), 0::bigint,
          'and so is the login');
select is((select string_agg(user_id::text || ':' || role, ',') from public.ledger_member
           where ledger_id = current_setting('t.esc')::uuid), '00000000-0000-0000-0000-0000000000d1:owner',
          'Escritório stays, owned by Davi');

select * from finish();
rollback;
