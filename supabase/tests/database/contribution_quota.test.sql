-- The contribute function's limits are checked and taken in one call (T-23, D196).
-- Run: npx supabase test db
begin;
select plan(5);

set local role service_role;
select is(public.reserve_contribution('00000000-0000-0000-0000-00000000f0a1', '00000000-0000-0000-0000-00000000c0a1', 'p00.webp', 100, 2, 1000, 100),
  null, 'a file under the limits is taken, and its row written');
select is((select count(*) from public.contribution_file where install_id = '00000000-0000-0000-0000-00000000f0a1'), 1::bigint, 'one row');
select is(public.reserve_contribution('00000000-0000-0000-0000-00000000f0a1', '00000000-0000-0000-0000-00000000c0a1', 'p01.webp', 950, 2, 1000, 100),
  'too much from this install today', 'the day''s bytes hold');
select is(public.reserve_contribution('00000000-0000-0000-0000-00000000f0a1', '00000000-0000-0000-0000-00000000c0a1', 'p01.webp', 100, 2, 1000, 100),
  null, 'the second file fits');
select is(public.reserve_contribution('00000000-0000-0000-0000-00000000f0a1', '00000000-0000-0000-0000-00000000c0a1', 'p02.webp', 100, 2, 1000, 100),
  'too much from this install today', 'the day''s file count holds');

select * from finish();
rollback;
