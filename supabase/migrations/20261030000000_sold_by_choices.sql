-- How an item is sold, when System-1 isn't sure (D110). Until now an unsure answer was dropped; it becomes the store
-- item's suggestion, which the phone shows as a card, and the user's answer has its own column, pushed by the phone and
-- final (D51). The server never writes `sold_by_user`; the phone never writes the suggestion.

alter table public.store_item
  add column sold_by_suggestion text check (sold_by_suggestion in ('weight', 'volume', 'unit')),
  add column sold_by_suggestion_confidence real,
  add column sold_by_user text check (sold_by_user in ('weight', 'volume', 'unit'));

-- As in 20261029000000, plus: an unsure way of selling becomes the suggestion, unless the item already has an answer
-- (sure, the parser's or the user's); a sure one clears any suggestion.
create or replace function public.apply_reading(
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
      sold_by_confidence = apply_reading.sold_by_confidence, sold_by_suggestion = null, sold_by_suggestion_confidence = null,
      read_model = apply_reading.model, read_at = now()
    where si.ledger_id = lid and si.id = item;
    outcome := 'sold_by';
  elsif apply_reading.sold_by is not null and s.sold_by is null and s.sold_by_user is null then
    update public.store_item si set sold_by_suggestion = apply_reading.sold_by,
      sold_by_suggestion_confidence = apply_reading.sold_by_confidence, read_model = apply_reading.model, read_at = now()
    where si.ledger_id = lid and si.id = item;
    outcome := 'sold_by_suggested';
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

-- The items read before this (D108) whose way of selling was left unwritten: read them again so the unsure answer
-- lands as a suggestion. Apply after the migration above, with the categorize function as deployed for D108.
insert into public.categorize_job (ledger_id, store_item_id)
select s.ledger_id, s.id from public.store_item s where not s.deleted and s.sold_by is null and s.sold_by_user is null
on conflict (ledger_id, store_item_id) do update
  set status = 'pending', attempts = 0, next_try_at = now(), last_error = null
  where public.categorize_job.status in ('done', 'failed');
