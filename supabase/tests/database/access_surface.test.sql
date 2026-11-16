-- What the app's roles (anon, authenticated) may touch at all, pinned (D208). Supabase grants every new table and
-- function to both by default, so a migration that forgets its revoke opens something without any other test
-- noticing: it fails here. A change to this file is a change to the server's access surface, and says so in review.
-- Run: npx supabase test db
begin;
select plan(9);

select is_empty($$select c.relname from pg_class c
                  where c.relnamespace = 'public'::regnamespace and c.relkind in ('r', 'p') and not c.relrowsecurity$$,
                'every table has row level security on');

select results_eq(
  $$select (table_name || ' ' || grantee || ' ' || string_agg(privilege_type, ',' order by privilege_type))::text collate "default"
    from information_schema.role_table_grants
    where table_schema = 'public' and grantee in ('anon', 'authenticated') group by table_name, grantee order by 1$$,
  $$values ('category authenticated DELETE,INSERT,SELECT,UPDATE'),
           ('category_event authenticated DELETE,INSERT,SELECT,UPDATE'),
           ('issuer authenticated DELETE,INSERT,SELECT,UPDATE'),
           ('ledger authenticated SELECT'),
           ('ledger_event authenticated SELECT'),
           ('ledger_invite authenticated SELECT'),
           ('ledger_member authenticated SELECT'),
           ('link_suggestion authenticated DELETE,INSERT,SELECT,UPDATE'),
           ('product authenticated DELETE,INSERT,SELECT,UPDATE'),
           ('product_tag authenticated DELETE,INSERT,SELECT,UPDATE'),
           ('receipt authenticated DELETE,INSERT,SELECT,UPDATE'),
           ('receipt_line authenticated DELETE,INSERT,SELECT,UPDATE'),
           ('receipt_photo authenticated DELETE,INSERT,SELECT,UPDATE'),
           ('receipt_scan authenticated DELETE,INSERT,SELECT,UPDATE'),
           ('store_item authenticated DELETE,INSERT,SELECT,UPDATE'),
           ('tag authenticated DELETE,INSERT,SELECT,UPDATE'),
           ('taxonomy_node authenticated DELETE,INSERT,SELECT,UPDATE'),
           ('taxonomy_tag authenticated DELETE,INSERT,SELECT,UPDATE'),
           ('worker_report authenticated DELETE,INSERT,SELECT,UPDATE')$$,
  'the tables a signed-in person may touch (row level security then decides which rows); anon touches none');

-- Privileges on single columns, where the table as a whole isn't granted.
select results_eq(
  $$select (p.table_name || ' ' || p.grantee || ' ' || p.privilege_type || ' ' || string_agg(p.column_name, ',' order by p.column_name))::text collate "default"
    from information_schema.column_privileges p
    where p.table_schema = 'public' and p.grantee in ('anon', 'authenticated')
      and not exists (select 1 from information_schema.role_table_grants g where g.table_schema = 'public'
                      and g.table_name = p.table_name and g.grantee = p.grantee and g.privilege_type = p.privilege_type)
    group by p.table_name, p.grantee, p.privilege_type order by 1$$,
  $$values ('feedback anon INSERT about,body,install_id,kind,written_at'),
           ('feedback authenticated INSERT about,body,install_id,kind,written_at')$$,
  'a report may be written, its own columns only, by anyone');

-- Trigger functions are left out: they can't be called, whoever may execute them.
select is_empty($$select p.oid::regprocedure from pg_proc p
                  where p.pronamespace = 'public'::regnamespace and p.prorettype <> 'trigger'::regtype
                    and has_function_privilege('anon', p.oid, 'execute')$$,
                'nobody calls a function without signing in');
select results_eq(
  $$select (p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')') collate "default" from pg_proc p
    where p.pronamespace = 'public'::regnamespace and p.prorettype <> 'trigger'::regtype
      and has_function_privilege('authenticated', p.oid, 'execute') order by 1$$,
  $$values ('claim_fetch(lid uuid, chave text)'), ('create_invite(lid uuid, role ledger_role)'), ('create_ledger(name text)'),
           ('delete_ledger(lid uuid)'), ('delete_me()'),
           ('delete_receipt(lid uuid, receipt_chave text, at timestamp with time zone)'), ('join_ledger(code text)'),
           ('leave_ledger(lid uuid)'), ('ledgers_to_hand_over()'), ('my_ledgers()'), ('my_managed_ledgers()'),
           ('my_role(lid uuid)'), ('release_fetch(lid uuid, chave text)'), ('remove_member(lid uuid, uid uuid)'),
           ('rename_ledger(lid uuid, name text)'), ('set_display_name(lid uuid, name text)'),
           ('set_role(lid uuid, uid uuid, role ledger_role)'), ('sync_taxonomy(lid uuid)')$$,
  'the functions a signed-in person may call; the queue, the quotas and the helpers are the server''s alone');

select is_empty($$select p.oid::regprocedure from pg_proc p
                  where p.pronamespace = 'public'::regnamespace and p.prosecdef
                    and not coalesce(p.proconfig, '{}') @> array['search_path=""']$$,
                'every SECURITY DEFINER function pins an empty search_path');

select results_eq(
  $$select id, public, file_size_limit, allowed_mime_types from storage.buckets order by id$$,
  $$values ('contributions', false, 2097152::bigint, array['image/webp', 'text/html', 'application/json']),
           ('receipts', false, null, null)$$,
  'both buckets are private; what testers send has a size and a type limit');

-- The sync pulls by updated_at: a write that didn't move it would never reach the other phones.
select results_eq(
  $$select c.relname::text collate "default" from pg_trigger t join pg_class c on c.oid = t.tgrelid
    where c.relnamespace = 'public'::regnamespace and t.tgenabled = 'O' and t.tgtype & 16 = 16
      and t.tgfoid in ('public.touch'::regproc, 'public.ticket_touch'::regproc) order by 1$$,
  $$values ('categorize_job'), ('category'), ('issuer'), ('ledger'), ('ledger_member'), ('link_suggestion'), ('product'),
           ('product_tag'), ('receipt'), ('receipt_line'), ('receipt_photo'), ('receipt_scan'), ('store_item'), ('tag'),
           ('ticket')$$,
  'every table that is edited stamps updated_at on each update');
select is_empty($$select c.relname from pg_class c join pg_attribute a on a.attrelid = c.oid and a.attname = 'updated_at'
                  where c.relnamespace = 'public'::regnamespace and c.relkind = 'r'
                    and c.relname not in ('category_event', 'lexicon', 'worker_report') -- written once, never edited by a phone
                    and not exists (select 1 from pg_trigger t where t.tgrelid = c.oid and t.tgenabled = 'O' and t.tgtype & 16 = 16
                                    and t.tgfoid in ('public.touch'::regproc, 'public.ticket_touch'::regproc))$$,
                'and a new table with updated_at has the trigger too');

select * from finish();
rollback;
