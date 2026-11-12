-- A category can't end up under itself (T-32, D187): neither by a member's move nor by the seed's sync.
-- Run: npx supabase test db
begin;
select plan(7);

insert into auth.users (id, email) values ('00000000-0000-0000-0000-0000000000c7', 'cy@example.test');
set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000c7", "role": "authenticated"}';
select set_config('t.l', (select ledger_id::text from public.ledger_member where user_id = auth.uid()), false);
insert into public.category (ledger_id, id, label) values
  (current_setting('t.l')::uuid, 'c0000000-0000-0000-0000-00000000000a', 'A'),
  (current_setting('t.l')::uuid, 'c0000000-0000-0000-0000-00000000000b', 'B');

select lives_ok($$ update public.category set parent_id = 'c0000000-0000-0000-0000-00000000000a'
  where id = 'c0000000-0000-0000-0000-00000000000b' $$, 'B under A');
select throws_ok($$ update public.category set parent_id = 'c0000000-0000-0000-0000-00000000000b'
  where id = 'c0000000-0000-0000-0000-00000000000a' $$, '23514', null, 'A under B, which is under A, is refused');
select throws_ok($$ update public.category set parent_id = id where id = 'c0000000-0000-0000-0000-00000000000a' $$,
  '23514', null, 'a category under itself is refused');
-- The phone's field upsert (D164) of a parent: refused the same way.
select throws_ok($$
  insert into public.category (ledger_id, id, parent_id)
  values (current_setting('t.l')::uuid, 'c0000000-0000-0000-0000-00000000000a', 'c0000000-0000-0000-0000-00000000000b')
  on conflict (ledger_id, id) do update set parent_id = excluded.parent_id
$$, '23514', null, 'and as the phone sends it');

-- Seed leaves X and Y with different parents. The owner moves X under Y; then the seed moves Y under X.
select set_config('t.x', (select n.path from public.taxonomy_node n where n.parent_path is not null
  and not exists (select 1 from public.taxonomy_node c where c.parent_path = n.path) order by n.path limit 1), false);
select set_config('t.y', (select n.path from public.taxonomy_node n where n.parent_path is not null
  and n.parent_path <> (select parent_path from public.taxonomy_node where path = current_setting('t.x'))
  and not exists (select 1 from public.taxonomy_node c where c.parent_path = n.path) order by n.path desc limit 1), false);
update public.category c set parent_id = (select id from public.category where ledger_id = c.ledger_id and seed_path = current_setting('t.y')),
  user_modified = true
where c.ledger_id = current_setting('t.l')::uuid and c.seed_path = current_setting('t.x');
reset role;
update public.taxonomy_node set parent_path = current_setting('t.x') where path = current_setting('t.y');
-- And a harmless seed change next to it, which must still apply.
update public.taxonomy_node set label = label || ' (novo)' where path = current_setting('t.x');
set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000c7", "role": "authenticated"}';

select lives_ok($$ select public.sync_taxonomy(current_setting('t.l')::uuid) $$, 'the seed''s sync still runs');
select isnt((select p.seed_path from public.category c join public.category p on p.ledger_id = c.ledger_id and p.id = c.parent_id
  where c.ledger_id = current_setting('t.l')::uuid and c.seed_path = current_setting('t.y')), current_setting('t.x'),
  'but doesn''t put Y under X, which the owner put under Y');
select is((select parent_id from public.category where ledger_id = current_setting('t.l')::uuid and seed_path = current_setting('t.x')),
  (select id from public.category where ledger_id = current_setting('t.l')::uuid and seed_path = current_setting('t.y')),
  'X stays where the owner put it');

select * from finish();
rollback;
