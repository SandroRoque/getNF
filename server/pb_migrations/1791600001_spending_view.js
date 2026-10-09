/// <reference path="../pb_data/types.d.ts" />

// `spending_lines`: read-only view, one row per receipt line with everything reports need, flat.
//   GET /api/collections/spending_lines/records?filter=(category~'beverages.coffee' %26%26 year='2026')
// Time buckets use the store's local time. Receipts so far are all SC (UTC-3, no DST); other states would need
// their own offset (see UF_OFFSET in pb_hooks/lib/nfce_page.js).
// PocketBase's view-query checker only accepts plain columns or function calls; other expressions (||, CASE)
// must be wrapped in parentheses.
// Category filters by prefix catch every level: `category = 'food.meat_fish' OR category LIKE 'food.meat_fish.%'`.
const QUERY = `
SELECT
  i.id                                        AS id,
  i.issued_at                                 AS issued_at,
  date(i.issued_at, '-3 hours')               AS day,
  strftime('%G-W%V', i.issued_at, '-3 hours') AS week,
  strftime('%Y-%m', i.issued_at, '-3 hours')  AS month,
  (strftime('%Y', i.issued_at, '-3 hours') || '-Q' || ((strftime('%m', i.issued_at, '-3 hours') + 2) / 3)) AS quarter,
  strftime('%Y', i.issued_at, '-3 hours')     AS year,
  COALESCE(c.path, '')                        AS category,
  (CASE WHEN c.path IS NULL THEN 'unclassified'
       WHEN instr(c.path, '.') = 0 THEN c.path
       ELSE substr(c.path, 1, instr(c.path, '.') - 1) END) AS category_l1,
  (CASE WHEN c.path IS NULL OR instr(c.path, '.') = 0 THEN ''
       WHEN instr(substr(c.path, instr(c.path, '.') + 1), '.') = 0 THEN c.path
       ELSE substr(c.path, 1, instr(c.path, '.') + instr(substr(c.path, instr(c.path, '.') + 1), '.') - 1) END) AS category_l2,
  p.tags                                      AS tags,
  i.product                                   AS product,
  i.description                               AS description,
  i.qty                                       AS qty,
  i.unit                                      AS unit,
  i.unit_price                                AS unit_price,
  i.gross                                     AS gross,
  i.discount                                  AS discount,
  i.discount_source                           AS discount_source,
  i.net                                       AS net,
  -- Price per kg or litre: bulk items are priced per kg already; packaged ones use the product's size.
  (CASE WHEN upper(i.unit) = 'KG' THEN round(i.net / i.qty, 2)
       WHEN p.size_qty > 0 THEN round(i.net / i.qty / p.size_qty, 2) END) AS net_price_per_base,
  (CASE WHEN upper(i.unit) = 'KG' THEN 'kg' WHEN p.size_qty > 0 THEN p.size_unit ELSE '' END) AS base_unit,
  i.store_cnpj                                AS store_cnpj,
  r.store_name                                AS store_name,
  i.receipt                                   AS receipt
FROM receipt_items i
JOIN receipts r ON r.id = i.receipt
LEFT JOIN products p ON p.id = i.product
LEFT JOIN categories c ON c.id = p.category
`

migrate((app) => {
  const authed = '@request.auth.id != ""'
  app.save(new Collection({ type: "view", name: "spending_lines", listRule: authed, viewRule: authed, viewQuery: QUERY }))
}, (app) => {
  app.delete(app.findCollectionByNameOrId("spending_lines"))
})
