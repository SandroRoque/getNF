import { createApi, AuthError } from "./api.mjs?v=pt-br-20261014"
import { latestYear, money, quantity, monthLabel, monthsBetween, prepareLines, prepareReceipts, receiptsForMonth, monthlyTotals, monthCategories, selectedMonth, groupedProducts, categoryKey, registerCategoryColors, categoryColor, categoryLabel } from "./model.mjs?v=pt-br-20261014"
import { renderBars, renderPie, scrollSelectedMonth } from "./charts.mjs?v=pt-br-20261014"

const $ = selector => document.querySelector(selector)
const api = createApi()
const storageKey = "getnf-dashboard-token"
const collectionKey = "getnf-dashboard-auth-collection"
const state = { token: "", authCollection: "users", lines: [], categories: [], receipts: [], month: "", category: "", loaded: false, loading: false, undated: 0, undatedReceipts: 0, updated: null, controller: null }
const initialRange = latestYear()
$("#start").value = initialRange.start
$("#end").value = initialRange.end

function element(tag, className = "", text = "") {
  const node = document.createElement(tag)
  if (className) node.className = className
  if (text) node.textContent = text
  return node
}
function saveToken(token, collection = state.authCollection) {
  state.token = token
  state.authCollection = token ? collection : "users"
  try {
    if (token) {
      sessionStorage.setItem(storageKey, token)
      sessionStorage.setItem(collectionKey, collection)
    } else {
      sessionStorage.removeItem(storageKey)
      sessionStorage.removeItem(collectionKey)
    }
  } catch { /* Session memory still works when browser storage is unavailable. */ }
}
function showLogin(message = "") {
  state.controller?.abort()
  saveToken("")
  Object.assign(state, { lines: [], categories: [], receipts: [], loaded: false, loading: false, category: "", month: "", updated: null })
  $("#dashboard").hidden = true
  $("#account").hidden = true
  $("#login-panel").hidden = false
  $("#data-content").hidden = true
  $("#bars").replaceChildren(); $("#pie").replaceChildren(); $("#product-list").replaceChildren()
  $("#receipt-list").replaceChildren(); $("#receipts-total").textContent = ""
  $("#pie-legend").replaceChildren(); $("#bar-legend").replaceChildren()
  $("#tooltip").hidden = true
  $("#updated").textContent = ""
  $("#login-message").textContent = message
  $("#password").value = ""
  $("#sign-in").disabled = false
  $("#sign-in").textContent = "Entrar"
}

function status(message = "", error = false) {
  $("#status").textContent = message
  $("#status").classList.toggle("error", error)
}

async function loadData({ refreshSession = false } = {}) {
  state.controller?.abort()
  const controller = new AbortController()
  state.controller = controller
  state.loading = true
  $("#refresh").disabled = true
  $("#refresh").textContent = "Carregando…"
  status(state.loaded ? "Atualizando despesas registradas…" : "Carregando despesas registradas…")
  try {
    if (refreshSession) {
      const auth = await api.refreshAuth(state.token, controller.signal, state.authCollection)
      if (controller.signal.aborted) return
      saveToken(auth.token)
    }
    const data = await api.load(state.token, controller.signal)
    if (controller.signal.aborted) return
    const prepared = prepareLines(data.lines)
    const preparedNfs = prepareReceipts(data.receipts)
    Object.assign(state, { lines: prepared.lines, categories: data.categories, receipts: preparedNfs.receipts, undated: prepared.undated, undatedReceipts: preparedNfs.undated, loaded: true, updated: new Date() })
    status()
    render()
    $("#updated").textContent = `Atualizado às ${state.updated.toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" })}`
  } catch (error) {
    if (controller.signal.aborted) return
    if (error instanceof AuthError) { showLogin(error.message); return }
    status(`${error.message || "Não foi possível conectar ao servidor."}${state.loaded ? " Exibindo os dados anteriores; atualize para tentar novamente." : " Atualize para tentar novamente."}`, true)
  } finally {
    if (state.controller === controller) {
      state.loading = false
      $("#refresh").disabled = false
      $("#refresh").textContent = "Atualizar"
    }
  }
}

