import { test } from "node:test"
import assert from "node:assert/strict"
import { prepareLines, prepareReceipts, receiptsForMonth, monthlyTotals, monthCategories, groupedProducts, categoryKey, selectedMonth, latestYear, currentMonth, monthsBetween, categoryColor } from "../pb_public/dashboard/model.mjs"
import { createApi, AuthError } from "../pb_public/dashboard/api.mjs"

const rows = [
  { id: "a", product: "coffee", description: "Coffee", month: "2026-01", day: "2026-01-01", category: "beverages.coffee", qty: 2, unit: "UN", net: 10.11 },
  { id: "b", product: "coffee", description: "Coffee", month: "2026-01", day: "2026-01-02", category: "beverages.coffee", qty: 1, unit: "UN", net: 5.05 },
  { id: "c", product: "fruit", description: "Fruit", month: "2026-01", day: "2026-01-03", category: "food.produce.fruit", qty: .355, unit: "KG", net: 3.33 },
  { id: "d", product: "fruit", description: "Fruit", month: "2026-01", day: "2026-01-04", category: "food.produce.fruit", qty: 1, unit: "UN", net: 2.22 },
  { id: "e", product: "other", description: "New product", month: "2026-03", day: "2026-03-01", category: "", qty: 1, unit: "UN", net: .1 },
  { id: "f", product: "coffee", description: "Coffee", month: "2025-12", day: "2025-12-31", category: "beverages.coffee", qty: 1, unit: "UN", net: 9.99 },
  { id: "g", product: "service", description: "Service", month: "2026-01", day: "2026-01-03", category: "services", qty: 1, unit: "UN", net: .2 },
]
const { lines } = prepareLines(rows)

test("bar, pie, product and purchase totals reconcile at every category depth", () => {
  for (const depth of ["1", "2", "leaf"]) {
    const months = monthlyTotals(lines, "2026-01", "2026-03", depth)
    assert.deepEqual(months.map(month => [month.month, month.cents, month.count]), [["2026-01", 2091, 5], ["2026-02", 0, 0], ["2026-03", 10, 1]])
    for (const month of months) {
      const categories = monthCategories(lines, month.month, depth)
      assert.equal(categories.reduce((sum, category) => sum + category.cents, 0), month.cents)
      for (const category of categories) {
        const products = groupedProducts(lines, month.month, category.key, depth)
        assert.equal(products.reduce((sum, product) => sum + product.cents, 0), category.cents)
        for (const product of products) assert.equal(product.purchases.reduce((sum, purchase) => sum + purchase.cents, 0), product.cents)
      }
    }
  }
})

test("category depth rolls up descendants without losing shallow or uncategorized assignments", () => {
  assert.equal(categoryKey("food.produce.fruit", "2"), "food.produce")
  assert.equal(categoryKey("food.produce.fruit", "1"), "food")
  assert.equal(categoryKey("food.produce.fruit", "leaf"), "food.produce.fruit")
  assert.equal(categoryKey("services", "2"), "services")
  assert.equal(categoryKey("", "leaf"), "unclassified")
  assert.equal(monthCategories(lines, "2026-03", "2")[0].key, "unclassified")
  assert.equal(categoryColor("food.produce"), categoryColor("food.produce"))
})

test("products consolidate repeated purchases, retain units, and sort purchases by date", () => {
  const coffee = groupedProducts(lines, "2026-01", "beverages", "1")[0]
  assert.equal(coffee.cents, 1516)
  assert.equal(coffee.units.get("UN"), 3)
  assert.deepEqual(coffee.purchases.map(line => line.id), ["b", "a"])
  const fruit = groupedProducts(lines, "2026-01", "food.produce", "2")[0]
  assert.deepEqual([...fruit.units], [["KG", .355], ["UN", 1]])
  const distinct = prepareLines([{ ...rows[0], product: "one" }, { ...rows[0], id: "another", product: "two" }]).lines
  assert.equal(groupedProducts(distinct, "2026-01", "beverages", "1").length, 2)
})

