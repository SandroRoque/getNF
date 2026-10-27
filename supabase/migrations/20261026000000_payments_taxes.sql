-- How a receipt was paid and the taxes it includes, read from its page by the phone (docs/decisions.md D96).
-- payments: [{"method": "Cartão de Crédito", "cents": 26852}, ...] in the page's order; a receipt can be paid several
-- ways. Taxes are the estimate the receipt states (Lei 12.741/2012); stores word it freely, so any part may be null.
alter table public.receipt
  add column payments jsonb check (payments is null or jsonb_typeof(payments) = 'array'),
  add column change_cents bigint,
  add column tax_federal_cents bigint,
  add column tax_state_cents bigint,
  add column tax_municipal_cents bigint,
  add column tax_total_cents bigint;