function showDashboard() {
  $("#login-panel").hidden = true
  $("#dashboard").hidden = false
  $("#account").hidden = false
  $("#login-message").textContent = ""
}

$("#login-form").addEventListener("submit", async event => {
  event.preventDefault()
  state.controller?.abort()
  const controller = new AbortController()
  state.controller = controller
  $("#sign-in").disabled = true
  $("#sign-in").textContent = "Entrando…"
  $("#login-message").textContent = ""
  try {
    const auth = await api.login($("#email").value.trim(), $("#password").value, controller.signal)
    if (controller.signal.aborted) return
    saveToken(auth.token, auth.collection)
    $("#password").value = ""
    showDashboard()
    await loadData()
  } catch (error) {
    if (!controller.signal.aborted) $("#login-message").textContent = error.message || "Não foi possível conectar ao servidor. Tente novamente."
  } finally {
    $("#sign-in").disabled = false
    $("#sign-in").textContent = "Entrar"
  }
})

$("#logout").addEventListener("click", () => { showLogin(); $("#email").focus() })
$("#refresh").addEventListener("click", () => loadData({ refreshSession: true }))

function setRange(start, end) {
  $("#start").value = start; $("#end").value = end
  render()
}
$("#latest").addEventListener("click", () => { const range = latestYear(); setRange(range.start, range.end) })
$("#all-history").addEventListener("click", () => {
  const months = [...state.lines, ...state.receipts].map(row => row.month).sort()
  const range = latestYear()
  setRange(months[0] || range.start, months.at(-1) || range.end)
})
for (const id of ["#start", "#end"]) $(id).addEventListener("change", render)
$("#depth").addEventListener("change", () => { state.category = ""; render() })
$("#month-select").addEventListener("change", event => selectMonth(event.target.value))

function selectMonth(month) {
  const focused = document.activeElement?.getAttribute("data-month")
  state.month = month; state.category = ""
  render()
  if (focused) $("#bars").querySelector(`[data-month="${focused}"]`)?.focus({ preventScroll: true })
}
function selectCategory(category) {
  state.category = category
  $("#tooltip").hidden = true
  $("#pie svg")?.classList.add("has-selection")
  // Keep the chart nodes mounted so pointer and keyboard focus survive selection.
  for (const node of $("#pie").querySelectorAll("[data-category]")) {
    const selected = node.dataset.category === category
    node.classList.toggle("selected", selected); node.setAttribute("aria-pressed", selected)
  }
  for (const node of $("#pie-legend").querySelectorAll("[data-category]")) node.setAttribute("aria-pressed", node.dataset.category === category)
  renderProducts()
}

