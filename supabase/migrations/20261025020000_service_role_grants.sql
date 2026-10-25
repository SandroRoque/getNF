-- The online project doesn't expose new tables to any role automatically (chosen at creation, D70), and the
-- service role needs table privileges like any other role, RLS aside. The local Supabase grants everything, so only
-- the deployed categorize function noticed (D74). The service role is the server's own: functions, backups.
grant usage on schema public to service_role;
grant select, insert, update, delete on all tables in schema public to service_role;
alter default privileges in schema public grant select, insert, update, delete on tables to service_role;
