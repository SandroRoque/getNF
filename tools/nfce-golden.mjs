#!/usr/bin/env node
// Writes what the server's JS parser and derivation make of each fixture, in the phone's units (cents, scale-4
// quantities, epoch millis, millionths for sizes), for the Kotlin port's parity tests (D48).
// Run after changing either parser or adding a fixture:  node tools/nfce-golden.mjs
// Or for local pages that must stay out of git, golden next to each page:  node tools/nfce-golden.mjs <dir>
// then  GETNF_PARITY_DIR=<dir> ./gradlew :app:testDebugUnitTest --tests '*NfceParityTest*'
import { createRequire } from "node:module"
import fs from "node:fs"
import path from "node:path"

const require = createRequire(import.meta.url)
const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..")
const page = require(path.join(root, "server/pb_hooks/lib/nfce_page.js"))
const derive = require(path.join(root, "server/pb_hooks/lib/derive.js"))
const local = process.argv[2] && path.resolve(process.argv[2])
const fixtures = local || path.join(root, "server/test/fixtures")
const out = local || path.join(root, "android/app/src/test/resources/nfce-golden")

const scaled = (x, f) => (x == null ? null : Math.round(x * f))
const cents = (x) => scaled(x, 100)
const e4 = (x) => scaled(x, 1e4)
const millis = (iso) => (iso == null ? null : Date.parse(iso))

fs.mkdirSync(out, { recursive: true })
for (const name of fs.readdirSync(fixtures).filter((f) => f.endsWith(".html")).sort()) {
  let p
  try {
    p = page.parse(fs.readFileSync(path.join(fixtures, name), "utf8"))
  } catch (err) {
    console.log(name, "not parsed:", err.message)
    continue
  }
  const storeCnpj = p.store ? p.store.cnpj : ""
  const lineCents = p.items.map((i) => cents(i.total || 0))
  const discounts = derive.lineDiscounts(cents(p.totals.discount), lineCents, {})
  const golden = {
    version: p.version,
    chave: p.chave,
    store: p.store,
    number: p.number,
    series: p.series,
    issuedAt: millis(p.issued_at),
    authorizationProtocol: p.authorization ? p.authorization.protocol : null,
    authorizedAt: p.authorization ? millis(p.authorization.at) : null,
    items: p.items.map((i, idx) => {
      const size = derive.parseSize(i.description)
      return {
        line: i.line, description: i.description, code: i.code, qtyE4: e4(i.qty), unit: i.unit,
        unitPriceE4: e4(i.unit_price), totalCents: cents(i.total),
        // Derivation, per line.
        serverKey: derive.productKey(i.code, storeCnpj),
        sizeE6: size ? Math.round(size.qty * 1e6) : null, sizeUnit: size ? size.unit : null,
        discountCents: discounts[idx].cents, discountSource: discounts[idx].source,
      }
    }),
    totals: {
      itemsCount: p.totals.items_count ?? null, grossCents: cents(p.totals.gross), discountCents: cents(p.totals.discount),
      surchargeCents: cents(p.totals.surcharge), payableCents: cents(p.totals.payable), changeCents: cents(p.totals.change),
      raw: p.totals.raw,
    },
    payments: p.payments.map((x) => ({ method: x.method, amountCents: cents(x.amount) })),
    taxesEstimate: p.taxes_estimate
      ? { federalCents: cents(p.taxes_estimate.federal), stateCents: cents(p.taxes_estimate.state), municipalCents: cents(p.taxes_estimate.municipal) }
      : null,
    warnings: p.warnings,
  }
  const file = path.join(out, name.replace(/\.html$/, ".json"))
  fs.writeFileSync(file, JSON.stringify(golden, null, 2) + "\n")
  console.log(local ? name : path.relative(root, file), `${p.items.length} items`)
}
