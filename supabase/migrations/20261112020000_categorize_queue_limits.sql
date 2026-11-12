-- The categorize queue's limits (review 2026-11-04 R18, T-22, D196).
-- 1. A job whose runs keep dying was taken up again every 10 minutes without looking at its attempts: it stayed
--    `running` for ever, never `failed`. Now a stale job on its sixth attempt fails at the next claim.
-- 2. A failure reported late, by a run that had lost the job (taken up again after 10 minutes, and maybe done since),
--    put it back to `pending`. Now a failure counts only for the attempt that is still running.
-- The provider calls' timeouts are in the Edge Function. compat: the queue is the Edge Function's alone; the
-- three-argument fail_categorize_job stays (only for a running job) until the function deployed with this is live.

create or replace function public.claim_categorize_jobs(batch int) returns table (
  ledger_id uuid, store_item_id uuid, product_id uuid, code_kind text, code text, description text, attempts int,
  wants_category boolean, unit text, qty_e4 bigint
) language sql security definer set search_path = '' as $$
  with expired as (
    -- A run that died (or outlived the 10 minutes) on a job's last attempt: the job fails here, as a failure reported
    -- by the run would have.
    update public.categorize_job q set status = 'failed', last_error = coalesce(q.last_error, 'the run never answered')
    where q.status = 'running' and q.updated_at < now() - interval '10 minutes' and q.attempts >= 6
  ), eligible as (
    select q.ledger_id, q.store_item_id, q.created_at from public.categorize_job q
    where ((q.status = 'pending' and q.next_try_at <= now())
           or (q.status = 'running' and q.updated_at < now() - interval '10 minutes' and q.attempts < 6))
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

create or replace function public.fail_categorize_job(lid uuid, item uuid, error text) returns void
language sql security definer set search_path = '' as $$
  update public.categorize_job
  set status = case when attempts >= 6 then 'failed' else 'pending' end, last_error = error,
      next_try_at = now() + make_interval(mins => power(2, attempts)::int)
  where ledger_id = lid and store_item_id = item and status = 'running'
$$;

-- The attempt that failed, as claim_categorize_jobs returned it: a run that lost the job changes nothing.
create function public.fail_categorize_job(lid uuid, item uuid, error text, attempt int) returns void
language sql security definer set search_path = '' as $$
  update public.categorize_job
  set status = case when attempts >= 6 then 'failed' else 'pending' end, last_error = error,
      next_try_at = now() + make_interval(mins => power(2, attempts)::int)
  where ledger_id = lid and store_item_id = item and status = 'running' and attempts = attempt
$$;
revoke execute on function public.fail_categorize_job(uuid, uuid, text, int) from public, anon, authenticated;
grant execute on function public.fail_categorize_job(uuid, uuid, text, int) to service_role;
