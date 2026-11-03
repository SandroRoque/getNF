-- The closed test's photos and pages (D161): only the contribute function (the service role) reaches them.
-- Run: npx supabase test db
begin;
select plan(7);

select is((select public from storage.buckets where id = 'contributions'), false, 'the bucket is private');
select is((select file_size_limit from storage.buckets where id = 'contributions'), 2097152::bigint, 'a file is at most 2 MB');

set local role anon;
select throws_ok($$select * from public.contribution_file$$, '42501', null, 'signed out, the files'' rows are out of reach');
select throws_ok($$insert into public.contribution_file (install_id, item_id, name, bytes)
                   values ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-00000000c001', 'p00.webp', 10)$$,
                 '42501', null, 'and can''t be written');
select is((select count(*) from storage.objects where bucket_id = 'contributions'), 0::bigint, 'no object is listed');

set local role authenticated;
set local request.jwt.claims = '{"sub": "00000000-0000-0000-0000-0000000000a1", "role": "authenticated"}';
select throws_ok($$select * from public.contribution_file$$, '42501', null, 'signed in, the same');

reset role;
select throws_ok($$insert into public.contribution_file (install_id, item_id, name, bytes)
                   values ('00000000-0000-0000-0000-00000000f001', '00000000-0000-0000-0000-00000000c001', '../x.webp', 10)$$,
                 '23514', null, 'only the app''s file names');

select * from finish();
rollback;
