// Pure helpers that turn parsed receipts into products and item lines (docs/decisions.md D14).
// No PocketBase APIs here, so it's testable in Node.

/** True for a GTIN-8/12/13/14 with a valid check digit that isn't a store-internal (restricted circulation) code. */
function isGtin(code) {
  if (!/^(\d{8}|\d{12,14})$/.test(code || "")) return false
  // Restricted circulation: GTIN-13 "2..." (in-store, variable weight) and GTIN-8 "0..."/"2...".
  if ((code.length === 13 && code[0] === "2") || (code.length === 8 && (code[0] === "0" || code[0] === "2"))) return false
  let sum = 0
  for (let i = code.length - 2, w = 3; i >= 0; i--, w = 4 - w) sum += (code.charCodeAt(i) - 48) * w
  return (10 - (sum % 10)) % 10 === code.charCodeAt(code.length - 1) - 48
}

/** Packaged goods are the same product at every store; bulk/in-store codes only mean something per store. */
function productKey(code, storeCnpj) {
  return isGtin(code) ? `gtin:${code}` : `store:${storeCnpj}:${code}`
}

/**
 * Net size from a description: "CAFE 3 CORACOES 250G" → {qty: 0.25, unit: "kg"}, "LEITE 12X1L" → {qty: 12, unit: "l"}.
 * Null when there's no size (bulk items are priced per kg already; odd ones get fixed by hand).
 */
function parseSize(description) {
  const m = /(?:^|[^\d.,])(?:(\d+)\s?X\s?)?(\d+(?:[.,]\d+)?)\s?(KGS?|GRS?|G|MG|ML|LTS?|L)(?![A-Z])/i.exec(description || "")
  if (!m) return null
  const count = m[1] ? parseInt(m[1], 10) : 1
  const n = parseFloat(m[2].replace(",", ".")) * count
  const u = m[3].toUpperCase()
  const [qty, unit] =
    u.startsWith("KG") ? [n, "kg"] : u === "MG" ? [n / 1e6, "kg"] : u.startsWith("G") ? [n / 1000, "kg"] : u === "ML" ? [n / 1000, "l"] : [n, "l"]
  return { qty: Math.round(qty * 1e6) / 1e6, unit }
}

/**
 * Splits `totalCents` across lines proportionally to `weights` (line totals in cents), so the parts add up exactly
 * (largest remainder). Returns integer cents per line.
 */
function allocateCents(totalCents, weights) {
  const sum = weights.reduce((a, b) => a + b, 0)
  if (weights.length === 0) return []
  if (sum <= 0) return weights.map((_, i) => (i === 0 ? totalCents : 0))
  const exact = weights.map((w) => (totalCents * w) / sum)
  const parts = exact.map(Math.floor)
  let left = totalCents - parts.reduce((a, b) => a + b, 0)
  const order = exact.map((x, i) => [x - Math.floor(x), i]).sort((a, b) => b[0] - a[0] || a[1] - b[1])
  for (let k = 0; left > 0; k = (k + 1) % order.length, left--) parts[order[k][1]]++
  return parts
}

/**
 * Discount per line, in cents. Lines with a known discount (read from the photo) keep it; the rest of the receipt's
 * discount is prorated over the other lines. Returns [{cents, source}].
 */
function lineDiscounts(totalDiscountCents, lineCents, knownCents) {
  const known = knownCents || {}
  const open = lineCents.map((c, i) => (known[i] == null ? i : -1)).filter((i) => i >= 0)
  const knownSum = Object.keys(known).reduce((s, k) => s + known[k], 0)
  const prorated = allocateCents(Math.max(0, totalDiscountCents - knownSum), open.map((i) => lineCents[i]))
  const out = lineCents.map((_, i) => (known[i] != null ? { cents: known[i], source: "photo" } : null))
  open.forEach((i, k) => (out[i] = { cents: prorated[k], source: "prorated" }))
  return out
}

/** Reads taxonomy/categories.txt (format in the file header). Throws with a line number on bad input. */
function parseCategories(source) {
  const nodes = []
  let stack = []
  source.split(/\r?\n/).forEach((raw, idx) => {
    const line = raw.split("#")[0].replace(/\s+$/, "")
    if (!line.trim()) return
    const indent = line.length - line.replace(/^ +/, "").length
    const m = /^\s*([a-z0-9_]+)\s{2,}(\S.*)$/.exec(line)
    if (!m || indent % 2 !== 0 || indent / 2 > stack.length) throw new Error(`categories.txt line ${idx + 1}: can't parse "${raw.trim()}"`)
    stack = stack.slice(0, indent / 2).concat(m[1])
    const path = stack.join(".")
    if (nodes.some((n) => n.path === path)) throw new Error(`categories.txt line ${idx + 1}: duplicate ${path}`)
    nodes.push({ path, label: m[2].trim(), parent: stack.length > 1 ? stack.slice(0, -1).join(".") : null, order: nodes.length })
  })
  return nodes
}

/** Reads taxonomy/tags.txt: `slug  Description` per line. */
function parseTags(source) {
  const tags = []
  source.split(/\r?\n/).forEach((raw, idx) => {
    const line = raw.split("#")[0].trim()
    if (!line) return
    const m = /^([a-z0-9_]+)\s{2,}(\S.*)$/.exec(line)
    if (!m) throw new Error(`tags.txt line ${idx + 1}: can't parse "${raw.trim()}"`)
    tags.push({ slug: m[1], description: m[2].trim() })
  })
  return tags
}

module.exports = { isGtin, productKey, parseSize, allocateCents, lineDiscounts, parseCategories, parseTags }
