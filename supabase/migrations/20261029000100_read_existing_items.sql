-- Read every existing store item once (D108): until now only items of uncategorized products were queued, and none
-- was asked how it's sold or its size. Apply after the categorize function that asks those questions is deployed.
-- Products that have a category aren't asked it again (claim_categorize_jobs' wants_category).
insert into public.categorize_job (ledger_id, store_item_id)
select s.ledger_id, s.id from public.store_item s where not s.deleted
on conflict (ledger_id, store_item_id) do update
  set status = 'pending', attempts = 0, next_try_at = now(), last_error = null
  where public.categorize_job.status in ('done', 'failed');
