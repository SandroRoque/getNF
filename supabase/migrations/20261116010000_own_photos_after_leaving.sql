-- D209: someone who left a caderneta can delete the photo files they sent. `own_uploads` (D104) allowed the delete,
-- but Storage only deletes what the caller may also read, and a person who left reads nothing of the folder: the
-- request was answered 200 and the files stayed. This lets a person read their own uploads in the receipts bucket,
-- except pages, which stay with the caderneta (a page is a receipt's, whoever fetched it).
-- compat: additive, and it makes the delete every supported code already sends after leave_ledger work (pgTAP
-- `isolation.test.sql`; the Storage API against the local stack: upload, leave, delete, gone).
create policy own_uploads_read on storage.objects for select to authenticated
  using (bucket_id = 'receipts' and owner_id = (select auth.uid())::text and name not like '%/page.html');
