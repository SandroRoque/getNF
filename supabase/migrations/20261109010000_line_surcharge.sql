-- A receipt line's share of the receipt's surcharge (acréscimo, outras despesas), prorated by the phone's parser like
-- the discount (D175, T-13): spending is gross − discount + surcharge.
-- compat: additive, with a default. Codes 2–6 don't send it: their upsert names only the columns they know, so a line
-- they push again keeps the surcharge a newer phone wrote; they ignore it when pulling (unknown keys).
alter table public.receipt_line add column surcharge_cents bigint not null default 0 check (surcharge_cents >= 0);
