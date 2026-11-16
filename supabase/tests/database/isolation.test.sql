-- One caderneta's rows and files, seen from outside it (D104, D208): every table that belongs to a caderneta, and
-- Storage. The tables are read from the catalog, so a new one must be given a row here before this passes.
-- Run: npx supabase test db
begin;
select plan(27);

insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-0000000001a1', 'ana@example.test'),
  ('00000000-0000-0000-0000-0000000001b1', 'bia@example.test'),
  ('00000000-0000-0000-0000-0000000001d1', 'davi@example.test');

-- How many of a caderneta's rows the caller sees in a table (none, if the table isn't theirs to read at all).
create function public.t_seen(tbl text, lid uuid) returns bigint language plpgsql as $$
declare n bigint;
begin
  execute format('select count(*) from public.%I where ledger_id = $1', tbl) into n using lid;
  return n;
exception when insufficient_privilege then return 0;
end $$;
create function public.t_tables() returns setof text language sql as $$
  select c.relname::text from pg_class c join pg_attribute a on a.attrelid = c.oid and a.attname = 'ledger_id' and not a.attisdropped
  where c.relnamespace = 'public'::regnamespace and c.relkind = 'r' order by 1 $$;
-- Deletes every object the caller may delete (Storage refuses a plain delete unless asked), and says how many went.
create function public.t_delete_objects() returns bigint language plpgsql as $$
declare n bigint;
begin
  perform set_config('storage.allow_delete_query', 'true', true);
  delete from storage.objects where name like current_setting('t.casa') || '/%' or name like 'isolation-test/%';
  get diagnostics n = row_count;
  return n;
end $$;

-- Ana's "Casa", with Bia as a member and an invite still open. Davi is outside.
set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000001a1", "role": "authenticated"}';
select set_config('t.casa', public.create_ledger('Casa')::text, false);
select set_config('t.inv', public.create_invite(current_setting('t.casa')::uuid, 'member'), false);
select set_config('t.open', public.create_invite(current_setting('t.casa')::uuid, 'member'), false);
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000001b1", "role": "authenticated"}';
select public.join_ledger(current_setting('t.inv'));
select set_config('t.c', '42261011222333000181650010000000011000000010', false);

-- A row in every table of the caderneta.
reset role;
insert into public.issuer (ledger_id, cnpj, name) values (current_setting('t.casa')::uuid, '11222333000181', 'Mercado Teste');
insert into public.product (ledger_id, id, name) values (current_setting('t.casa')::uuid, '10000000-0000-0000-0000-0000000001a1', 'Maçã');
insert into public.tag (ledger_id, id, slug) values (current_setting('t.casa')::uuid, '30000000-0000-0000-0000-0000000001a1', 'feira');
insert into public.product_tag (ledger_id, product_id, tag_id)
  values (current_setting('t.casa')::uuid, '10000000-0000-0000-0000-0000000001a1', '30000000-0000-0000-0000-0000000001a1');
insert into public.store_item (ledger_id, id, code_kind, code, store_cnpj, description, product_id)
  values (current_setting('t.casa')::uuid, '20000000-0000-0000-0000-0000000001a1', 'store', '243', '11222333000181', 'MACA NAC KG',
          '10000000-0000-0000-0000-0000000001a1');
insert into public.link_suggestion (ledger_id, store_item_id, product_id)
  values (current_setting('t.casa')::uuid, '20000000-0000-0000-0000-0000000001a1', '10000000-0000-0000-0000-0000000001a1');
insert into public.receipt (ledger_id, chave, captured_at) values (current_setting('t.casa')::uuid, current_setting('t.c'), now());
insert into public.receipt_line (ledger_id, chave, line_no, description, code, qty_e4, unit_price_e4, gross_cents, store_item_id)
  values (current_setting('t.casa')::uuid, current_setting('t.c'), 1, 'MACA NAC KG', '243', 10000, 1000, 100, '20000000-0000-0000-0000-0000000001a1');
insert into public.receipt_photo (ledger_id, chave, local_name, position) values (current_setting('t.casa')::uuid, current_setting('t.c'), 'a.jpg', 0);
insert into public.receipt_scan (ledger_id, chave, user_id, scanned_at)
  values (current_setting('t.casa')::uuid, current_setting('t.c'), '00000000-0000-0000-0000-0000000001a1', now());
