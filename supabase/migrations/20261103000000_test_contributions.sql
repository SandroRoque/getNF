-- Photos and pages sent by testers during the closed test (D161). The app sends them through the `contribute` Edge
-- Function, signed in or not, under a random install id; nothing here is readable or writable through the Data API or
-- Storage by anyone but the service role (the function, and the developer's tools). Kept while the test lasts.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('contributions', 'contributions', false, 2097152, array['image/webp', 'text/html', 'application/json']);
-- No policy on storage.objects for this bucket: only the service role reaches it.

-- One row per file received: what the function's limits count, and what the developer's tools list.
create table public.contribution_file (
  install_id uuid not null,
  item_id uuid not null,   -- one capture, or one page: the files sent together
  name text not null check (name ~ '^(meta\.json|page\.html|p[0-9]{2}\.webp)$'),
  bytes integer not null check (bytes between 1 and 2097152),
  received_at timestamptz not null default now(),
  primary key (install_id, item_id, name)
);
create index contribution_file_received on public.contribution_file (received_at);

alter table public.contribution_file enable row level security;
-- compat: the revoke is on a new table no client uses; codes 2 and 3 never touch it (pgTAP, local).
revoke all on public.contribution_file from anon, authenticated;
grant select, insert, update, delete on public.contribution_file to service_role;
