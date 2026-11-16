-- What each role in a caderneta may not do (D104, D208): every refusal of the caderneta functions that
-- ledger.test.sql doesn't already walk through. Run: npx supabase test db
begin;
select plan(27);

insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-0000000002a1', 'ana@example.test'),
  ('00000000-0000-0000-0000-0000000002b1', 'bia@example.test'),
  ('00000000-0000-0000-0000-0000000002c1', 'caio@example.test'),
  ('00000000-0000-0000-0000-0000000002e1', 'eva@example.test'),
  ('00000000-0000-0000-0000-0000000002d1', 'davi@example.test');

-- "Casa": Ana owns it, Bia manages, Caio and Eva are members. Davi is outside. One invite is left unused.
set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000002a1", "role": "authenticated"}';
select set_config('t.casa', public.create_ledger('Casa')::text, false);
select set_config('t.inv_b', public.create_invite(current_setting('t.casa')::uuid, 'manager'), false);
select set_config('t.inv_c', public.create_invite(current_setting('t.casa')::uuid, 'member'), false);
select set_config('t.inv_e', public.create_invite(current_setting('t.casa')::uuid, 'member'), false);
select set_config('t.inv_x', public.create_invite(current_setting('t.casa')::uuid, 'member'), false);
select throws_ok($$select public.create_invite(current_setting('t.casa')::uuid, 'owner')$$, '42501', null,
                 'nobody invites an owner, not even the owner');
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000002b1", "role": "authenticated"}';
select public.join_ledger(current_setting('t.inv_b'));
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000002c1", "role": "authenticated"}';
select public.join_ledger(current_setting('t.inv_c'));
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000002e1", "role": "authenticated"}';
select public.join_ledger(current_setting('t.inv_e'));
reset role;
insert into public.receipt (ledger_id, chave, captured_at)
  values (current_setting('t.casa')::uuid, '42261011222333000181650010000000011000000010', now());
set local role authenticated;

-- A token without a login (none is issued, but the functions don't rely on that).
set local request.jwt.claims = '{"role": "authenticated"}';
select throws_ok($$select public.create_ledger('De ninguém')$$, '42501', null, 'without a login, no caderneta is created');
select throws_ok($$select public.join_ledger(current_setting('t.inv_x'))$$, '42501', null, 'nor joined');
select throws_ok($$select public.delete_me()$$, '42501', null, 'nor a login deleted');

-- The outsider.
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000002d1", "role": "authenticated"}';
select throws_ok($$select public.create_invite(current_setting('t.casa')::uuid)$$, '42501', null, 'an outsider can''t invite');
select throws_ok($$select public.delete_ledger(current_setting('t.casa')::uuid)$$, '42501', null, 'nor delete the caderneta');
select throws_ok($$select public.rename_ledger(current_setting('t.casa')::uuid, 'Minha')$$, '42501', null, 'nor rename it');
select throws_ok($$select public.leave_ledger(current_setting('t.casa')::uuid)$$, '42501', null, 'nor leave it');
select throws_ok($$select public.remove_member(current_setting('t.casa')::uuid, '00000000-0000-0000-0000-0000000002c1')$$,
                 '42501', null, 'nor remove a member');
select throws_ok($$select public.set_display_name(current_setting('t.casa')::uuid, 'Davi')$$, '42501', null, 'nor take a name in it');
select throws_ok($$select public.claim_fetch(current_setting('t.casa')::uuid, '42261011222333000181650010000000011000000010')$$,
                 '42501', null, 'nor claim one of its fetches');
select throws_ok($$select public.join_ledger('ZZZZZZZZZZ')$$, 'P0002', null, 'a code that was never issued is refused');
reset role;
update public.ledger_invite set expires_at = now() - interval '1 minute' where code = current_setting('t.inv_x');
set local role authenticated;
select throws_ok($$select public.join_ledger(current_setting('t.inv_x'))$$, 'P0002', null, 'and so is an expired one');

-- A member.
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000002c1", "role": "authenticated"}';
select throws_ok($$select public.remove_member(current_setting('t.casa')::uuid, '00000000-0000-0000-0000-0000000002e1')$$,
                 '42501', null, 'a member can''t remove another member');
select throws_ok($$select public.rename_ledger(current_setting('t.casa')::uuid, 'Do Caio')$$, '42501', null, 'nor rename the caderneta');
select throws_ok($$select public.delete_ledger(current_setting('t.casa')::uuid)$$, '42501', null, 'nor delete it');
select lives_ok($$select public.set_display_name(current_setting('t.casa')::uuid, 'Caio C.')$$, 'a member names himself');
select is(public.claim_fetch(current_setting('t.casa')::uuid, '42261011222333000181650010000000011000000010'), true,
          'and claims a fetch');

-- A manager.
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000002b1", "role": "authenticated"}';
select throws_ok($$select public.delete_ledger(current_setting('t.casa')::uuid)$$, '42501', null, 'a manager can''t delete the caderneta');
select throws_ok($$select public.remove_member(current_setting('t.casa')::uuid, '00000000-0000-0000-0000-0000000002a1')$$,
                 '42501', null, 'nor remove the owner');
select public.release_fetch(current_setting('t.casa')::uuid, '42261011222333000181650010000000011000000010');
select is((select fetch_claimed_by from public.receipt where ledger_id = current_setting('t.casa')::uuid),
          '00000000-0000-0000-0000-0000000002c1'::uuid, 'nobody releases a claim that is someone else''s');
select is((select string_agg(display_name, ',' order by display_name) filter (where display_name = 'Caio C.')
           from public.ledger_member where ledger_id = current_setting('t.casa')::uuid), 'Caio C.',
          'and a name is set for oneself only');

-- The owner.
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000002a1", "role": "authenticated"}';
select throws_ok($$select public.remove_member(current_setting('t.casa')::uuid, '00000000-0000-0000-0000-0000000002d1')$$,
                 'P0002', null, 'someone who isn''t in the caderneta can''t be removed from it');
select throws_ok($$select public.set_role(current_setting('t.casa')::uuid, '00000000-0000-0000-0000-0000000002d1', 'manager')$$,
                 'P0002', null, 'nor given a role');
select throws_ok($$select public.leave_ledger(current_setting('t.casa')::uuid)$$, 'P0001', null,
                 'the only owner can''t leave while others are in');
select throws_ok($$select public.delete_me()$$, 'P0001', 'hand over your cadernetas first', 'nor delete the login before handing over');

reset role;
select is((select string_agg(right(user_id::text, 2) || ':' || role, ' ' order by user_id) from public.ledger_member
           where ledger_id = current_setting('t.casa')::uuid), 'a1:owner b1:manager c1:member e1:member',
          'after all that, the caderneta is as it was');

select * from finish();
rollback;