insert into public.worker_report (ledger_id, report) values (current_setting('t.casa')::uuid, '{}');
insert into public.category_event (id, ledger_id, op, category_id, at)
  select gen_random_uuid(), ledger_id, 'rename', id, now() from public.category where ledger_id = current_setting('t.casa')::uuid limit 1;
insert into public.audit_log (ledger_id, product_id, agree, model)
  values (current_setting('t.casa')::uuid, '10000000-0000-0000-0000-0000000001a1', true, 'test');
insert into public.category_rule (ledger_id, text, model) values (current_setting('t.casa')::uuid, 'MACA', 'test');
insert into public.categorize_job (ledger_id, store_item_id)
  values (current_setting('t.casa')::uuid, '20000000-0000-0000-0000-0000000001a1') on conflict do nothing;
select is_empty($$select t from public.t_tables() t where public.t_seen(t, current_setting('t.casa')::uuid) = 0$$,
                'every table of a caderneta has a row to look for');

-- A member sees it all, except the invites and what only the server uses.
set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000001b1", "role": "authenticated"}';
select results_eq($$select t from public.t_tables() t where public.t_seen(t, current_setting('t.casa')::uuid) = 0$$,
                  $$values ('audit_log'), ('categorize_job'), ('category_rule'), ('ledger_invite')$$,
                  'a member reads every table but the invites, the queue and the worker''s notes');
select throws_ok(format($$insert into public.category_event (id, ledger_id, op, category_id, at)
                          select gen_random_uuid(), ledger_id, 'rename', id, now() from public.category where ledger_id = %L limit 1$$,
                        current_setting('t.casa')), '42501', null, 'a member can''t log a tree edit');

-- A manager logs tree edits, in their own name only.
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000001a1", "role": "authenticated"}';
select is((select count(*) from public.ledger_invite where ledger_id = current_setting('t.casa')::uuid), 2::bigint,
          'a manager sees the invites');
select throws_ok(format($$insert into public.category_event (id, ledger_id, op, category_id, at, actor)
                          select gen_random_uuid(), ledger_id, 'rename', id, now(), '00000000-0000-0000-0000-0000000001b1'
                          from public.category where ledger_id = %L limit 1$$, current_setting('t.casa')),
                 '42501', null, 'a tree edit can''t be logged in someone else''s name');

-- An outsider sees nothing and writes nothing.
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000001d1", "role": "authenticated"}';
select is_empty($$select t from public.t_tables() t where public.t_seen(t, current_setting('t.casa')::uuid) > 0$$,
                'an outsider sees no row of the caderneta, in any table');
select throws_ok(format($$insert into public.issuer (ledger_id, cnpj, name) values (%L, '99999999000191', 'X')$$, current_setting('t.casa')),
                 '42501', null, 'an outsider can''t add a store');
select throws_ok(format($$insert into public.product (ledger_id, name) values (%L, 'X')$$, current_setting('t.casa')),
                 '42501', null, 'nor a product');
select throws_ok(format($$insert into public.tag (ledger_id, slug) values (%L, 'x')$$, current_setting('t.casa')),
                 '42501', null, 'nor a tag');
select throws_ok(format($$insert into public.product_tag (ledger_id, product_id, tag_id)
                          values (%L, '10000000-0000-0000-0000-0000000001a1', '30000000-0000-0000-0000-0000000001a1')$$,
                        current_setting('t.casa')), '42501', null, 'nor tag a product');
select throws_ok(format($$insert into public.store_item (ledger_id, code_kind, code, store_cnpj, description, product_id)
                          values (%L, 'store', '999', '11222333000181', 'X', '10000000-0000-0000-0000-0000000001a1')$$, current_setting('t.casa')),
                 '42501', null, 'nor a store item');
select throws_ok(format($$insert into public.link_suggestion (ledger_id, store_item_id, product_id)
                          values (%L, '20000000-0000-0000-0000-0000000001a1', '10000000-0000-0000-0000-0000000001a1')$$,
                        current_setting('t.casa')), '42501', null, 'nor a link suggestion');
select throws_ok(format($$insert into public.receipt (ledger_id, chave, captured_at)
                          values (%L, '42261011222333000181650010000000021000000020', now())$$, current_setting('t.casa')),
                 '42501', null, 'nor a receipt');
