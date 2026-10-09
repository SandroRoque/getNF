/// <reference path="../pb_data/types.d.ts" />

// Derived data (docs/decisions.md D14): categories + tags (synced from taxonomy/), products, receipt item lines,
// and parsed summary fields on receipts. Everything here is rebuilt by pb_hooks from the raw captures.
migrate((app) => {
  const authed = '@request.auth.id != ""'
  const readOnly = { listRule: authed, viewRule: authed, createRule: null, updateRule: null, deleteRule: null }
  const autodates = [
    { name: "created", type: "autodate", onCreate: true, onUpdate: false },
    { name: "updated", type: "autodate", onCreate: true, onUpdate: true },
  ]

  const categories = new Collection({
    type: "base",
    name: "categories",
    ...readOnly,
    fields: [
      { name: "path", type: "text", required: true, presentable: true, pattern: "^[a-z0-9_]+(\\.[a-z0-9_]+)*$" },
      { name: "label", type: "text", required: true },
      { name: "order", type: "number", onlyInt: true },
      ...autodates,
    ],
    indexes: ["CREATE UNIQUE INDEX idx_categories_path ON categories (path)"],
  })
  app.save(categories)
  categories.fields.add(new RelationField({ name: "parent", collectionId: categories.id, maxSelect: 1 }))
  app.save(categories)

  const tags = new Collection({
    type: "base",
    name: "tags",
    ...readOnly,
    fields: [
      { name: "slug", type: "text", required: true, presentable: true, pattern: "^[a-z0-9_]+$" },
      { name: "description", type: "text" },
      ...autodates,
    ],
    indexes: ["CREATE UNIQUE INDEX idx_tags_slug ON tags (slug)"],
  })
  app.save(tags)

  // Categorizing is the one thing users edit here (admin UI now, maybe the app later).
  const products = new Collection({
    type: "base",
    name: "products",
    ...readOnly,
    updateRule: authed,
    fields: [
      // "gtin:<code>" or "store:<cnpj>:<code>"
      { name: "key", type: "text", required: true },
      { name: "description", type: "text", presentable: true },
      { name: "gtin", type: "text" },
      { name: "store_cnpj", type: "text" },
      { name: "store_code", type: "text" },
      { name: "category", type: "relation", collectionId: categories.id, maxSelect: 1 },
      { name: "tags", type: "relation", collectionId: tags.id, maxSelect: 50 },
      // Net size of one unit, for price per kg / per litre. "manual" means don't overwrite with the parsed guess.
      { name: "size_qty", type: "number" },
      { name: "size_unit", type: "select", values: ["kg", "l"], maxSelect: 1 },
      { name: "size_source", type: "select", values: ["parsed", "manual"], maxSelect: 1 },
      ...autodates,
    ],
    indexes: ["CREATE UNIQUE INDEX idx_products_key ON products (key)"],
  })
  app.save(products)

  const receipts = app.findCollectionByNameOrId("receipts")
  receipts.fields.add(
    new TextField({ name: "store_name" }),
    new TextField({ name: "store_cnpj" }),
    new DateField({ name: "issued_at" }),
    new NumberField({ name: "total_gross" }),
    new NumberField({ name: "discount" }),
    new NumberField({ name: "total_paid" }),
    // Full parser output (also keeps what isn't modelled yet: payments, tax estimate, raw totals).
    new JSONField({ name: "parsed", maxSize: 2000000 }),
    new TextField({ name: "parsed_page" }),
    new NumberField({ name: "parser_version", onlyInt: true }),
    new TextField({ name: "parse_error" }),
  )
  app.save(receipts)

  // One row per receipt line: the "spending line" shape meant to be shared with future sources.
  const items = new Collection({
    type: "base",
    name: "receipt_items",
    ...readOnly,
    fields: [
      { name: "receipt", type: "relation", collectionId: receipts.id, maxSelect: 1, required: true, cascadeDelete: true },
      { name: "line", type: "number", onlyInt: true },
      { name: "product", type: "relation", collectionId: products.id, maxSelect: 1 },
      { name: "description", type: "text", presentable: true },
      { name: "code", type: "text" },
      { name: "qty", type: "number" },
      { name: "unit", type: "text" },
      { name: "unit_price", type: "number" },
      { name: "gross", type: "number" },
      { name: "discount", type: "number" },
      { name: "discount_source", type: "select", values: ["prorated", "photo"], maxSelect: 1 },
      { name: "net", type: "number" },
      { name: "issued_at", type: "date" },
      { name: "store_cnpj", type: "text" },
      ...autodates,
    ],
    indexes: [
      "CREATE UNIQUE INDEX idx_receipt_items_line ON receipt_items (receipt, line)",
      "CREATE INDEX idx_receipt_items_product ON receipt_items (product)",
      "CREATE INDEX idx_receipt_items_issued ON receipt_items (issued_at)",
    ],
  })
  app.save(items)
}, (app) => {
  for (const name of ["receipt_items", "products", "tags", "categories"]) app.delete(app.findCollectionByNameOrId(name))
  const receipts = app.findCollectionByNameOrId("receipts")
  for (const f of ["store_name", "store_cnpj", "issued_at", "total_gross", "discount", "total_paid", "parsed", "parsed_page", "parser_version", "parse_error"])
    receipts.fields.removeByName(f)
  app.save(receipts)
})
