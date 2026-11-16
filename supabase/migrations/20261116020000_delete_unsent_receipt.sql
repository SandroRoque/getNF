-- Deleting a receipt the caderneta doesn't have yet (D210, found by the sync simulation). A phone that scans a
-- receipt and deletes it before ever syncing called delete_receipt for a row that wasn't there: nothing was written,
-- so another member's older copy of the same receipt, sent afterwards, stayed, and came back to the phone that had
-- deleted it. Whether a deletion held depended on which phone synced first. Now it leaves the tombstone either way:
-- an older copy sent later changes nothing, and a scan made after the deletion still brings the receipt back (D194).
-- compat: delete_receipt keeps its signature and its answer; a tombstone for a receipt a phone doesn't hold is
-- skipped by every build's pull (supabase/tests/database/receipt_deletion_unsent.test.sql).

create or replace function public.delete_receipt(lid uuid, receipt_chave text, at timestamptz) returns text[]
language plpgsql security invoker set search_path = '' as $$
declare
  -- A phone clock ahead of the server's would otherwise refuse a scan made right after.
  t timestamptz := least(at, clock_timestamp());
  files text[];
begin
  -- Someone outside the caderneta sees no row and may write none: nothing, as before.
  if lid not in (select public.my_ledgers()) then return '{}'; end if;
  -- Never sent here: only the tombstone, for the copies other phones may still send.
  if not exists (select 1 from public.receipt where ledger_id = lid and chave = receipt_chave) then
    insert into public.receipt (ledger_id, chave, captured_at, deleted, deleted_at) values (lid, receipt_chave, t, true, t)
    on conflict (ledger_id, chave) do nothing;
    return '{}';
  end if;
  -- Nothing to do for a receipt already deleted, or scanned again after this deletion (a phone that was offline).
  if not exists (select 1 from public.receipt where ledger_id = lid and chave = receipt_chave and not deleted
                 and captured_at <= t) then
    return '{}';
  end if;
  select coalesce(array_agg(f), '{}') into files from (
    select page_path f from public.receipt where ledger_id = lid and chave = receipt_chave
    union select object_path from public.receipt_photo where ledger_id = lid and chave = receipt_chave
    union select mask_path from public.receipt_photo where ledger_id = lid and chave = receipt_chave
  ) paths where f is not null;
  delete from public.receipt_line where ledger_id = lid and chave = receipt_chave;
  delete from public.receipt_photo where ledger_id = lid and chave = receipt_chave;
  delete from public.receipt_scan where ledger_id = lid and chave = receipt_chave;
  update public.receipt set deleted = true, deleted_at = t, page_path = null, page_url = null, page_fetched_at = null,
    fetched_by = null, fetch_claimed_by = null, fetch_claimed_until = null
  where ledger_id = lid and chave = receipt_chave;
  -- The Storage objects to delete: the phone deletes them after this returns.
  return files;
end $$;
