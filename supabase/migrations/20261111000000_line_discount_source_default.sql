-- A receipt line merges by field (D182, review L1): a phone sends a line's extracted columns and only the corrections
-- it changed. Postgres checks NOT NULL on the row an upsert proposes before it finds the conflict, so a correction
-- column that is NOT NULL needs a default for that upsert to reach the existing row. discount_source was the only one
-- without (discount_cents and discount_review have theirs). Nothing inserts a line on that default: a line the server
-- lacks is always sent whole first.
-- compat: additive. Codes 2–6 always name discount_source; nothing they send changes.
alter table public.receipt_line alter column discount_source set default 'prorated';
