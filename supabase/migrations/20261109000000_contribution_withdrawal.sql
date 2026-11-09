-- Withdrawals of the closed test's contributions are final for that install id (D172, T-9, T-10). The contribute
-- function records one before it deletes anything and refuses that id from then on, so a send still in flight when
-- the person turned it off can't land afterwards; the app sends under a new id if the person turns it on again.
-- compat: a new table and function no client reaches (service role only). Codes 4–6 keep their id after turning the
-- contributions back on, so what they send after a withdrawal is refused and dropped (400), never stored.
create table public.contribution_withdrawal (
  install_id uuid primary key,
  withdrawn_at timestamptz not null default now()
);
alter table public.contribution_withdrawal enable row level security;
revoke all on public.contribution_withdrawal from anon, authenticated;
grant select, insert, update, delete on public.contribution_withdrawal to service_role;

-- What Storage holds under an install's folder, whatever contribution_file says: the withdrawal removes these, a page
-- at a time, until none is left (rows can miss an object; listing through the Storage API stops at a page).
create function public.contribution_objects(install uuid, n integer default 1000)
returns setof text language sql stable security definer set search_path = '' as $$
  select o.name from storage.objects o
  where o.bucket_id = 'contributions' and o.name like install::text || '/%'
  order by o.name limit n
$$;
revoke all on function public.contribution_objects(uuid, integer) from public, anon, authenticated;
grant execute on function public.contribution_objects(uuid, integer) to service_role;
