export const UNCATEGORIZED = "unclassified"
export const money = cents => new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" }).format(cents / 100)
export const quantity = value => new Intl.NumberFormat("pt-BR", { maximumFractionDigits: 3 }).format(value)
export const monthLabel = month => new Intl.DateTimeFormat("pt-BR", { month: "short", year: "numeric", timeZone: "UTC" }).format(new Date(`${month}-01T00:00:00Z`))

export function shiftMonth(month, offset) {
  const [year, number] = month.split("-").map(Number)
  const date = new Date(Date.UTC(year, number - 1 + offset, 1))
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}`
}

export function currentMonth(date = new Date()) {
  return new Intl.DateTimeFormat("sv-SE", { year: "numeric", month: "2-digit", timeZone: "America/Sao_Paulo" }).format(date)
}

export function latestYear(month = currentMonth()) {
  return { start: shiftMonth(month, -11), end: month }
}

export function monthsBetween(start, end) {
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(start) || !/^\d{4}-(0[1-9]|1[0-2])$/.test(end) || start > end) return []
  const months = []
  for (let month = start; month <= end; month = shiftMonth(month, 1)) months.push(month)
  return months
}

export function categoryKey(path, depth) {
  if (!path || path === UNCATEGORIZED) return UNCATEGORIZED
  return depth === "leaf" ? path : path.split(".").slice(0, Number(depth)).join(".")
}

export function categoryLabel(path, categories) {
  const translations = {
    unclassified: "Sem categoria", food: "Alimentos e mercado", "food.produce": "Frutas e verduras", "food.produce.fruit": "Frutas", "food.produce.vegetables": "Legumes e verduras", "food.produce.greens_herbs": "Folhas e ervas",
    "food.meat_fish": "Carnes e peixes", "food.meat_fish.beef": "Carne bovina", "food.meat_fish.pork": "Carne suína", "food.meat_fish.poultry": "Aves", "food.meat_fish.fish_seafood": "Peixes e frutos do mar", "food.meat_fish.sausages": "Linguiças", "food.meat_fish.cold_cuts": "Frios",
    "food.dairy_eggs": "Laticínios e ovos", "food.dairy_eggs.milk": "Leite", "food.dairy_eggs.cheese": "Queijos", "food.dairy_eggs.yogurt": "Iogurtes e fermentados", "food.dairy_eggs.butter_cream": "Manteiga e creme de leite", "food.dairy_eggs.eggs": "Ovos",
    "food.bakery": "Padaria", "food.bakery.bread": "Pães", "food.bakery.cakes_pastries": "Bolos e doces", "food.grains": "Grãos e leguminosas", "food.grains.rice": "Arroz", "food.grains.beans_legumes": "Feijão e leguminosas", "food.grains.pasta": "Massas", "food.grains.flour_cereals": "Farinhas, aveia e cereais",
    "food.pantry": "Mercearia", "food.pantry.oils_fats": "Óleos e gorduras", "food.pantry.sugar_sweeteners": "Açúcar e adoçantes", "food.pantry.spreads": "Pastas, geleias e mel", "food.pantry.sauces_condiments": "Molhos e condimentos", "food.pantry.spices_seasonings": "Temperos", "food.pantry.canned_preserved": "Enlatados e conservas", "food.pantry.baking": "Ingredientes para confeitaria",
    "food.ready_meals": "Refeições prontas e instantâneas", "food.ready_meals.instant_noodles": "Macarrão instantâneo", "food.ready_meals.frozen_meals": "Refeições congeladas", "food.ready_meals.other": "Outras refeições prontas", "food.snacks_sweets": "Lanches e doces", "food.snacks_sweets.chocolate_candy": "Chocolate e balas", "food.snacks_sweets.biscuits": "Biscoitos e bolachas", "food.snacks_sweets.salty_snacks": "Salgadinhos", "food.snacks_sweets.ice_cream": "Sorvetes e sobremesas",
    beverages: "Bebidas", "beverages.coffee": "Café", "beverages.tea_mate": "Chá e erva-mate", "beverages.juice": "Sucos", "beverages.soft_drinks": "Refrigerantes", "beverages.water": "Água", "beverages.alcoholic": "Bebidas alcoólicas", "beverages.alcoholic.beer": "Cerveja", "beverages.alcoholic.wine": "Vinho", "beverages.alcoholic.spirits": "Destilados",
    household: "Produtos para casa", "household.cleaning": "Limpeza", "household.laundry": "Lavanderia", "household.kitchen_disposables": "Descartáveis para cozinha", "household.paper_goods": "Papel higiênico e lenços", "household.home_maintenance": "Pequenos reparos domésticos",
    personal_care: "Cuidados pessoais", "personal_care.hygiene": "Higiene", "personal_care.cosmetics": "Cosméticos e cabelo", "personal_care.pharmacy_otc": "Medicamentos sem receita", pets: "Animais de estimação", baby: "Bebês", eating_out: "Alimentação fora de casa", housing: "Moradia", transport: "Transporte", health: "Saúde", education: "Educação", leisure: "Lazer e cultura", clothing: "Roupas e calçados", services: "Serviços e assinaturas", taxes_fees: "Impostos e taxas", other: "Outros",
  }
  if (translations[path]) return translations[path]
  return categories.find(category => category.path === path)?.label || path.split(".").at(-1).replaceAll("_", " ")
}

// Explicit colors for the main groups avoid hash collisions and give familiar categories a visual identity.
const categoryPalette = {
  unclassified: "#8a909b",
  food: "#228833", beverages: "#4477aa", household: "#aa66bb", personal_care: "#cc6677",
  "food.produce": "#65b34e", "food.meat_fish": "#e06055", "food.dairy_eggs": "#5599de",
  "food.bakery": "#c89565", "food.grains": "#ada542", "food.pantry": "#319c7d",
  "food.ready_meals": "#7470ce", "food.snacks_sweets": "#edcf62",
  "beverages.alcoholic": "#b181d7", "beverages.coffee": "#89654a", "beverages.juice": "#f4a440",
  "beverages.tea_mate": "#95ba79", "beverages.soft_drinks": "#e9805c", "beverages.water": "#80bce9",
  "household.cleaning": "#3ab8d4", "household.laundry": "#c3b1e5",
  "household.kitchen_disposables": "#78c6b6", "household.paper_goods": "#8d9fac",
  "household.home_maintenance": "#aa805c", "personal_care.hygiene": "#e38db0",
  "personal_care.cosmetics": "#b458a8", "personal_care.pharmacy_otc": "#9dbbce",
  clothing: "#969b54", leisure: "#ef927e", pets: "#8293c5", baby: "#ceafb4",
  eating_out: "#b96a44", housing: "#547c83", transport: "#a791ce", health: "#d48194",
  education: "#79a5a0", services: "#a79c83", taxes_fees: "#967777", other: "#aaa79b",
}
const assignedColors = new Map(Object.entries(categoryPalette))

// Choose additional category colors by perceptual separation, rather than hashing into similar hues.
function lab(hex) {
  const rgb = hex.match(/[a-f\d]{2}/gi).map(value => parseInt(value, 16) / 255).map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4)
  const [r, g, b] = rgb
  const l = Math.cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b)
  const m = Math.cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b)
  const s = Math.cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b)
  return [.2104542553 * l + .793617785 * m - .0040720468 * s, 1.9779984951 * l - 2.428592205 * m + .4505937099 * s, .0259040371 * l + .7827717662 * m - .808675766 * s]
}
const candidates = []
for (let hue = 0; hue < 360; hue += 15) {
  for (const lightness of [.45, .62, .75]) {
    const saturation = .62, chroma = (1 - Math.abs(2 * lightness - 1)) * saturation
    const x = chroma * (1 - Math.abs(hue / 60 % 2 - 1)), m = lightness - chroma / 2
    const parts = hue < 60 ? [chroma, x, 0] : hue < 120 ? [x, chroma, 0] : hue < 180 ? [0, chroma, x] : hue < 240 ? [0, x, chroma] : hue < 300 ? [x, 0, chroma] : [chroma, 0, x]
    const hex = "#" + parts.map(value => Math.round((value + m) * 255).toString(16).padStart(2, "0")).join("")
    candidates.push({ hex, lab: lab(hex) })
  }
}
function nextColor(used) {
  const reference = used.map(lab)
  let best = candidates[0], maximum = -1
  for (const candidate of candidates) {
    const distance = Math.min(...reference.map(color => color.reduce((sum, channel, index) => sum + (channel - candidate.lab[index]) ** 2, 0)))
    if (distance > maximum) { best = candidate; maximum = distance }
  }
  return best.hex
}

export function registerCategoryColors(paths) {
  const keys = [...new Set(paths.map(path => path || UNCATEGORIZED))].sort()
  const used = keys.filter(key => assignedColors.has(key)).map(key => assignedColors.get(key))
  if (!used.length) used.push(categoryPalette.unclassified)
  for (const key of keys) {
    if (assignedColors.has(key)) continue
    const color = nextColor(used)
    assignedColors.set(key, color); used.push(color)
  }
}

export function categoryColor(path) {
  path ||= UNCATEGORIZED
  if (!assignedColors.has(path)) registerCategoryColors([path])
  return assignedColors.get(path)
}

export function prepareLines(rows) {
  const lines = []
  let undated = 0
  for (const row of rows) {
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(row.month || "")) { undated++; continue }
    const net = Number(row.net)
    if (!Number.isFinite(net)) throw new Error("Um item de despesa tem um valor inválido. Verifique os dados da nota.")
    lines.push({ ...row, cents: Math.round(net * 100) })
  }
  return { lines, undated }
}

export function monthlyTotals(lines, start, end, depth) {
  const months = monthsBetween(start, end).map(month => ({ month, cents: 0, count: 0, categories: new Map() }))
  const byMonth = new Map(months.map(month => [month.month, month]))
  for (const line of lines) {
    const month = byMonth.get(line.month)
    if (!month) continue
    const category = categoryKey(line.category, depth)
    month.cents += line.cents
    month.count++
    month.categories.set(category, (month.categories.get(category) || 0) + line.cents)
  }
  return months
}

export function monthCategories(lines, month, depth) {
  const totals = new Map()
  for (const line of lines) {
    if (line.month !== month) continue
    const category = categoryKey(line.category, depth)
    totals.set(category, (totals.get(category) || 0) + line.cents)
  }
  return [...totals].map(([key, cents]) => ({ key, cents })).sort((a, b) => b.cents - a.cents || a.key.localeCompare(b.key))
}

export function selectedMonth(months, previous = "") {
  if (months.some(month => month.month === previous)) return previous
  return months.filter(month => month.count || month.receiptCount).at(-1)?.month || months.at(-1)?.month || ""
}

export function groupedProducts(lines, month, category, depth) {
  const products = new Map()
  for (const line of lines) {
    if (line.month !== month || categoryKey(line.category, depth) !== category) continue
    const key = line.product || `line:${line.id}`
    if (!products.has(key)) products.set(key, { key, description: line.description || "Produto sem nome", cents: 0, units: new Map(), purchases: [] })
    const product = products.get(key)
    product.cents += line.cents
    const unit = line.unit || "units"
    product.units.set(unit, (product.units.get(unit) || 0) + Number(line.qty || 0))
    product.purchases.push(line)
  }
  for (const product of products.values()) product.purchases.sort((a, b) => (b.day || "").localeCompare(a.day || "") || a.id.localeCompare(b.id))
  return [...products.values()].sort((a, b) => b.cents - a.cents || a.description.localeCompare(b.description))
}

export function prepareReceipts(rows) {
  const receipts = []
  let undated = 0
  for (const row of rows) {
    const timestamp = row.issued_at ? Date.parse(row.issued_at.replace(" ", "T")) : NaN
    if (!Number.isFinite(timestamp)) { undated++; continue }
    // Match spending_lines: SC store-local issue date, UTC-3 (D15).
    const day = new Date(timestamp - 3 * 60 * 60 * 1000).toISOString().slice(0, 10)
    const amount = Number(row.total_paid)
    if (!Number.isFinite(amount)) throw new Error("Uma nota tem um valor total inválido. Verifique os dados da nota.")
    receipts.push({ ...row, day, month: day.slice(0, 7), cents: Math.round(amount * 100) })
  }
  return { receipts, undated }
}

export function receiptsForMonth(receipts, lines, month) {
  const items = new Map()
  for (const line of lines) {
    if (!items.has(line.receipt)) items.set(line.receipt, [])
    items.get(line.receipt).push(line)
  }
  return receipts.filter(receipt => receipt.month === month).map(receipt => ({
    ...receipt,
    items: (items.get(receipt.id) || []).slice().sort((a, b) => (a.description || "").localeCompare(b.description || "") || a.id.localeCompare(b.id)),
  })).sort((a, b) => b.issued_at.localeCompare(a.issued_at) || a.id.localeCompare(b.id))
}
