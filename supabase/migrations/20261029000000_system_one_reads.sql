-- System-1 reads more than the category (D108): how a store item is sold, and its net size.
--
-- Both are readings of the store item (its description and the way its lines are rung up), written only by the server
-- and pulled by the phone, never pushed. `sold_by` is set by the parser when the unit settles it (KG, L, a fractional
-- quantity) and by Jev when it's sure; the size Jev is sure of replaces the parsed one in prices (a sure "no size"
-- included). An unsure size becomes the product's size suggestion, as the LLM's did (D80). A size is the whole unit
-- as sold (a pack of 12 × 500 ml is 6 l) with the count of what's in it: 12 there, 1 for a single item.

alter table public.store_item
  add column sold_by text check (sold_by in ('weight', 'volume', 'unit')),
  add column sold_by_source text check (sold_by_source in ('parser', 'jev')),
  add column sold_by_confidence real,
  -- Set only by Jev at or above the threshold. Source set with a null size: "this description has no net size" (a
  -- pack count may still be known: "C/4").
  add column read_size_e6 bigint,
  add column read_size_unit text check (read_size_unit in ('kg', 'l')),
  add column read_size_source text check (read_size_source in ('jev')),
  add column read_size_confidence real,
  add column read_pack_count int check (read_pack_count >= 1),
  add column read_model text,
  add column read_at timestamptz;

-- Every new store item is read now, not only those of an uncategorized product; the category question is asked only
-- when the product still has neither a category nor a suggestion.
create or replace function public.queue_categorize() returns trigger language plpgsql security definer
set search_path = '' as $$
begin
  insert into public.categorize_job (ledger_id, store_item_id) values (new.ledger_id, new.id) on conflict do nothing;
  return new;
end $$;

-- A store item arrives before its lines in the same sync; wait for a line (up to 10 minutes) so its unit and quantity
-- are known.
drop function public.claim_categorize_jobs(int);
create function public.claim_categorize_jobs(batch int) returns table (
  ledger_id uuid, store_item_id uuid, product_id uuid, code_kind text, code text, description text, attempts int,
  wants_category boolean, unit text, qty_e4 bigint
) language sql security definer set search_path = '' as $$
  with claimed as (
    update public.categorize_job j set status = 'running', attempts = j.attempts + 1
    from public.store_item s
    where s.ledger_id = j.ledger_id and s.id = j.store_item_id
      and (j.ledger_id, j.store_item_id) in (
        select q.ledger_id, q.store_item_id from public.categorize_job q
        where ((q.status = 'pending' and q.next_try_at <= now())
               or (q.status = 'running' and q.updated_at < now() - interval '10 minutes'))
          and (q.created_at < now() - interval '10 minutes' or exists (
            select 1 from public.receipt_line l where l.ledger_id = q.ledger_id and l.store_item_id = q.store_item_id))
        order by q.created_at limit batch for update skip locked)
    returning j.ledger_id, j.store_item_id, s.product_id, s.code_kind, s.code, s.description, j.attempts
  )
  select c.ledger_id, c.store_item_id, c.product_id, c.code_kind, c.code, c.description, c.attempts,
    p.category_id is null and p.suggestion_category_id is null,
    l.unit, l.qty_e4
  from claimed c
  join public.product p on p.ledger_id = c.ledger_id and p.id = c.product_id
  left join lateral (
    select l.unit, l.qty_e4 from public.receipt_line l
    where l.ledger_id = c.ledger_id and l.store_item_id = c.store_item_id and not l.orphaned
    order by l.chave, l.line_no limit 1
  ) l on true
$$;

-- Writes a store item's readings and finishes its job. `sold_by_source` 'parser' is the unit's own answer; 'jev' is
-- kept only at or above the threshold. `size_asked` false: there was no candidate, nothing to write. A size below the
-- threshold, and at 0.5 or above, becomes the product's suggestion, unless the product has a size by hand or a
-- suggestion already, or the answer is the parser's own reading or "no size".
create function public.apply_reading(
  lid uuid, item uuid, product uuid,
  sold_by text, sold_by_source text, sold_by_confidence real,
  size_asked boolean, size_e6 bigint, size_unit text, pack_count int, size_confidence real,
  model text, threshold real
) returns text language plpgsql security definer set search_path = '' as $$
declare
  s public.store_item;
  outcome text := '';
begin
  select * into s from public.store_item si where si.ledger_id = lid and si.id = item;
  if not found then return 'skipped'; end if;

  if apply_reading.sold_by is not null
     and (apply_reading.sold_by_source = 'parser' or apply_reading.sold_by_confidence >= apply_reading.threshold) then
    update public.store_item si set sold_by = apply_reading.sold_by, sold_by_source = apply_reading.sold_by_source,
      sold_by_confidence = apply_reading.sold_by_confidence, read_model = apply_reading.model, read_at = now()
    where si.ledger_id = lid and si.id = item;
    outcome := 'sold_by';
  end if;

  if apply_reading.size_asked and apply_reading.size_confidence >= apply_reading.threshold then
    update public.store_item si set read_size_e6 = apply_reading.size_e6, read_size_unit = apply_reading.size_unit, read_size_source = 'jev',
      read_pack_count = apply_reading.pack_count, read_size_confidence = apply_reading.size_confidence, read_model = apply_reading.model, read_at = now()
    where si.ledger_id = lid and si.id = item;
    outcome := outcome || ' size';
  -- Below 0.5 Jev is all but saying no (a suitcase "10KG" is its capacity: 0.04): not worth the user's time.
  elsif apply_reading.size_asked and apply_reading.size_confidence >= 0.5 and apply_reading.size_e6 is not null
        and apply_reading.size_e6 is distinct from s.parsed_size_e6 then
    update public.product p set size_suggestion_e6 = apply_reading.size_e6, size_suggestion_unit = apply_reading.size_unit,
      size_suggestion_model = apply_reading.model, size_suggestion_at = now()
    where p.ledger_id = lid and p.id = apply_reading.product and p.size_source is null and p.size_suggestion_e6 is null;
    if found then outcome := outcome || ' size_suggested'; end if;
  end if;

  update public.categorize_job j set status = 'done', last_error = null where j.ledger_id = lid and j.store_item_id = item;
  return coalesce(nullif(trim(outcome), ''), 'nothing');
end $$;

revoke execute on function public.claim_categorize_jobs(int),
  public.apply_reading(uuid, uuid, uuid, text, text, real, boolean, bigint, text, int, real, text, real)
  from public, anon, authenticated;
grant execute on function public.claim_categorize_jobs(int),
  public.apply_reading(uuid, uuid, uuid, text, text, real, boolean, bigint, text, int, real, text, real)
  to service_role;
