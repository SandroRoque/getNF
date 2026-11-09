-- Withdrawals of the closed test's contributions (D172): only the contribute function (the service role) reaches them.
-- Run: npx supabase test db
begin;
select plan(5);

set local role anon;
select throws_ok($$select * from public.contribution_withdrawal$$, '42501', null, 'signed out, withdrawals are out of reach');
select throws_ok($$select public.contribution_objects('00000000-0000-0000-0000-00000000f001')$$, '42501', null,
                 'and Storage''s list of an install can''t be called');

set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000a1", "role": "authenticated"}';
select throws_ok($$insert into public.contribution_withdrawal (install_id) values ('00000000-0000-0000-0000-00000000f001')$$,
                 '42501', null, 'signed in, the same');
select throws_ok($$select public.contribution_objects('00000000-0000-0000-0000-00000000f001')$$, '42501', null, 'nor the list');

reset role;
set local role service_role;
select is((select count(*) from public.contribution_objects('00000000-0000-0000-0000-00000000f001')), 0::bigint,
          'the service role lists an install''s objects');

select * from finish();
rollback;
