-- The catalog merges by field (D164): a phone sends a changed row's id and only the fields it changed. Postgres checks
-- NOT NULL (and CHECK) on the row an upsert proposes before it finds the conflict, so every NOT NULL column without a
-- default refused that upsert with 23502 even though the row existed (review 2026-11-11 L8, D183). Moving a store item
-- to another product, moving or reordering a category, or changing a tag's slug or description alone never reached
-- the server or the other phones.
-- The defaults only let the proposed row pass those checks; the update writes just the fields sent, so no row ever
-- holds them. A field upsert always meets an existing row: a phone sends one only for a row the server already gave
-- or took (its base), and catalog rows are never deleted one by one (they carry a deleted flag), only with their
-- caderneta, when RLS and the caderneta's foreign key refuse the insert.
-- compat: additive. Codes 2–6 send the same requests, which now pass. Rows they already parked stay parked until they
-- change or are sent again from Ajustes (D145); clients with D183 send them again by themselves.
alter table public.store_item
  alter column code_kind set default 'store',
  alter column code set default '',
  alter column description set default '',
  alter column product_id set default gen_random_uuid();
alter table public.category alter column label set default '';
alter table public.tag
  alter column slug set default '',
  alter column description set default '';
