-- Deleting a receipt (T-50, D194). The receipt row stays as a tombstone (`deleted`, since the first schema) so every
-- phone's pull sees the removal; its lines, photo rows and scan marks go. The phone deletes the Storage objects (page,
-- photos, masks) that delete_receipt returns.
--
-- A tombstone is final against older copies: a phone that still holds the receipt and sends it again (a re-read page,
-- before it pulled the removal) changes nothing, and its lines and photos are dropped. Only a scan made after the
-- deletion (captured_at later than deleted_at) brings the receipt back.
-- compat: the revoke is on delete_receipt, new here; builds before T-50 never call it, and their resent lines and
-- photos for a deleted receipt are dropped as above (supabase/tests/database).

alter table public.receipt add column deleted_at timestamptz;

create function public.delete_receipt(lid uuid, receipt_chave text, at timestamptz) returns text[]
language plpgsql security invoker set search_path = '' as $$
declare
  -- A phone clock ahead of the server's would otherwise refuse a scan made right after.
  t timestamptz := least(at, clock_timestamp());
  files text[];
begin
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
revoke all on function public.delete_receipt(uuid, text, timestamptz) from public, anon;
grant execute on function public.delete_receipt(uuid, text, timestamptz) to authenticated;

-- Fires before keep_page (triggers run in name order).
create function public.keep_deleted() returns trigger language plpgsql as $$
begin
  if not old.deleted then return new; end if;
  if new.captured_at > old.deleted_at then
    new.deleted := false; new.deleted_at := null;
    return new;
  end if;
  return null; -- an older copy, or delete_receipt again: the tombstone stands
end $$;
create trigger keep_deleted before update on public.receipt for each row execute function public.keep_deleted();

-- A deleted receipt's page is gone; a scan that brings it back starts without one.
create or replace function public.keep_page() returns trigger language plpgsql as $$
begin
  if old.page_path is not null and new.page_path is null and not old.deleted and not new.deleted then
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

-- Lines, photos and scan marks of a deleted receipt, sent by a phone that hasn't pulled the removal yet: dropped.
create function public.skip_deleted_receipt() returns trigger language plpgsql set search_path = '' as $$
begin
  if exists (select 1 from public.receipt r where r.ledger_id = new.ledger_id and r.chave = new.chave and r.deleted) then
    return null;
  end if;
  return new;
end $$;
create trigger skip_deleted_receipt before insert or update on public.receipt_line
  for each row execute function public.skip_deleted_receipt();
create trigger skip_deleted_receipt before insert or update on public.receipt_photo
  for each row execute function public.skip_deleted_receipt();
create trigger skip_deleted_receipt before insert or update on public.receipt_scan
  for each row execute function public.skip_deleted_receipt();
