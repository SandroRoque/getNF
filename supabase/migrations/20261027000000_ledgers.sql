-- Cadernetas (D104): every catalog row belongs to a caderneta (`ledger`), not to a login. People and cadernetas are
-- many-to-many through `ledger_member`, with one role per pair. Existing data needs no rewrite: each login becomes a
-- caderneta with the same id, so the old `user_id` values are the new `ledger_id` values and the storage folders
-- (`<uid>/<chave>/…`) are already `<ledger>/<chave>/…`.

create type public.ledger_role as enum ('owner', 'manager', 'member');

create table public.ledger (
  id uuid primary key default gen_random_uuid(),
  name text not null check (length(btrim(name)) between 1 and 60),
  created_by uuid references auth.users on delete set null,
  created_at timestamptz not null default clock_timestamp(),
  updated_at timestamptz not null default clock_timestamp()
);

create table public.ledger_member (
  ledger_id uuid not null references public.ledger on delete cascade,
  user_id uuid not null references auth.users on delete cascade,
  role public.ledger_role not null,
  -- How the others see this person on receipts ("por Ana").
  display_name text not null default '',
  joined_at timestamptz not null default clock_timestamp(),
  updated_at timestamptz not null default clock_timestamp(),
  primary key (ledger_id, user_id)
);
create index on public.ledger_member (user_id);

-- Single use, a week by default. The code is what a person types or opens as a link.
create table public.ledger_invite (
  code text primary key,
  ledger_id uuid not null references public.ledger on delete cascade,
  role public.ledger_role not null check (role <> 'owner'),
  created_by uuid references auth.users on delete set null,
  created_at timestamptz not null default clock_timestamp(),
  expires_at timestamptz not null default clock_timestamp() + interval '7 days',
  used_by uuid references auth.users on delete set null,
  used_at timestamptz
);

-- Append-only history of a caderneta's setup: created, renamed, invited, joined, role changed, left, removed. No screen
-- yet (D104); the category tree has its own log (category_event).
create table public.ledger_event (
  id bigint generated always as identity primary key,
  ledger_id uuid not null references public.ledger on delete cascade,
  actor uuid,
  op text not null,
  payload jsonb not null default '{}',
  at timestamptz not null default clock_timestamp()
);
create index on public.ledger_event (ledger_id, at);

create trigger touch before insert or update on public.ledger for each row execute function public.touch();
create trigger touch before insert or update on public.ledger_member for each row execute function public.touch();

-- Every login so far becomes a caderneta of its own, with the same id.
insert into public.ledger (id, name, created_by) select id, 'Pessoal', id from auth.users;
insert into public.ledger_member (ledger_id, user_id, role, display_name)
select id, id, 'owner', coalesce(nullif(raw_user_meta_data ->> 'name', ''), split_part(coalesce(email, ''), '@', 1))
from auth.users;
insert into public.ledger_event (ledger_id, actor, op) select id, id, 'created' from auth.users;

-- user_id → ledger_id everywhere: same values, now pointing at the caderneta. The composite keys keep their columns.
do $$
declare t text; c text;
begin
  foreach t in array array['issuer', 'receipt', 'receipt_photo', 'category', 'tag', 'product', 'store_item',
                           'product_tag', 'receipt_line', 'categorize_job', 'category_event', 'link_suggestion',
                           'audit_log', 'category_rule', 'worker_report'] loop
    for c in select polname from pg_policy where polrelid = format('public.%I', t)::regclass loop
      execute format('drop policy %I on public.%I', c, t);
    end loop;
    for c in select conname from pg_constraint
             where conrelid = format('public.%I', t)::regclass and confrelid = 'auth.users'::regclass loop
      execute format('alter table public.%I drop constraint %I', t, c);
    end loop;
    execute format('alter table public.%I rename column user_id to ledger_id', t);
    execute format('alter table public.%I alter column ledger_id drop default', t);
    execute format('alter table public.%I add foreign key (ledger_id) references public.ledger on delete cascade', t);
  end loop;
end $$;

-- Ids made from an item's code (D104) are the same in every caderneta, so an id is unique only within one.
do $$
declare t text;
begin
  foreach t in array array['category', 'tag', 'product', 'store_item', 'link_suggestion'] loop
    execute format('alter table public.%I drop constraint %I', t, t || '_pkey');
    execute format('alter table public.%I add primary key (ledger_id, id)', t);
  end loop;
