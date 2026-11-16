-- D208: the app's roles lose TRUNCATE, REFERENCES and TRIGGER on every table. They came with Supabase's default
-- privileges ("grant all"), nothing uses them, and TRUNCATE isn't subject to row level security. The Data API can't
-- issue a TRUNCATE, so nothing was reachable; this takes the privilege away instead of relying on that.
-- New tables no longer get them either. What a table gives (select, insert, update, delete) is pinned by
-- supabase/tests/database/access_surface.test.sql.
-- compat: no client ever truncates, references or adds triggers (the Data API has no such request); every supported
-- code's requests are unchanged (pgTAP, `SupabaseLocalTest` against the local stack).
do $$
declare t text;
begin
  for t in select c.relname from pg_class c where c.relnamespace = 'public'::regnamespace and c.relkind in ('r', 'p', 'v', 'm') loop
    execute format('revoke truncate, references, trigger on public.%I from anon, authenticated', t);
  end loop;
end $$;
alter default privileges in schema public revoke truncate, references, trigger on tables from anon, authenticated;
