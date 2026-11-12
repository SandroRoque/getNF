-- One account could hold up everyone's categorization (review 2026-11-09 P2-8, T-43, D192): every store item queues
-- a job, jobs were claimed oldest first across all cadernetas, 25 a minute, so 2,000 items inserted by one account
-- delayed a tester's new item by over an hour; and a description of any length went to the provider.
-- compat: additive for codes 2–7. The claim is the Edge Function's alone. The limits are far above what the parser
-- writes (an NFC-e's xProd is at most 120 characters and cProd 60; the longest description online is 40), so no
-- supported client meets them; a row over them is refused (23514) and parked like any refused row.

alter table public.store_item
  add constraint store_item_description_length check (length(description) <= 200),
  add constraint store_item_code_length check (length(code) <= 60);

-- Round robin by caderneta: each caderneta's oldest eligible job first, then each one's second, and so on, so a
-- caderneta with thousands waiting takes one slot per round like any other.
create or replace function public.claim_categorize_jobs(batch int) returns table (
  ledger_id uuid, store_item_id uuid, product_id uuid, code_kind text, code text, description text, attempts int,
  wants_category boolean, unit text, qty_e4 bigint
) language sql security definer set search_path = '' as $$
  with eligible as (
    select q.ledger_id, q.store_item_id, q.created_at from public.categorize_job q
    where ((q.status = 'pending' and q.next_try_at <= now())
           or (q.status = 'running' and q.updated_at < now() - interval '10 minutes'))
      and (q.created_at < now() - interval '10 minutes' or exists (
        select 1 from public.receipt_line l where l.ledger_id = q.ledger_id and l.store_item_id = q.store_item_id))
    for update of q skip locked
  ), picked as (
    select e.ledger_id, e.store_item_id from (
      select e.*, row_number() over (partition by e.ledger_id order by e.created_at, e.store_item_id) as turn from eligible e
    ) e order by e.turn, e.created_at, e.ledger_id limit batch
  ), claimed as (
    update public.categorize_job j set status = 'running', attempts = j.attempts + 1
    from public.store_item s
    where s.ledger_id = j.ledger_id and s.id = j.store_item_id
      and (j.ledger_id, j.store_item_id) in (select p.ledger_id, p.store_item_id from picked p)
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