end $$;

-- Who did what. Several members can scan the same receipt; each is a row, so one leaving doesn't touch the others.
create table public.receipt_scan (
  ledger_id uuid not null,
  chave text not null,
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  scanned_at timestamptz not null,
  updated_at timestamptz not null default clock_timestamp(),
  primary key (ledger_id, chave, user_id),
  foreign key (ledger_id, chave) references public.receipt (ledger_id, chave) on delete cascade
);
create index on public.receipt_scan (ledger_id, updated_at);
create trigger touch before insert or update on public.receipt_scan for each row execute function public.touch();
insert into public.receipt_scan (ledger_id, chave, user_id, scanned_at)
select r.ledger_id, r.chave, r.ledger_id, r.captured_at from public.receipt r
where exists (select 1 from auth.users u where u.id = r.ledger_id);

alter table public.receipt_photo add column created_by uuid default auth.uid() references auth.users on delete set null;
update public.receipt_photo p set created_by = p.ledger_id where exists (select 1 from auth.users u where u.id = p.ledger_id);

alter table public.receipt
  add column fetched_by uuid references auth.users on delete set null,
  -- The shared fetch queue (D104): a member claims a receipt before spending a SEFAZ lookup on it.
  add column fetch_claimed_by uuid,
  add column fetch_claimed_until timestamptz;
update public.receipt r set fetched_by = r.ledger_id
where r.page_path is not null and exists (select 1 from auth.users u where u.id = r.ledger_id);

alter table public.category_event add column actor uuid default auth.uid();

-- A receipt's page, once on the server, is never cleared by a write without one (a member who scanned it but hasn't
-- pulled the page yet). Whoever first sends the page is its fetcher.
create function public.keep_page() returns trigger language plpgsql as $$
begin
  if old.page_path is not null and new.page_path is null then
    new.page_path := old.page_path; new.page_url := old.page_url; new.page_fetched_at := old.page_fetched_at;
    new.fetched_by := old.fetched_by; new.issuer_cnpj := old.issuer_cnpj; new.issued_at := old.issued_at;
    new.gross_cents := old.gross_cents; new.discount_cents := old.discount_cents; new.paid_cents := old.paid_cents;
    new.parser_version := old.parser_version; new.parse_error := old.parse_error; new.payments := old.payments;
    new.change_cents := old.change_cents; new.tax_federal_cents := old.tax_federal_cents;
    new.tax_state_cents := old.tax_state_cents; new.tax_municipal_cents := old.tax_municipal_cents;
    new.tax_total_cents := old.tax_total_cents;
  elsif old.page_path is null and new.page_path is not null then
    new.fetched_by := coalesce(new.fetched_by, auth.uid());
    new.fetch_claimed_by := null; new.fetch_claimed_until := null;
  end if;
  return new;
end $$;
create trigger keep_page before update on public.receipt for each row execute function public.keep_page();

-- Membership, for policies. Definer rights: they read ledger_member whatever its own policies say.
create function public.my_ledgers() returns setof uuid language sql stable security definer
set search_path = '' as $$ select ledger_id from public.ledger_member where user_id = (select auth.uid()) $$;
create function public.my_managed_ledgers() returns setof uuid language sql stable security definer
set search_path = '' as $$
  select ledger_id from public.ledger_member where user_id = (select auth.uid()) and role in ('owner', 'manager')
$$;
create function public.my_role(lid uuid) returns public.ledger_role language sql stable security definer
set search_path = '' as $$
  select role from public.ledger_member where ledger_id = lid and user_id = (select auth.uid())
$$;

