/// <reference path="../pb_data/types.d.ts" />

// Derives products and item lines from uploaded receipt pages (docs/decisions.md D14). Logic lives in lib/.

// Note: PocketBase runs every handler in an isolated VM, so nothing declared at the top level of this file is
// visible inside them. Shared code goes in lib/ and is require()d per call.

onRecordAfterCreateSuccess((e) => {
  e.next()
  require(`${__hooks}/lib/ingest.js`).onReceiptSaved(e)
}, "receipts")

onRecordAfterUpdateSuccess((e) => {
  e.next()
  require(`${__hooks}/lib/ingest.js`).onReceiptSaved(e)
}, "receipts")

// On start: load taxonomy edits, and re-parse anything parsed by an older parser. Restart the server to apply either.
onBootstrap((e) => {
  e.next()
  const ingest = require(`${__hooks}/lib/ingest.js`)
  const dir = $os.getenv("GETNF_TAXONOMY_DIR") || "/pb/taxonomy"
  try {
    const t = ingest.syncTaxonomy(e.app, dir)
    e.app.logger().info("taxonomy synced", "categories", t.categories, "tags", t.tags, "warnings", t.warnings)
    for (const w of t.warnings) console.log("taxonomy: " + w)
  } catch (err) {
    e.app.logger().error("taxonomy sync failed", "error", String(err))
    console.log("taxonomy sync failed: " + err)
  }
  const parsed = ingest.parseStale(e.app)
  if (Object.keys(parsed).length) console.log("re-parsed receipts: " + JSON.stringify(parsed))
})

// Superuser-only manual trigger: POST /api/getnf/reparse (?all=1 to re-parse everything, not just stale ones).
routerAdd(
  "POST",
  "/api/getnf/reparse",
  (e) => {
    const ingest = require(`${__hooks}/lib/ingest.js`)
    if (e.request.url.query().get("all") !== "1") return e.json(200, ingest.parseStale(e.app))
    const out = {}
    for (const r of e.app.findRecordsByFilter("receipts", "page != ''", "created", 0, 0)) out[r.getString("chave")] = ingest.parseReceipt(e.app, r.id)
    return e.json(200, out)
  },
  $apis.requireSuperuserAuth(),
)