function render() {
  if (!state.loaded) return
  $("#tooltip").hidden = true
  const start = $("#start").value, end = $("#end").value, depth = $("#depth").value
  // Register the whole history first, keeping colors independent of selected month/date filters.
  registerCategoryColors(state.lines.map(line => categoryKey(line.category, depth)))
  if (!monthsBetween(start, end).length) {
    $("#data-content").hidden = true
    status("Escolha um período válido, com o mês inicial anterior ou igual ao mês final.", true)
    return
  }
  if (!state.loading && $("#status").textContent.startsWith("Escolha um período válido")) status()
  const receiptCounts = new Map()
  for (const receipt of state.receipts) receiptCounts.set(receipt.month, (receiptCounts.get(receipt.month) || 0) + 1)
  const months = monthlyTotals(state.lines, start, end, depth).map(month => ({ ...month, receiptCount: receiptCounts.get(month.month) || 0 }))
  const chosen = selectedMonth(months, state.month)
  if (chosen !== state.month) state.category = ""
  state.month = chosen
  const totals = monthCategories(state.lines, state.month, depth)
  if (!totals.some(category => category.key === state.category)) state.category = ""
  $("#data-content").hidden = false
  $("#range-summary").textContent = `${monthLabel(start)} – ${monthLabel(end)} · ${months.reduce((sum, month) => sum + month.count, 0)} itens de notas`
  $("#history-total").textContent = money(months.reduce((sum, month) => sum + month.cents, 0))
  const year = latestYear()
  $("#latest").setAttribute("aria-pressed", start === year.start && end === year.end)
  const captured = [...state.lines, ...state.receipts].map(row => row.month).sort()
  $("#all-history").setAttribute("aria-pressed", Boolean(captured.length && start === captured[0] && end === captured.at(-1)))
  $("#undated-note").textContent = [state.undated ? `${state.undated} itens sem data foram excluídos dos gráficos mensais.` : "", state.undatedReceipts ? `${state.undatedReceipts} notas sem data de emissão não podem ser associadas a um mês.` : ""].filter(Boolean).join(" ")
  $("#month-select").replaceChildren(...months.map(month => {
    const option = element("option", "", `${monthLabel(month.month)}${month.count ? "" : month.receiptCount ? " · sem itens identificados" : " · sem notas"}`)
    option.value = month.month; option.selected = month.month === state.month
    return option
  }))
  const keys = renderBars($("#bars"), months, state.categories, state.month, selectMonth)
  $("#bar-legend").replaceChildren(...keys.map(key => {
    const node = element("span", "legend-item")
    const swatch = element("span", "swatch"); swatch.style.setProperty("--swatch", categoryColor(key))
    node.append(swatch, document.createTextNode(categoryLabel(key, state.categories)))
    return node
  }))
  const sum = totals.reduce((total, category) => total + category.cents, 0)
  const hasReceipts = receiptCounts.has(state.month)
  $("#month-detail").textContent = `${monthLabel(state.month)} · ${totals.length ? money(sum) : hasReceipts ? "Nenhum item identificado" : "Nenhuma nota registrada"}`
  renderPie($("#pie"), totals, state.categories, state.category, selectCategory)
  if (!totals.length && hasReceipts) $("#pie .empty").textContent = "Nenhum item identificado nas notas deste mês."
  $("#pie-legend").replaceChildren(...totals.map(category => {
    const button = element("button")
    button.type = "button"; button.dataset.category = category.key
    button.setAttribute("aria-pressed", category.key === state.category)
    button.setAttribute("aria-label", `${categoryLabel(category.key, state.categories)}, ${money(category.cents)}. Mostrar produtos.`)
    const swatch = element("span", "swatch"); swatch.style.setProperty("--swatch", categoryColor(category.key))
    button.append(swatch, element("span", "label", categoryLabel(category.key, state.categories)), element("span", "amount", money(category.cents)), element("span", "percent", sum > 0 ? `${(category.cents / sum * 100).toFixed(1)}%` : "—"))
    button.addEventListener("click", () => selectCategory(category.key))
    return button
  }))
  renderProducts()
  renderReceipts()
}

