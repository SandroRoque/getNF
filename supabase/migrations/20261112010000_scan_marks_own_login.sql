-- Who scanned a receipt, and who sent a photo, is the login that wrote the row (T-46). The policies only checked the
-- caderneta, so a member could write a scan mark with another login's id (the receipt then said "por <them>"), and the
-- foreign key's error told whether an id was a login at all.
--
-- A trigger, not a policy: no client sends these columns (their defaults are auth.uid()), and a member may update a
-- photo row someone else sent (the same file archived again), which must keep its sender. With a signed-in caller:
-- an insert takes the caller's id whatever was sent, and an update keeps the row's. Without one (the service role,
-- a foreign key's "on delete" action) nothing changes. Compatible with every client: none sends the columns.

create function public.stamp_writer() returns trigger language plpgsql set search_path = '' as $$
declare
  uid uuid := (select auth.uid());
  col text := tg_argv[0];
begin
  if uid is null then return new; end if;
  if tg_op = 'INSERT' then
    new := jsonb_populate_record(new, jsonb_build_object(col, uid));
  else
    new := jsonb_populate_record(new, jsonb_build_object(col, to_jsonb(old) -> col));
  end if;
  return new;
end $$;

create trigger stamp_writer before insert or update on public.receipt_scan
  for each row execute function public.stamp_writer('user_id');
create trigger stamp_writer before insert or update on public.receipt_photo
  for each row execute function public.stamp_writer('created_by');