select throws_ok(format($$insert into public.receipt_line (ledger_id, chave, line_no, description, code, qty_e4, unit_price_e4, gross_cents, store_item_id)
                          values (%L, %L, 2, 'X', '1', 10000, 100, 1, '20000000-0000-0000-0000-0000000001a1')$$,
                        current_setting('t.casa'), current_setting('t.c')), '42501', null, 'nor a line on one of its receipts');
select throws_ok(format($$insert into public.receipt_photo (ledger_id, chave, local_name, position) values (%L, %L, 'x.jpg', 1)$$,
                        current_setting('t.casa'), current_setting('t.c')), '42501', null, 'nor a photo');
select throws_ok(format($$insert into public.receipt_scan (ledger_id, chave, scanned_at) values (%L, %L, now())$$,
                        current_setting('t.casa'), current_setting('t.c')), '42501', null, 'nor a scan mark');

-- Storage: a receipts folder per caderneta, for its members; what testers send is nobody's to read.
reset role;
insert into storage.objects (bucket_id, name, owner_id) values
  ('receipts', current_setting('t.casa') || '/' || current_setting('t.c') || '/page.html', '00000000-0000-0000-0000-0000000001a1'),
  ('contributions', current_setting('t.casa') || '/a.webp', '00000000-0000-0000-0000-0000000001b1'),
  ('contributions', 'isolation-test/item-1/a.webp', null);
set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000001d1", "role": "authenticated"}';
select is((select count(*) from storage.objects), 0::bigint, 'an outsider sees no file');
select throws_ok(format($$insert into storage.objects (bucket_id, name, owner_id)
                          values ('receipts', %L, '00000000-0000-0000-0000-0000000001d1')$$, current_setting('t.casa') || '/x/page.html'),
                 '42501', null, 'and can''t put one in the caderneta''s folder');
select is(public.t_delete_objects(), 0::bigint, 'nor delete any');
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000001b1", "role": "authenticated"}';
select is((select string_agg(bucket_id, ',') from storage.objects), 'receipts',
          'a member sees the caderneta''s files, and nothing of the other bucket even under the caderneta''s name');
select throws_ok(format($$insert into storage.objects (bucket_id, name, owner_id)
                          values ('contributions', %L, '00000000-0000-0000-0000-0000000001b1')$$, current_setting('t.casa') || '/b.webp'),
                 '42501', null, 'a member can''t write to the other bucket');
select lives_ok(format($$insert into storage.objects (bucket_id, name, owner_id)
                         values ('receipts', %L, '00000000-0000-0000-0000-0000000001b1')$$,
                       current_setting('t.casa') || '/' || current_setting('t.c') || '/a.webp'), 'a member adds a file to the caderneta''s folder');
select lives_ok(format($$insert into storage.objects (bucket_id, name, owner_id)
                         values ('receipts', %L, '00000000-0000-0000-0000-0000000001b1')$$,
                       current_setting('t.casa') || '/42261011222333000181650010000000021000000020/page.html'), 'and a page she fetched');
-- Out of the caderneta, Bia still takes back the photo she sent (D209): that file, and nothing else.
reset role;
delete from public.ledger_member where ledger_id = current_setting('t.casa')::uuid and user_id = '00000000-0000-0000-0000-0000000001b1';
set local role authenticated;
select is((select string_agg(split_part(name, '/', -1), ',') from storage.objects), 'a.webp',
          'someone who left sees only the photo they sent: not the pages, not even the one they fetched');
select is(public.t_delete_objects(), 1::bigint, 'and deletes it');
reset role;
select is((select string_agg(bucket_id || ':' || split_part(name, '/', -1), ' ' order by bucket_id, name) from storage.objects
           where name like current_setting('t.casa') || '/%' or name like 'isolation-test/%'),
          'contributions:a.webp contributions:a.webp receipts:page.html receipts:page.html',
          'both pages stay, and the other bucket is untouched, even a file there with her as its owner');
-- A member deletes the caderneta's files.
set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000001a1", "role": "authenticated"}';
select is(public.t_delete_objects(), 2::bigint, 'a member deletes the caderneta''s files');

select * from finish();
rollback;