function dateLabel(day) {
  return new Intl.DateTimeFormat("pt-BR", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" }).format(new Date(`${day}T00:00:00Z`))
}

function renderReceipts() {
  const receipts = receiptsForMonth(state.receipts, state.lines, state.month)
  const total = receipts.reduce((sum, receipt) => sum + receipt.cents, 0)
  $("#receipts-subtitle").textContent = `${monthLabel(state.month)} · ${receipts.length} ${receipts.length === 1 ? "nota" : "notas"} · Todas as categorias`
  $("#receipts-total").textContent = money(total)
  if (!receipts.length) {
    $("#receipt-list").replaceChildren(element("div", "empty", "Nenhuma nota registrada neste mês."))
    return
  }
  const wrapper = element("div", "receipt-table-wrap")
  const table = element("table")
  const head = element("thead"), header = element("tr")
  for (const title of ["Nota / estabelecimento", "Data de emissão", "Itens", "Total pago"]) { const th = element("th", "", title); th.scope = "col"; header.append(th) }
  head.append(header); table.append(head)
  const body = element("tbody")
  for (const [index, receipt] of receipts.entries()) {
    const row = element("tr", "receipt-row")
    const name = element("td", "receipt-name"), button = element("button")
    button.type = "button"; button.setAttribute("aria-expanded", "false"); button.setAttribute("aria-controls", `receipt-items-${index}`)
    const arrow = element("span", "expand-indicator", "+"); arrow.setAttribute("aria-hidden", "true")
    button.append(arrow, document.createTextNode(receipt.store_name || "Estabelecimento indisponível")); name.append(button)
    if (receipt.parse_error) name.append(element("span", "receipt-warning", "Houve um problema na leitura; os detalhes dos itens podem estar incompletos."))
    row.append(name, element("td", "receipt-date", dateLabel(receipt.day)), element("td", "purchase-count", String(receipt.items.length)), element("td", "amount", money(receipt.cents)))
    const expanded = element("tr", "receipt-details")
    expanded.id = `receipt-items-${index}`; expanded.hidden = true
    const cell = element("td"); cell.colSpan = 4
    const content = element("div", "receipt-items")
    if (!receipt.items.length) content.append(element("p", "subtitle", "Nenhum item identificado nesta nota."))
    else {
      const itemTable = element("table"), itemHead = element("thead"), itemHeader = element("tr")
      for (const title of ["Produto", "Categoria", "Quantidade", "Despesa líquida"]) { const th = element("th", "", title); th.scope = "col"; itemHeader.append(th) }
      itemHead.append(itemHeader); itemTable.append(itemHead)
      const itemBody = element("tbody")
      for (const item of receipt.items) {
        const itemRow = element("tr")
        itemRow.append(element("td", "", item.description || "Produto sem nome"), element("td", "item-category", categoryLabel(item.category || "unclassified", state.categories)), element("td", "quantity", `${quantity(Number(item.qty || 0))} ${item.unit || "un."}`), element("td", "amount", money(item.cents)))
        itemBody.append(itemRow)
      }
      itemTable.append(itemBody); content.append(itemTable)
      const itemTotal = receipt.items.reduce((sum, item) => sum + item.cents, 0)
      if (itemTotal !== receipt.cents) content.append(element("p", "receipt-warning", `A soma dos itens identificados é ${money(itemTotal)}; o total pago na nota é ${money(receipt.cents)}.`))
    }
    cell.append(content); expanded.append(cell)
    button.addEventListener("click", () => { expanded.hidden = !expanded.hidden; button.setAttribute("aria-expanded", String(!expanded.hidden)); arrow.textContent = expanded.hidden ? "+" : "−" })
    body.append(row, expanded)
  }
  table.append(body)
  const foot = element("tfoot"), footRow = element("tr"), label = element("td", "", "Total das notas no mês")
  label.colSpan = 3; footRow.append(label, element("td", "amount", money(total))); foot.append(footRow); table.append(foot)
  wrapper.append(table); $("#receipt-list").replaceChildren(wrapper)
}

function renderProducts() {
  const list = $("#product-list")
  $("#category-total").textContent = ""
  if (!state.category) {
    $("#products-subtitle").textContent = "Selecione uma categoria no detalhamento do mês."
    const empty = element("div", "empty")
    empty.append(element("span", "empty-symbol", "↙"), element("span", "", "Escolha uma fatia ou categoria para ver o que você comprou."))
    list.replaceChildren(empty)
    return
  }
  const products = groupedProducts(state.lines, state.month, state.category, $("#depth").value)
  const sum = products.reduce((total, product) => total + product.cents, 0)
  $("#category-total").textContent = money(sum)
  $("#products-subtitle").textContent = `${categoryLabel(state.category, state.categories)} · ${monthLabel(state.month)} · ${products.length} ${products.length === 1 ? "produto" : "produtos"}`
  const wrapper = element("div", "product-table-wrap")
  const table = element("table")
  const caption = element("caption", "", `Produtos em ${categoryLabel(state.category, state.categories)} durante ${monthLabel(state.month)}`)
  caption.style.cssText = "position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%)"
  table.append(caption)
  const thead = element("thead"), header = element("tr")
  for (const title of ["Produto", "Quantidade", "Compras", "Despesa líquida"]) { const th = element("th", "", title); th.scope = "col"; header.append(th) }
  thead.append(header); table.append(thead)
  const tbody = element("tbody")
  for (const [index, product] of products.entries()) {
    const row = element("tr")
    const name = element("td", "product-name")
    const button = element("button")
    button.type = "button"; button.setAttribute("aria-expanded", "false")
    button.setAttribute("aria-controls", `purchases-${index}`)
    const arrow = element("span", "expand-indicator", "+"); arrow.setAttribute("aria-hidden", "true")
    button.append(arrow, document.createTextNode(product.description)); name.append(button)
    const qty = element("td", "quantity")
    for (const [unit, value] of product.units) qty.append(element("span", "", `${quantity(value)} ${unit}`))
    row.append(name, qty, element("td", "purchase-count", String(product.purchases.length)), element("td", "amount", money(product.cents)))
    const details = element("tr", "purchase-details")
    details.id = `purchases-${index}`; details.hidden = true
    const cell = element("td"); cell.colSpan = 4
    const purchases = element("div", "purchase-list")
    for (const line of product.purchases) {
      const purchase = element("div", "purchase")
      const date = line.day ? new Intl.DateTimeFormat("pt-BR", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" }).format(new Date(`${line.day}T00:00:00Z`)) : "Data indisponível"
      purchase.append(element("span", "", date), element("span", "amount", money(line.cents)), element("span", "store", line.store_name || "Estabelecimento indisponível"), element("span", "quantity", `${quantity(Number(line.qty || 0))} ${line.unit || "un."}`))
      purchases.append(purchase)
    }
    cell.append(purchases); details.append(cell)
    button.addEventListener("click", () => {
      details.hidden = !details.hidden; button.setAttribute("aria-expanded", String(!details.hidden)); arrow.textContent = details.hidden ? "+" : "−"
    })
    tbody.append(row, details)
  }
  table.append(tbody)
  const footer = element("tfoot"), footerRow = element("tr"), totalLabel = element("td", "", "Total da categoria")
  totalLabel.colSpan = 3; footerRow.append(totalLabel, element("td", "amount", money(sum))); footer.append(footerRow); table.append(footer)
  wrapper.append(table); list.replaceChildren(wrapper)
}

async function restoreSession() {
  let saved = ""
  let collection = "users"
  try {
    saved = sessionStorage.getItem(storageKey) || ""
    collection = sessionStorage.getItem(collectionKey) || "users"
  } catch { return }
  if (!saved) return
  $("#sign-in").disabled = true
  $("#login-message").textContent = "Restaurando sua sessão…"
  const controller = new AbortController(); state.controller = controller
  try {
    const auth = await api.refreshAuth(saved, controller.signal, collection)
    if (controller.signal.aborted) return
    saveToken(auth.token, collection); showDashboard(); await loadData()
  } catch (error) {
    if (!controller.signal.aborted) showLogin(error instanceof AuthError ? error.message : "Não foi possível restaurar sua sessão. Entre novamente para tentar.")
  } finally { $("#sign-in").disabled = false }
}
$("#sign-in").disabled = false
document.documentElement.dataset.ready = "true"
new ResizeObserver(() => scrollSelectedMonth($("#bars"))).observe($("#bars"))
restoreSession()