-- Any member reads and writes the caderneta's data; only managers and owners edit the category tree.
do $$
declare t text;
begin
  foreach t in array array['issuer', 'receipt', 'receipt_photo', 'tag', 'product', 'store_item', 'product_tag',
                           'receipt_line', 'link_suggestion', 'receipt_scan'] loop
    execute format('alter table public.%I enable row level security', t);
    execute format('create policy members on public.%I for all to authenticated
                      using (ledger_id in (select public.my_ledgers()))
                      with check (ledger_id in (select public.my_ledgers()))', t);
  end loop;
end $$;
revoke all on public.receipt_scan from anon;
grant select, insert, update, delete on public.receipt_scan to authenticated;

create policy members_read on public.category for select to authenticated
  using (ledger_id in (select public.my_ledgers()));
create policy managers_edit on public.category for all to authenticated
  using (ledger_id in (select public.my_managed_ledgers()))
  with check (ledger_id in (select public.my_managed_ledgers()));

create policy members_read on public.category_event for select to authenticated
  using (ledger_id in (select public.my_ledgers()));
create policy managers_log on public.category_event for insert to authenticated
  with check (ledger_id in (select public.my_managed_ledgers()) and actor = (select auth.uid()));

create policy members_read on public.worker_report for select to authenticated
  using (ledger_id in (select public.my_ledgers()));

-- The caderneta's own tables are read here and changed only through the functions below.
alter table public.ledger enable row level security;
alter table public.ledger_member enable row level security;
alter table public.ledger_invite enable row level security;
alter table public.ledger_event enable row level security;
create policy members_read on public.ledger for select to authenticated using (id in (select public.my_ledgers()));
create policy members_read on public.ledger_member for select to authenticated
  using (ledger_id in (select public.my_ledgers()));
create policy managers_read on public.ledger_invite for select to authenticated
  using (ledger_id in (select public.my_managed_ledgers()));
create policy members_read on public.ledger_event for select to authenticated
  using (ledger_id in (select public.my_ledgers()));
revoke all on public.ledger, public.ledger_member, public.ledger_invite, public.ledger_event from anon, authenticated;
grant select on public.ledger, public.ledger_member, public.ledger_invite, public.ledger_event to authenticated;

-- Storage: a member reads and writes the caderneta's folder; anyone may delete what they uploaded themselves, even
-- after leaving (leaving deletes the rows first, then the phone deletes the files).
drop policy own_folder on storage.objects;
create policy member_folder on storage.objects for all to authenticated
  using (bucket_id = 'receipts' and (storage.foldername(name))[1] in (select l::text from public.my_ledgers() l))
  with check (bucket_id = 'receipts' and (storage.foldername(name))[1] in (select l::text from public.my_ledgers() l));
create policy own_uploads on storage.objects for delete to authenticated
  using (bucket_id = 'receipts' and owner_id = (select auth.uid())::text);

-- Seeding is per caderneta now.
drop trigger seed_new_user on auth.users;
drop function public.seed_new_user();
drop function public.sync_taxonomy();
drop function public.sync_taxonomy_for(uuid);

create function public.sync_taxonomy_for(lid uuid) returns void language plpgsql security definer
set search_path = '' as $$
declare node record;
begin
  for node in
    with recursive t as (
      select path, label, parent_path, sort, 0 as depth from public.taxonomy_node where parent_path is null
      union all
      select n.path, n.label, n.parent_path, n.sort, t.depth + 1 from public.taxonomy_node n join t on n.parent_path = t.path
    ) select * from t order by depth, sort
  loop
    insert into public.category (ledger_id, seed_path, label, parent_id, sort)
    values (lid, node.path, node.label,
            (select id from public.category where ledger_id = lid and seed_path = node.parent_path), node.sort)
    on conflict (ledger_id, seed_path) do update
      set label = excluded.label, parent_id = excluded.parent_id, sort = excluded.sort
      where not public.category.user_modified and not public.category.deleted
        and (public.category.label, public.category.parent_id, public.category.sort)
            is distinct from (excluded.label, excluded.parent_id, excluded.sort);
  end loop;
  insert into public.tag (ledger_id, slug, description)
  select lid, slug, description from public.taxonomy_tag
  on conflict (ledger_id, slug) do nothing;
end $$;

-- Any member may bring their caderneta up to the seed; the phone calls it before each sync.
create function public.sync_taxonomy(lid uuid) returns void language plpgsql security definer
set search_path = '' as $$
begin
  if public.my_role(lid) is null then raise exception 'not a member' using errcode = '42501'; end if;
  perform public.sync_taxonomy_for(lid);
end $$;

create function public.default_display_name(uid uuid) returns text language sql stable security definer
set search_path = '' as $$
  select coalesce(nullif(raw_user_meta_data ->> 'name', ''), split_part(coalesce(email, ''), '@', 1), '')
  from auth.users where id = uid
$$;

create function public.log_ledger(lid uuid, op text, payload jsonb default '{}') returns void language sql
security definer set search_path = '' as $$
  insert into public.ledger_event (ledger_id, actor, op, payload) values (lid, (select auth.uid()), op, payload)
$$;

-- A new login starts with one caderneta of its own ("Pessoal"), seeded. Only a starting point.
create function public.seed_new_user() returns trigger language plpgsql security definer
set search_path = '' as $$
declare lid uuid;
begin
  insert into public.ledger (name, created_by) values ('Pessoal', new.id) returning id into lid;
  insert into public.ledger_member (ledger_id, user_id, role, display_name)
  values (lid, new.id, 'owner', coalesce(nullif(new.raw_user_meta_data ->> 'name', ''),
                                         split_part(coalesce(new.email, ''), '@', 1), ''));
  insert into public.ledger_event (ledger_id, actor, op) values (lid, new.id, 'created');
  perform public.sync_taxonomy_for(lid);
  return new;
end $$;
create trigger seed_new_user after insert on auth.users for each row execute function public.seed_new_user();

create function public.create_ledger(name text) returns uuid language plpgsql security definer
set search_path = '' as $$
declare lid uuid; uid uuid := (select auth.uid());
begin
  if uid is null then raise exception 'not signed in' using errcode = '42501'; end if;
  insert into public.ledger (name, created_by) values (btrim(create_ledger.name), uid) returning id into lid;
  insert into public.ledger_member (ledger_id, user_id, role, display_name)
  values (lid, uid, 'owner', public.default_display_name(uid));
  perform public.log_ledger(lid, 'created', jsonb_build_object('name', btrim(create_ledger.name)));
  perform public.sync_taxonomy_for(lid);
  return lid;
end $$;

create function public.rename_ledger(lid uuid, name text) returns void language plpgsql security definer
set search_path = '' as $$
begin
  if public.my_role(lid) is distinct from 'owner' and public.my_role(lid) is distinct from 'manager' then
    raise exception 'only managers rename' using errcode = '42501';
  end if;
  update public.ledger set name = btrim(rename_ledger.name) where id = lid;
  perform public.log_ledger(lid, 'renamed', jsonb_build_object('name', btrim(rename_ledger.name)));
end $$;

create function public.set_display_name(lid uuid, name text) returns void language plpgsql security definer
set search_path = '' as $$
begin
  update public.ledger_member set display_name = btrim(set_display_name.name)
  where ledger_id = lid and user_id = (select auth.uid());
  if not found then raise exception 'not a member' using errcode = '42501'; end if;
end $$;

-- Managers invite members; only an owner invites a manager.
create function public.create_invite(lid uuid, role public.ledger_role default 'member') returns text
language plpgsql security definer set search_path = '' as $$
declare code text; mine public.ledger_role := public.my_role(lid);
begin
  if mine is null or mine = 'member' or (create_invite.role = 'manager' and mine <> 'owner') or create_invite.role = 'owner' then
    raise exception 'not allowed to invite as %', create_invite.role using errcode = '42501';
  end if;
  code := upper(encode(extensions.gen_random_bytes(5), 'hex'));
  insert into public.ledger_invite (code, ledger_id, role, created_by) values (code, lid, create_invite.role, (select auth.uid()));
  perform public.log_ledger(lid, 'invited', jsonb_build_object('role', create_invite.role));
  return code;
end $$;

create function public.join_ledger(code text) returns uuid language plpgsql security definer
set search_path = '' as $$
declare inv public.ledger_invite; uid uuid := (select auth.uid());
begin
  if uid is null then raise exception 'not signed in' using errcode = '42501'; end if;
  select * into inv from public.ledger_invite i where i.code = upper(btrim(join_ledger.code)) for update;
  if inv is null or inv.used_by is not null or inv.expires_at < now() then
    raise exception 'invite not valid' using errcode = 'P0002';
  end if;
  if exists (select 1 from public.ledger_member where ledger_id = inv.ledger_id and user_id = uid) then
    return inv.ledger_id;
  end if;
  insert into public.ledger_member (ledger_id, user_id, role, display_name)
  values (inv.ledger_id, uid, inv.role, public.default_display_name(uid));
  update public.ledger_invite set used_by = uid, used_at = now() where ledger_invite.code = inv.code;
  perform public.log_ledger(inv.ledger_id, 'joined', jsonb_build_object('user', uid, 'role', inv.role));
  return inv.ledger_id;
end $$;

-- Owners change anyone's role; a caderneta always keeps an owner.
create function public.set_role(lid uuid, uid uuid, role public.ledger_role) returns void language plpgsql
security definer set search_path = '' as $$
begin
  if public.my_role(lid) is distinct from 'owner' then
    raise exception 'only owners change roles' using errcode = '42501';
  end if;
  if set_role.role <> 'owner' and (select m.role from public.ledger_member m where m.ledger_id = lid and m.user_id = uid) = 'owner'
     and (select count(*) from public.ledger_member m where m.ledger_id = lid and m.role = 'owner') = 1 then
    raise exception 'a caderneta needs an owner' using errcode = 'P0001';
  end if;
  update public.ledger_member m set role = set_role.role where m.ledger_id = lid and m.user_id = uid;
  if not found then raise exception 'not a member' using errcode = 'P0002'; end if;
  perform public.log_ledger(lid, 'role', jsonb_build_object('user', uid, 'role', set_role.role));
end $$;

-- What a person takes with them when they stop being a member: their uploaded photos (rows here; the caller deletes
-- the files, which `own_uploads` lets them do afterwards) and their scan marks. Receipts, pages, lines and the
-- catalog stay. Returns the storage paths to delete.
create function public.drop_member(lid uuid, uid uuid, op text) returns setof text language plpgsql
security definer set search_path = '' as $$
begin
  if (select m.role from public.ledger_member m where m.ledger_id = lid and m.user_id = uid) = 'owner'
     and (select count(*) from public.ledger_member m where m.ledger_id = lid and m.role = 'owner') = 1
     and (select count(*) from public.ledger_member m where m.ledger_id = lid) > 1 then
    raise exception 'hand the caderneta over before leaving' using errcode = 'P0001';
  end if;
  return query
    with gone as (
      delete from public.receipt_photo p where p.ledger_id = lid and p.created_by = uid
      returning p.object_path, p.mask_path)
    select x from gone, unnest(array[gone.object_path, gone.mask_path]) x where x is not null;
  delete from public.receipt_scan s where s.ledger_id = lid and s.user_id = uid;
  update public.receipt r set fetched_by = null where r.ledger_id = lid and r.fetched_by = uid;
  update public.receipt r set fetch_claimed_by = null, fetch_claimed_until = null
  where r.ledger_id = lid and r.fetch_claimed_by = uid;
  delete from public.ledger_member m where m.ledger_id = lid and m.user_id = uid;
  insert into public.ledger_event (ledger_id, actor, op, payload)
  values (lid, (select auth.uid()), op, jsonb_build_object('user', uid));
  -- The last member leaving takes the caderneta with them.
  if not exists (select 1 from public.ledger_member m where m.ledger_id = lid) then
    delete from public.ledger where id = lid;
  end if;
end $$;
revoke execute on function public.drop_member(uuid, uuid, text) from public, anon, authenticated;

create function public.leave_ledger(lid uuid) returns setof text language plpgsql security definer
set search_path = '' as $$
begin
  if public.my_role(lid) is null then raise exception 'not a member' using errcode = '42501'; end if;
  return query select public.drop_member(lid, (select auth.uid()), 'left');
end $$;

-- Managers remove members; only an owner removes a manager or another owner.
create function public.remove_member(lid uuid, uid uuid) returns setof text language plpgsql security definer
set search_path = '' as $$
declare mine public.ledger_role := public.my_role(lid);
        theirs public.ledger_role := (select m.role from public.ledger_member m where m.ledger_id = lid and m.user_id = uid);
begin
  if theirs is null then raise exception 'not a member' using errcode = 'P0002'; end if;
  if mine is null or mine = 'member' or (theirs <> 'member' and mine <> 'owner') then
    raise exception 'not allowed to remove a %', theirs using errcode = '42501';
  end if;
  return query select public.drop_member(lid, uid, 'removed');
end $$;

-- Only owners delete a caderneta. The phone deletes its folder's files first, while still a member.
create function public.delete_ledger(lid uuid) returns void language plpgsql security definer
set search_path = '' as $$
begin
  if public.my_role(lid) is distinct from 'owner' then
    raise exception 'only owners delete a caderneta' using errcode = '42501';
  end if;
  delete from public.ledger where id = lid;
end $$;

-- Cadernetas that keep the person from deleting their login: owned by them alone, with other members.
create function public.ledgers_to_hand_over() returns table (ledger_id uuid, name text) language sql stable
security definer set search_path = '' as $$
  select l.id, l.name from public.ledger l
  join public.ledger_member me on me.ledger_id = l.id and me.user_id = (select auth.uid()) and me.role = 'owner'
  where (select count(*) from public.ledger_member m where m.ledger_id = l.id and m.role = 'owner') = 1
    and (select count(*) from public.ledger_member m where m.ledger_id = l.id) > 1
$$;

-- Leaving the app (D104): every caderneta's leaving rules, cadernetas left empty go with them, then the login.
-- The phone first deletes the files: its own uploads, and the folders of cadernetas where it's the only member.
create function public.delete_me() returns void language plpgsql security definer set search_path = '' as $$
declare uid uuid := (select auth.uid()); lid uuid;
begin
  if uid is null then raise exception 'not signed in' using errcode = '42501'; end if;
  if exists (select 1 from public.ledgers_to_hand_over()) then
    raise exception 'hand over your cadernetas first' using errcode = 'P0001';
  end if;
  for lid in select m.ledger_id from public.ledger_member m where m.user_id = uid loop
    perform public.drop_member(lid, uid, 'left');
  end loop;
  delete from auth.users where id = uid;
end $$;

-- The shared fetch queue (D104): before spending a lookup on a receipt the caderneta already knows, a phone claims it
-- for a few minutes. True if this phone may fetch now.
create function public.claim_fetch(lid uuid, chave text) returns boolean language plpgsql security definer
set search_path = '' as $$
declare uid uuid := (select auth.uid());
begin
  if public.my_role(lid) is null then raise exception 'not a member' using errcode = '42501'; end if;
  update public.receipt r set fetch_claimed_by = uid, fetch_claimed_until = now() + interval '5 minutes'
  where r.ledger_id = lid and r.chave = claim_fetch.chave and r.page_path is null
    and (r.fetch_claimed_until is null or r.fetch_claimed_until < now() or r.fetch_claimed_by = uid);
  if found then return true; end if;
  -- Not on the server yet: only this phone knows it, so it may fetch.
  return not exists (select 1 from public.receipt r where r.ledger_id = lid and r.chave = claim_fetch.chave);
end $$;

create function public.release_fetch(lid uuid, chave text) returns void language sql security definer
set search_path = '' as $$
  update public.receipt r set fetch_claimed_by = null, fetch_claimed_until = null
  where r.ledger_id = lid and r.chave = release_fetch.chave and r.fetch_claimed_by = (select auth.uid())
$$;

do $$
declare f text;
begin
  foreach f in array array['my_ledgers()', 'my_managed_ledgers()', 'my_role(uuid)', 'sync_taxonomy_for(uuid)',
                           'sync_taxonomy(uuid)', 'default_display_name(uuid)', 'log_ledger(uuid, text, jsonb)',
                           'create_ledger(text)', 'rename_ledger(uuid, text)', 'set_display_name(uuid, text)',
                           'create_invite(uuid, public.ledger_role)', 'join_ledger(text)',
                           'set_role(uuid, uuid, public.ledger_role)', 'leave_ledger(uuid)', 'remove_member(uuid, uuid)',
                           'delete_ledger(uuid)', 'ledgers_to_hand_over()', 'delete_me()', 'claim_fetch(uuid, text)',
                           'release_fetch(uuid, text)'] loop
    execute format('revoke execute on function public.%s from public, anon', f);
  end loop;
end $$;
revoke execute on function public.sync_taxonomy_for(uuid), public.default_display_name(uuid),
  public.log_ledger(uuid, text, jsonb) from authenticated;
grant execute on function public.my_ledgers(), public.my_managed_ledgers(), public.my_role(uuid),
  public.sync_taxonomy(uuid), public.create_ledger(text), public.rename_ledger(uuid, text),
  public.set_display_name(uuid, text), public.create_invite(uuid, public.ledger_role), public.join_ledger(text),
  public.set_role(uuid, uuid, public.ledger_role), public.leave_ledger(uuid), public.remove_member(uuid, uuid),
  public.delete_ledger(uuid), public.ledgers_to_hand_over(), public.delete_me(), public.claim_fetch(uuid, text),
  public.release_fetch(uuid, text) to authenticated;

-- The categorize queue (D74), per caderneta.
create or replace function public.queue_categorize() returns trigger language plpgsql security definer
set search_path = '' as $$
begin
  if exists (select 1 from public.product p where p.ledger_id = new.ledger_id and p.id = new.product_id
             and p.category_id is null and p.suggestion_category_id is null) then
    insert into public.categorize_job (ledger_id, store_item_id) values (new.ledger_id, new.id) on conflict do nothing;
  end if;
  return new;
end $$;

drop function public.claim_categorize_jobs(int);
create function public.claim_categorize_jobs(batch int) returns table (
  ledger_id uuid, store_item_id uuid, product_id uuid, code_kind text, code text, description text, attempts int
) language sql security definer set search_path = '' as $$
  update public.categorize_job j set status = 'running', attempts = j.attempts + 1
  from public.store_item s
  where s.ledger_id = j.ledger_id and s.id = j.store_item_id
    and (j.ledger_id, j.store_item_id) in (
      select q.ledger_id, q.store_item_id from public.categorize_job q
      where (q.status = 'pending' and q.next_try_at <= now()) or (q.status = 'running' and q.updated_at < now() - interval '10 minutes')
      order by q.created_at limit batch for update skip locked)
  returning j.ledger_id, j.store_item_id, s.product_id, s.code_kind, s.code, s.description, j.attempts
$$;

drop function public.apply_categorization(uuid, uuid, uuid, uuid, real, text, real);
create function public.apply_categorization(
  lid uuid, item uuid, product uuid, category uuid, confidence real, model text, threshold real
) returns text language plpgsql security definer set search_path = '' as $$
declare outcome text;
begin
  if apply_categorization.confidence >= apply_categorization.threshold then
    update public.product p set category_id = apply_categorization.category, category_source = 'jev',
      category_review = 'automatic', category_model = apply_categorization.model,
      category_confidence = apply_categorization.confidence, category_at = now()
    where p.ledger_id = lid and p.id = apply_categorization.product and p.category_id is null;
    outcome := case when found then 'automatic' else 'skipped' end;
  else
    update public.product p set suggestion_category_id = apply_categorization.category,
      suggestion_confidence = apply_categorization.confidence, suggestion_model = apply_categorization.model,
      suggestion_at = now()
    where p.ledger_id = lid and p.id = apply_categorization.product and p.category_id is null and p.suggestion_category_id is null;
    outcome := case when found then 'suggested' else 'skipped' end;
  end if;
  update public.categorize_job j set status = 'done', last_error = null where j.ledger_id = lid and j.store_item_id = item;
  return outcome;
end $$;

drop function public.fail_categorize_job(uuid, uuid, text);
create function public.fail_categorize_job(lid uuid, item uuid, error text) returns void
language sql security definer set search_path = '' as $$
  update public.categorize_job
  set status = case when attempts >= 6 then 'failed' else 'pending' end, last_error = error,
      next_try_at = now() + make_interval(mins => power(2, attempts)::int)
  where ledger_id = lid and store_item_id = item
$$;

revoke execute on function public.claim_categorize_jobs(int),
  public.apply_categorization(uuid, uuid, uuid, uuid, real, text, real), public.fail_categorize_job(uuid, uuid, text)
  from public, anon, authenticated;
grant execute on function public.claim_categorize_jobs(int),
  public.apply_categorization(uuid, uuid, uuid, uuid, real, text, real), public.fail_categorize_job(uuid, uuid, text)
  to service_role;

grant select, insert, update, delete on public.ledger, public.ledger_member, public.ledger_invite, public.ledger_event,
  public.receipt_scan to service_role;