test("month controls include gaps, handle year boundaries, and keep or reset selections", () => {
  assert.deepEqual(latestYear("2026-10"), { start: "2025-11", end: "2026-10" })
  assert.equal(currentMonth(new Date("2026-11-01T01:00:00Z")), "2026-10")
  assert.deepEqual(monthsBetween("2025-12", "2026-02"), ["2025-12", "2026-01", "2026-02"])
  assert.deepEqual(monthsBetween("2026-03", "2026-01"), [])
  assert.deepEqual(monthsBetween("2026-13", "2026-14"), [])
  const months = monthlyTotals(lines, "2026-01", "2026-03", "2")
  assert.equal(selectedMonth(months), "2026-03")
  assert.equal(selectedMonth(months, "2026-02"), "2026-02")
  assert.equal(selectedMonth(months, "2025-12"), "2026-03")
  assert.equal(selectedMonth(monthlyTotals([], "2026-01", "2026-03", "2")), "2026-03")
})

test("undated lines are disclosed and excluded; zero and signed amounts stay exact", () => {
  const result = prepareLines([...rows, { id: "undated", month: "", net: 500 }])
  assert.equal(result.undated, 1)
  assert.equal(result.lines.length, rows.length)
  const signed = prepareLines([{ ...rows[0], net: .1 }, { ...rows[1], net: .2 }, { ...rows[2], net: -.05 }, { ...rows[3], net: 0 }]).lines
  assert.equal(monthlyTotals(signed, "2026-01", "2026-01", "1")[0].cents, 25)
  assert.throws(() => prepareLines([{ ...rows[0], net: "invalid" }]), /valor inválido/)
})

test("loader follows every page for both collections and forwards authentication", async () => {
  const calls = []
  const api = createApi({ fetcher: async (url, options) => {
    calls.push({ url, options })
    const page = Number(new URL(url, "http://localhost").searchParams.get("page"))
    const collection = url.includes("spending_lines") ? "lines" : url.includes("/receipts/") ? "receipts" : "categories"
    return { ok: true, status: 200, json: async () => ({ totalPages: 2, items: [{ collection, page }] }) }
  } })
  const controller = new AbortController()
  const result = await api.load("session-token", controller.signal)
  assert.deepEqual(result.lines.map(row => row.page), [1, 2])
  assert.deepEqual(result.categories.map(row => row.page), [1, 2])
  assert.deepEqual(result.receipts.map(row => row.page), [1, 2])
  assert.equal(calls.length, 6)
  for (const call of calls) {
    assert.equal(call.options.headers.Authorization, "session-token")
    assert.equal(call.options.signal, controller.signal)
    assert.equal(call.options.cache, "no-store")
    assert.ok(!call.url.includes("session-token"))
  }
})

test("partial results are never returned when later pages fail", async () => {
  const api = createApi({ fetcher: async url => {
    const page = Number(new URL(url, "http://localhost").searchParams.get("page"))
    return page === 2 ? { ok: false, status: 500 } : { ok: true, status: 200, json: async () => ({ totalPages: 2, items: [rows[0]] }) }
  } })
  await assert.rejects(api.load("token"), /HTTP 500/)
})

test("authentication expiry, login errors, and network errors stay distinct", async () => {
  for (const code of [401, 403]) {
    const api = createApi({ fetcher: async () => ({ ok: false, status: code }) })
    await assert.rejects(api.load("expired"), AuthError)
    await assert.rejects(api.refreshAuth("expired"), AuthError)
  }
  const invalid = createApi({ fetcher: async () => ({ ok: false, status: 400 }) })
  await assert.rejects(invalid.login("user@example.test", "incorrect"), /E-mail ou senha incorretos/)
  const unavailable = createApi({ fetcher: async () => { throw new TypeError("Network unavailable") } })
  await assert.rejects(unavailable.load("token"), /Network unavailable/)
})

