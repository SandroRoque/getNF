-- Categories form a tree; nothing kept a parent cycle out (review 2026-11-09 P1-5, T-32, D187). Two managers moving
-- categories under each other before syncing both landed, since each phone checked only its own tree and parents
-- merge by field (D164); and sync_taxonomy_for wrote one by itself when the seed moved a node under one the user had
-- moved. A cycle hung Início and Gastos on every member's phone.
-- compat: additive. A client's move that would close a cycle is refused (23514, a 400), which every supported client
-- parks as a refused row (D104, D145); before this, the same move hung the reports.

-- Whether [node] is [ancestor] or lies under it. UNION (not ALL) ends on a cycle already in the data.
create function public.category_is_under(lid uuid, node uuid, ancestor uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  with recursive up(id) as (
    select node
    union
    select c.parent_id from public.category c join up on c.ledger_id = lid and c.id = up.id where c.parent_id is not null
  ) select exists (select 1 from up where id = ancestor)
$$;
revoke execute on function public.category_is_under(uuid, uuid, uuid) from public, anon, authenticated;

create function public.category_refuse_cycle() returns trigger language plpgsql security definer
set search_path = '' as $$
begin
  if new.parent_id is null then return new; end if;
  -- Two moves of one caderneta's tree check one after the other, so concurrent ones can't both pass.
  perform pg_advisory_xact_lock(hashtextextended('category_tree:' || new.ledger_id::text, 0));
  if public.category_is_under(new.ledger_id, new.parent_id, new.id) then
    raise exception 'category % would be under itself', new.id using errcode = '23514';
  end if;
  return new;
end $$;

create trigger category_no_cycle before insert or update of parent_id on public.category
  for each row execute function public.category_refuse_cycle();

-- The seed's moves skip a node the move would put under itself (the user's own moves made that shape); the rest of
-- the seed still applies, instead of the trigger failing every sync_taxonomy for that caderneta.
create or replace function public.sync_taxonomy_for(lid uuid) returns void language plpgsql security definer
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
            is distinct from (excluded.label, excluded.parent_id, excluded.sort)
        and (excluded.parent_id is null or excluded.parent_id is not distinct from public.category.parent_id
             or not public.category_is_under(lid, excluded.parent_id, public.category.id));
  end loop;
  insert into public.tag (ledger_id, slug, description)
  select lid, slug, description from public.taxonomy_tag
  on conflict (ledger_id, slug) do nothing;
end $$;
