const { test } = require("node:test")
const assert = require("node:assert/strict")
const fs = require("node:fs")
const path = require("node:path")
const d = require("../pb_hooks/lib/derive.js")

test("GTIN check digits and store-internal codes", () => {
  assert.equal(d.isGtin("7891079000229"), true)
  assert.equal(d.isGtin("7891079000228"), false) // wrong check digit
  assert.equal(d.isGtin("243"), false)
  assert.equal(d.isGtin("1043570"), false) // 7 digits: store code
  assert.equal(d.isGtin("2000123000005"), false) // restricted circulation (in-store, variable weight)
  assert.equal(d.isGtin("96385074"), true) // GTIN-8
})

test("product keys", () => {
  assert.equal(d.productKey("7891079000229", "11222333000181"), "gtin:7891079000229")
  assert.equal(d.productKey("243", "11222333000181"), "store:11222333000181:243")
})

test("sizes from descriptions", () => {
  assert.deepEqual(d.parseSize("CAFE 3 CORACOES 250G MOG"), { qty: 0.25, unit: "kg" })
  assert.deepEqual(d.parseSize("LEITE ITALAC INTEG 1L"), { qty: 1, unit: "l" })
  assert.deepEqual(d.parseSize("MAC INST NISSIN 85G"), { qty: 0.085, unit: "kg" })
  assert.deepEqual(d.parseSize("PAO HAMB VITAL 300G C G"), { qty: 0.3, unit: "kg" })
  assert.deepEqual(d.parseSize("AGUA MINERAL 6X1,5L"), { qty: 9, unit: "l" })
  assert.deepEqual(d.parseSize("REFRIG 350ML"), { qty: 0.35, unit: "l" })
  assert.deepEqual(d.parseSize("ARROZ 5KG"), { qty: 5, unit: "kg" })
  assert.equal(d.parseSize("MACA NAC KG"), null) // sold by weight: no pack size
  assert.equal(d.parseSize("QJO MUSS CASA QUEIJO 300"), null) // no unit: fix by hand
  assert.equal(d.parseSize("FILTRO PAP BRIGITTA 102"), null)
})

test("allocation adds up exactly and follows the weights", () => {
  const parts = d.allocateCents(50, [562, 298, 298, 298, 298, 298, 698, 364, 599, 375, 498, 249, 249, 249, 249, 249, 2098, 2098, 349, 349])
  assert.equal(parts.reduce((a, b) => a + b, 0), 50)
  assert.deepEqual(parts.slice(16, 18), [10, 10]) // the two coffees: ~19.6% of the total each
  assert.deepEqual(d.allocateCents(1, [100, 100, 100]), [1, 0, 0])
  assert.deepEqual(d.allocateCents(0, [100, 200]), [0, 0])
  assert.deepEqual(d.allocateCents(7, [0, 0]), [7, 0])
  assert.deepEqual(d.allocateCents(5, []), [])
})

test("photo discounts are kept, the rest is prorated over the other lines", () => {
  const out = d.lineDiscounts(50, [1000, 1000, 249, 249], { 2: 10, 3: 10 })
  assert.deepEqual(out, [
    { cents: 15, source: "prorated" },
    { cents: 15, source: "prorated" },
    { cents: 10, source: "photo" },
    { cents: 10, source: "photo" },
  ])
  // Everything known from the photo: nothing left to prorate.
  assert.deepEqual(d.lineDiscounts(20, [500, 500], { 0: 20, 1: 0 }).map((x) => x.cents), [20, 0])
})

test("the real taxonomy files parse", () => {
  const dir = path.join(__dirname, "..", "..", "taxonomy")
  const cats = d.parseCategories(fs.readFileSync(path.join(dir, "categories.txt"), "utf8"))
  const byPath = Object.fromEntries(cats.map((c) => [c.path, c]))
  assert.equal(byPath["food.produce.fruit"].parent, "food.produce")
  assert.equal(byPath["beverages.coffee"].label, "Café")
  assert.equal(byPath["food"].parent, null)
  assert.ok(cats.every((c) => c.parent === null || byPath[c.parent]))
  const tags = d.parseTags(fs.readFileSync(path.join(dir, "tags.txt"), "utf8"))
  assert.ok(tags.some((t) => t.slug === "frozen"))
})

test("taxonomy errors point at the line", () => {
  assert.throws(() => d.parseCategories("food  Food\n   bad  Indent"), /line 2/)
  assert.throws(() => d.parseCategories("a  A\na  Again"), /duplicate a/)
})
