// Run: node --test "server/test/*.test.js"
const { test } = require("node:test")
const assert = require("node:assert/strict")
const fs = require("node:fs")
const path = require("node:path")
const { parse, num } = require("../pb_hooks/lib/nfce_page.js")

const fixture = (name) => fs.readFileSync(path.join(__dirname, "fixtures", name), "utf8")

test("server HTML: header, items, totals with discount", () => {
  const p = parse(fixture("sc-detalhes-server-html.html"))
  assert.equal(p.chave, "42260111222333000181651090000000011123456784")
  assert.deepEqual(p.store, {
    name: "SUPERMERCADO EXEMPLO LTDA",
    cnpj: "11222333000181",
    address: "RUA EXEMPLO, 100 , , CENTRO , CIDADE EXEMPLO , SC",
  })
  assert.equal(p.issued_at, "2026-01-15T10:00:00-03:00")
  assert.equal(p.series, "109")
  assert.equal(p.items.length, 8)
  assert.deepEqual(p.items[0], { line: 1, description: "MACA NAC KG", code: "243", qty: 0.64, unit: "KG", unit_price: 8.78, total: 5.62 })
  assert.equal(p.items[1].code, "7896014400287")
  assert.equal(p.totals.items_count, 8)
  assert.equal(p.totals.gross, 31.14)
  assert.equal(p.totals.discount, 0.5)
  assert.equal(p.totals.payable, 30.64)
  assert.deepEqual(p.payments, [{ method: "Cartão de Crédito", amount: 30.64 }])
  assert.deepEqual(p.warnings, [])
})

test("WebView DOM (jQuery Mobile markup, &nbsp;, no discount lines)", () => {
  const p = parse(fixture("sc-detalhes-webview-dom.html"))
  assert.equal(p.items.length, 6)
  assert.deepEqual(p.items[0], { line: 1, description: "PAO LTE SEVEN BOYS 450G", code: "7891193010081", qty: 1, unit: "UN", unit_price: 6.79, total: 6.79 })
  // "Valor total"/"Descontos" are absent when there's no discount; the parser fills them in.
  assert.equal(p.totals.gross, 44.43)
  assert.equal(p.totals.discount, 0)
  assert.equal(p.totals.payable, 44.43)
  assert.equal(p.issued_at, "2026-01-15T10:00:00-03:00")
  assert.ok(p.taxes_estimate.federal > 0)
  assert.deepEqual(p.warnings, [])
})

test("warns when items don't add up to the total", () => {
  const html = fixture("sc-detalhes-server-html.html").replace('<span class="valor">5,62</span>', '<span class="valor">5,63</span>')
  assert.match(parse(html).warnings.join(), /items sum to 31.15/)
})

test("rejects pages without items (e.g. the challenge page)", () => {
  assert.throws(() => parse("<html><div class='cf-turnstile'></div></html>"), /no items/)
})

test("Brazilian numbers", () => {
  assert.equal(num("1.234,56"), 1234.56)
  assert.equal(num(" 0,64"), 0.64)
  assert.equal(num("20"), 20)
  assert.equal(num(""), null)
})
