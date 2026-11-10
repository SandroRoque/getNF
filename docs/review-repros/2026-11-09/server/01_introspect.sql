-- Read-only introspection of the local stack (audit 2026-11-09, server area).
\pset pager off
\echo == functions in public: definer, search_path, ACL
select p.proname, pg_get_function_identity_arguments(p.oid) args, p.prosecdef definer, p.proconfig, p.proacl
from pg_proc p where p.pronamespace='public'::regnamespace order by 1;
\echo == tables: RLS, ACL
select c.relname, c.relkind, c.relrowsecurity rls, c.relforcerowsecurity force, c.relacl, c.reloptions
from pg_class c where c.relnamespace='public'::regnamespace and c.relkind in ('r','v','m','S') order by 2,1;
\echo == column ACLs
select attrelid::regclass, attname, attacl from pg_attribute where attacl is not null and attrelid::regclass::text !~ '^(pg_|information_schema)';
\echo == policies
select schemaname, tablename, policyname, cmd, roles, qual, with_check from pg_policies where schemaname in ('public','storage') order by 1,2,3;
\echo == triggers
select tgrelid::regclass, tgname, pg_get_triggerdef(oid) from pg_trigger where not tgisinternal and tgrelid::regclass::text ~ '^(public\.|auth\.users|[a-z_]+$)' order by 1,2;
\echo == buckets
select id, public, file_size_limit, allowed_mime_types from storage.buckets;
\echo == publications
select * from pg_publication_tables;
\echo == cron
select jobname, schedule, command from cron.job;
\echo == default privileges
select defaclrole::regrole, defaclnamespace::regnamespace, defaclobjtype, defaclacl from pg_default_acl;