test("phone login stays first; valid admin credentials fall back and refresh as admin", async () => {
  const calls = []
  const api = createApi({ fetcher: async (url, options) => {
    calls.push({ url, options })
    if (url === "/api/collections/users/auth-with-password") return { ok: false, status: 400 }
    return { ok: true, status: 200, json: async () => ({ token: "admin-token" }) }
  } })
  const result = await api.login("admin@example.test", "saved-password")
  assert.equal(result.collection, "_superusers")
  assert.equal(result.token, "admin-token")
  assert.deepEqual(calls.map(call => call.url), ["/api/collections/users/auth-with-password", "/api/collections/_superusers/auth-with-password"])
  await api.refreshAuth(result.token, undefined, result.collection)
  assert.equal(calls.at(-1).url, "/api/collections/_superusers/auth-refresh")
  assert.equal(calls.at(-1).options.headers.Authorization, result.token)
  const phoneCalls = []
  const phone = createApi({ fetcher: async url => {
    phoneCalls.push(url)
    return { ok: true, status: 200, json: async () => ({ token: "phone-token" }) }
  } })
  assert.equal((await phone.login("phone@example.test", "phone-password")).collection, "users")
  assert.equal(phoneCalls.length, 1)
  await phone.refreshAuth("phone-token")
  assert.equal(phoneCalls.at(-1), "/api/collections/users/auth-refresh")
})

test("login fallback never masks network, server, rate-limit, or authorization errors", async () => {
  for (const code of [401, 403, 429, 500]) {
    let calls = 0
    const api = createApi({ fetcher: async () => { calls++; return { ok: false, status: code } } })
    await assert.rejects(api.login("user@example.test", "password"))
    assert.equal(calls, 1)
  }
  let calls = 0
  const api = createApi({ fetcher: async () => { calls++; throw new TypeError("Network unavailable") } })
  await assert.rejects(api.login("user@example.test", "password"), /Network unavailable/)
  assert.equal(calls, 1)
  assert.throws(() => api.refreshAuth("token", undefined, "unknown"), AuthError)
})

test("NF month uses store-local issue date, not capture date or UTC month", () => {
  const result = prepareReceipts([
    { id: "late", issued_at: "2026-11-01 01:30:00.000Z", total_paid: 20.91 },
    { id: "november", issued_at: "2026-11-01 03:00:00.000Z", total_paid: 5 },
    { id: "undated", issued_at: "", total_paid: 10 },
  ])
  assert.equal(result.undated, 1)
  assert.equal(result.receipts[0].month, "2026-10")
  assert.equal(result.receipts[0].day, "2026-10-31")
  assert.equal(result.receipts[0].cents, 2091)
  assert.equal(result.receipts[1].month, "2026-11")
})

test("month NFs retain all categories, distinct receipts, totals, and receipts without items", () => {
  const { receipts } = prepareReceipts([
    { id: "one", store_name: "Test store", issued_at: "2026-01-10 16:00:00.000Z", total_paid: 15.16 },
    { id: "two", store_name: "Test store", issued_at: "2026-01-12 16:00:00.000Z", total_paid: 5.75 },
    { id: "unparsed", issued_at: "2026-01-13 16:00:00.000Z", total_paid: 8, parse_error: "Parser issue" },
    { id: "outside", issued_at: "2026-03-01 16:00:00.000Z", total_paid: 1 },
  ])
  const items = lines.filter(line => line.month === "2026-01").map(line => ({ ...line, receipt: ["a", "b"].includes(line.id) ? "one" : "two" }))
  const selected = receiptsForMonth(receipts, items, "2026-01")
  assert.deepEqual(selected.map(receipt => receipt.id), ["unparsed", "two", "one"])
  assert.equal(selected.reduce((sum, receipt) => sum + receipt.cents, 0), 2891)
  assert.equal(selected[0].items.length, 0)
  assert.equal(selected[0].parse_error, "Parser issue")
  assert.equal(selected[2].items.reduce((sum, item) => sum + item.cents, 0), 1516)
  assert.equal(selected[1].items.reduce((sum, item) => sum + item.cents, 0), 575)
  assert.ok(selected[1].items.some(item => item.category === "food.produce.fruit"))
  assert.ok(selected[1].items.some(item => item.category === "services"))
  assert.equal(receiptsForMonth(receipts, items, "2026-02").length, 0)
  assert.equal(selectedMonth([{ month: "2026-01", count: 0, receiptCount: 1 }, { month: "2026-02", count: 0, receiptCount: 0 }]), "2026-01")
})
