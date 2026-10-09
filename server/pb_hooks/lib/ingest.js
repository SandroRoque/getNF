// PocketBase glue: parse uploaded pages into receipt_items/products, and sync taxonomy/ into categories/tags.
// Loaded with require() from receipts.pb.js. Each hook runs in its own JS VM, so keep this stateless.

const page = require(`${__hooks}/lib/nfce_page.js`)
const derive = require(`${__hooks}/lib/derive.js`)

const cents = (x) => Math.round((x || 0) * 100)

/** The page changed since we last parsed it, or the parser got better. */
function needsParse(rec) {
  const file = rec.getString("page")
  return file !== "" && (rec.getString("parsed_page") !== file || rec.getInt("parser_version") !== page.VERSION)
}

function readPage(app, rec) {
  const fsys = app.newFilesystem()
  try {
    const reader = fsys.getReader(rec.baseFilesPath() + "/" + rec.getString("page"))
    try {
      return readerToString(reader)
    } finally {
      reader.close()
    }
  } finally {
    fsys.close()
  }
}

function upsertProduct(app, item, storeCnpj) {
  const key = derive.productKey(item.code, storeCnpj)
  let product
  try {
    product = app.findFirstRecordByData("products", "key", key)
  } catch (_) {
    product = new Record(app.findCollectionByNameOrId("products"))
    product.set("key", key)
    if (key.startsWith("gtin:")) product.set("gtin", item.code)
    else {
      product.set("store_cnpj", storeCnpj)
      product.set("store_code", item.code)
    }
  }
  // Keep the latest wording; fill the size guess unless someone set it by hand.
  product.set("description", item.description)
  const size = derive.parseSize(item.description)
  if (size && product.getString("size_source") !== "manual") {
    product.set("size_qty", size.qty)
    product.set("size_unit", size.unit)
    product.set("size_source", "parsed")
  }
  app.save(product)
  return product
}

/** (Re)builds the item lines and summary fields of one receipt from its saved page. */
function parseReceipt(app, receiptId) {
  const rec = app.findRecordById("receipts", receiptId)
  const pageFile = rec.getString("page")
  let parsed
  try {
    parsed = page.parse(readPage(app, rec))
  } catch (err) {
    rec.set("parse_error", String(err))
    rec.set("parsed_page", pageFile)
    rec.set("parser_version", page.VERSION)
    app.save(rec)
    return { ok: false, error: String(err) }
  }

  app.runInTransaction((tx) => {
    const r = tx.findRecordById("receipts", receiptId)
    const old = tx.findRecordsByFilter("receipt_items", "receipt = {:id}", "line", 0, 0, { id: receiptId })
    // Discounts read from the photos survive a re-parse (matched by line number).
    const photoByLine = {}
    for (const o of old) if (o.getString("discount_source") === "photo") photoByLine[o.getInt("line")] = cents(o.getFloat("discount"))
    for (const o of old) tx.delete(o)

    const storeCnpj = parsed.store ? parsed.store.cnpj : ""
    const lineCents = parsed.items.map((i) => cents(i.total))
    const known = {}
    parsed.items.forEach((i, idx) => {
      if (photoByLine[i.line] != null) known[idx] = photoByLine[i.line]
    })
    const discounts = derive.lineDiscounts(cents(parsed.totals.discount), lineCents, known)

    const itemsCollection = tx.findCollectionByNameOrId("receipt_items")
    parsed.items.forEach((item, idx) => {
      const product = upsertProduct(tx, item, storeCnpj)
      const line = new Record(itemsCollection)
      line.set("receipt", receiptId)
      line.set("line", item.line)
      line.set("product", product.id)
      line.set("description", item.description)
      line.set("code", item.code)
      line.set("qty", item.qty)
      line.set("unit", item.unit)
      line.set("unit_price", item.unit_price)
      line.set("gross", item.total)
      line.set("discount", discounts[idx].cents / 100)
      line.set("discount_source", discounts[idx].source)
      line.set("net", (lineCents[idx] - discounts[idx].cents) / 100)
      line.set("issued_at", parsed.issued_at)
      line.set("store_cnpj", storeCnpj)
      tx.save(line)
    })

    r.set("store_name", parsed.store ? parsed.store.name : "")
    r.set("store_cnpj", storeCnpj)
    r.set("issued_at", parsed.issued_at)
    r.set("total_gross", parsed.totals.gross)
    r.set("discount", parsed.totals.discount)
    r.set("total_paid", parsed.totals.payable != null ? parsed.totals.payable : parsed.totals.gross - parsed.totals.discount)
    r.set("parsed", parsed)
    r.set("parsed_page", pageFile)
    r.set("parser_version", page.VERSION)
    r.set("parse_error", parsed.warnings.length ? "warnings: " + parsed.warnings.join("; ") : "")
    tx.save(r)
  })
  return { ok: true, items: parsed.items.length, warnings: parsed.warnings }
}

