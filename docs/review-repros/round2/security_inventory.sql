-- Read-only schema and grants; no user rows or secrets.
select tablename, rowsecurity from pg_tables where schemaname='public' order by tablename;
select p.proname, pg_get_function_identity_arguments(p.oid) as args,
 has_function_privilege('anon',p.oid,'execute') as anon_execute,
 has_function_privilege('authenticated',p.oid,'execute') as authenticated_execute,
 p.proconfig
from pg_proc p join pg_namespace n on n.oid=p.pronamespace
where n.nspname='public' and p.prosecdef order by p.proname;
select schemaname,tablename,policyname,roles,cmd,qual,with_check from pg_policies
where schemaname in ('public','storage') order by schemaname,tablename,policyname;
