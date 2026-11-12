-- The contribute function's limits, checked and taken in one step (review 2026-11-04 R19, T-23, D196). It counted the
-- install's files of the last day and everyone's of the last hour, then wrote its row: parallel sends all passed the
-- count before any wrote (331 accepted of a 300 limit with 32 at once). Now the count and the row are one call, under
-- one lock, so the limits hold. One lock for everyone is fine at the closed test's volume, and the hour's limit is
-- global anyway.
-- compat: the revoke is on a new function the Edge Function calls; no client touches it.

create function public.reserve_contribution(install uuid, item uuid, file text, size int,
                                            day_files int, day_bytes bigint, hour_files int) returns text
language plpgsql security definer set search_path = '' as $$
declare
  files int;
  bytes bigint;
begin
  perform pg_advisory_xact_lock(hashtext('contribution_quota'));
  select count(*), coalesce(sum(f.bytes), 0) into files, bytes from public.contribution_file f
  where f.install_id = install and f.received_at > now() - interval '1 day';
  if files >= day_files or bytes + size > day_bytes then return 'too much from this install today'; end if;
  if (select count(*) from public.contribution_file f where f.received_at > now() - interval '1 hour') >= hour_files then
    return 'too much this hour';
  end if;
  insert into public.contribution_file (install_id, item_id, name, bytes, received_at)
    values (install, item, file, size, now())
    on conflict (install_id, item_id, name) do update set bytes = excluded.bytes, received_at = excluded.received_at;
  return null;
end $$;
revoke execute on function public.reserve_contribution(uuid, uuid, text, int, int, bigint, int) from public, anon, authenticated;
grant execute on function public.reserve_contribution(uuid, uuid, text, int, int, bigint, int) to service_role;