/** Parses every receipt whose page is new or was parsed by an older parser version. */
function parseStale(app) {
  const results = {}
  for (const rec of app.findRecordsByFilter("receipts", "page != ''", "created", 0, 0)) {
    if (needsParse(rec)) results[rec.getString("chave")] = parseReceipt(app, rec.id)
  }
  return results
}

function readText(path) {
  return toString($os.readFile(path))
}

/**
 * Makes categories/tags match taxonomy/*.txt. Nodes removed from the file are deleted only if nothing uses them;
 * otherwise they stay and a warning is returned, so assignments are never silently lost.
 */
function syncTaxonomy(app, dir) {
  const nodes = derive.parseCategories(readText(dir + "/categories.txt"))
  const tagDefs = derive.parseTags(readText(dir + "/tags.txt"))
  const warnings = []

  app.runInTransaction((tx) => {
    const cats = tx.findCollectionByNameOrId("categories")
    const byPath = {}
    for (const c of tx.findRecordsByFilter("categories", "", "", 0, 0)) byPath[c.getString("path")] = c
    for (const n of nodes) {
      const c = byPath[n.path] || new Record(cats)
      c.set("path", n.path)
      c.set("label", n.label)
      c.set("order", n.order)
      c.set("parent", n.parent ? byPath[n.parent].id : "")
      tx.save(c)
      byPath[n.path] = c
    }
    const wanted = {}
    for (const n of nodes) wanted[n.path] = true
    for (const path of Object.keys(byPath).filter((p) => !wanted[p]).sort().reverse()) {
      const used = tx.findRecordsByFilter("products", "category = {:id}", "", 1, 0, { id: byPath[path].id }).length
      const children = tx.findRecordsByFilter("categories", "parent = {:id}", "", 1, 0, { id: byPath[path].id }).length
      if (used || children) warnings.push(`category ${path} was removed from categories.txt but is still in use; kept`)
      else tx.delete(byPath[path])
    }

    const tagsCol = tx.findCollectionByNameOrId("tags")
    const bySlug = {}
    for (const t of tx.findRecordsByFilter("tags", "", "", 0, 0)) bySlug[t.getString("slug")] = t
    for (const d of tagDefs) {
      const t = bySlug[d.slug] || new Record(tagsCol)
      t.set("slug", d.slug)
      t.set("description", d.description)
      tx.save(t)
      bySlug[d.slug] = true
    }
    const wantedTags = {}
    for (const d of tagDefs) wantedTags[d.slug] = true
    for (const slug of Object.keys(bySlug).filter((s) => !wantedTags[s])) {
      const t = bySlug[slug]
      if (tx.findRecordsByFilter("products", "tags ~ {:id}", "", 1, 0, { id: t.id }).length) warnings.push(`tag ${slug} removed from tags.txt but in use; kept`)
      else tx.delete(t)
    }
  })
  return { categories: nodes.length, tags: tagDefs.length, warnings }
}

/** Record hook body: parse after an upload. The parse saves the receipt again, which needsParse() then ignores. */
function onReceiptSaved(e) {
  if (!needsParse(e.record)) return
  try {
    const result = parseReceipt(e.app, e.record.id)
    e.app.logger().info("receipt parsed", "chave", e.record.getString("chave"), "items", result.items, "error", result.error || "")
  } catch (err) {
    // The upload itself succeeded; a parser bug shouldn't turn it into an error for the phone.
    e.app.logger().error("receipt parse failed", "chave", e.record.getString("chave"), "error", String(err))
  }
}

module.exports = { needsParse, parseReceipt, parseStale, syncTaxonomy, onReceiptSaved }
