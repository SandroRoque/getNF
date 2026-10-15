import { money, monthLabel, categoryColor, categoryLabel } from "./model.mjs?v=pt-br-20261014"

const ns = "http://www.w3.org/2000/svg"
function svgElement(tag, attributes = {}, text = "") {
  const element = document.createElementNS(ns, tag)
  for (const [key, value] of Object.entries(attributes)) element.setAttribute(key, String(value))
  if (text) element.textContent = text
  return element
}

function tooltip(mark, text) {
  const box = document.querySelector("#tooltip")
  const show = event => {
    box.textContent = text
    box.hidden = false
    const bounds = mark.getBoundingClientRect()
    const x = event?.clientX || bounds.left + bounds.width / 2
    const y = event?.clientY || bounds.top + 12
    box.style.left = `${Math.max(8, Math.min(x + 12, window.innerWidth - box.offsetWidth - 8))}px`
    box.style.top = `${Math.max(8, Math.min(y + 12, window.innerHeight - box.offsetHeight - 8))}px`
  }
  mark.addEventListener("pointerenter", show)
  mark.addEventListener("pointermove", show)
  mark.addEventListener("focus", show)
  for (const type of ["pointerleave", "blur", "click"]) mark.addEventListener(type, () => { box.hidden = true })
}

function activate(mark, callback) {
  mark.addEventListener("click", callback)
  mark.addEventListener("keydown", event => {
    if (event.key === "Enter" || event.key === " ") { event.preventDefault(); callback() }
  })
}

function chartTitle(svg, title) {
  svg.append(svgElement("title", {}, title))
  svg.setAttribute("role", "group")
  svg.setAttribute("aria-label", title)
}

export function renderBars(container, months, categories, chosenMonth, onSelect) {
  const width = Math.max(680, months.length * 67 + 80), height = 285
  const left = 65, right = 14, top = 20, bottom = 235
  const chartHeight = bottom - top, band = (width - left - right) / Math.max(months.length, 1)
  const positive = Math.max(100, ...months.map(month => [...month.categories.values()].filter(value => value > 0).reduce((sum, value) => sum + value, 0)))
  const negative = Math.min(0, ...months.map(month => [...month.categories.values()].filter(value => value < 0).reduce((sum, value) => sum + value, 0)))
  const tick = 10 ** Math.floor(Math.log10(positive / 4)) * Math.ceil(positive / 4 / (10 ** Math.floor(Math.log10(positive / 4))))
  const maximum = tick * 4, minimum = negative < 0 ? Math.floor(negative / tick) * tick : 0
  const y = value => bottom - (value - minimum) / (maximum - minimum) * chartHeight
  const keys = [...new Set(months.flatMap(month => [...month.categories.keys()]))].sort()
  const svg = svgElement("svg", { viewBox: `0 0 ${width} ${height}`, style: `min-width:${width}px` })
  chartTitle(svg, "Despesas registradas por mês. Selecione um mês para ver suas categorias.")
  for (let value = minimum; value <= maximum; value += tick) {
    svg.append(svgElement("line", { x1: left, x2: width - right, y1: y(value), y2: y(value), class: "chart-grid" }))
    const axisLabel = new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL", maximumFractionDigits: 0 }).format(value / 100)
    svg.append(svgElement("text", { x: left - 9, y: y(value) + 4, "text-anchor": "end" }, axisLabel))
  }
  months.forEach((month, index) => {
    const x = left + index * band, barWidth = Math.min(35, band * .55)
    const label = monthLabel(month.month)
    const description = `${label}: ${month.count ? `${money(month.cents)} em ${month.count} itens de notas` : month.receiptCount ? "Nenhum item identificado" : "Nenhuma nota registrada"}`
    const group = svgElement("g", { class: "month-hit", role: "button", tabindex: 0, "aria-label": `${description}. Selecione o mês.`, "aria-pressed": month.month === chosenMonth, "data-month": month.month })
    group.append(svgElement("rect", { x: x + 2, y: top, width: Math.max(1, band - 4), height: bottom - top + 36, rx: 5, fill: "var(--accent)", opacity: month.month === chosenMonth ? .07 : 0, class: "month-indicator" }))
    let pos = 0, neg = 0
    for (const key of keys) {
      const value = month.categories.get(key) || 0
      if (!value) continue
      const start = value > 0 ? pos : neg, end = start + value
      const rect = svgElement("rect", { x: x + (band - barWidth) / 2, y: y(Math.max(start, end)), width: barWidth, height: Math.abs(y(end) - y(start)), fill: categoryColor(key), "data-category": key })
      rect.append(svgElement("title", {}, `${categoryLabel(key, categories)}: ${money(value)}`))
      group.append(rect)
      tooltip(rect, `${label}\n${categoryLabel(key, categories)}: ${money(value)}\nTotal do mês: ${money(month.cents)}`)
      if (value > 0) pos = end; else neg = end
    }
    if (!month.count) group.append(svgElement("line", { x1: x + band / 2 - 7, x2: x + band / 2 + 7, y1: y(0) - 2, y2: y(0) - 2, stroke: "var(--line)", "stroke-width": 2 }))
    group.append(svgElement("rect", { x, y: bottom + 3, width: band, height: 33, fill: "transparent", class: "month-focus" }))
    const [name, year] = label.split(" ")
    group.append(svgElement("text", { x: x + band / 2, y: bottom + 17, "text-anchor": "middle", class: month.month === chosenMonth ? "selected-month-label" : "" }, name))
    group.append(svgElement("text", { x: x + band / 2, y: bottom + 31, "text-anchor": "middle", style: "font-size:9px" }, year))
    group.addEventListener("focus", () => {
      group.scrollIntoView({ block: "nearest", inline: "nearest" })
    })
    // Pointer tooltips belong to individual segments; keyboard focus shows the month total.
    group.addEventListener("focus", () => {
      const box = document.querySelector("#tooltip")
      box.textContent = description
      const rect = group.getBoundingClientRect()
      box.hidden = false
      box.style.left = `${Math.max(8, Math.min(rect.left, window.innerWidth - box.offsetWidth - 8))}px`
      box.style.top = `${Math.max(8, rect.top - box.offsetHeight - 8)}px`
    })
    group.addEventListener("blur", () => { document.querySelector("#tooltip").hidden = true })
    group.addEventListener("pointerenter", event => {
      if (!month.count) {
        const box = document.querySelector("#tooltip")
        box.textContent = description; box.hidden = false
        box.style.left = `${Math.max(8, Math.min(event.clientX + 12, window.innerWidth - box.offsetWidth - 8))}px`
        box.style.top = `${Math.min(event.clientY + 12, window.innerHeight - box.offsetHeight - 8)}px`
      }
    })
    group.addEventListener("pointerleave", () => { document.querySelector("#tooltip").hidden = true })
    activate(group, () => onSelect(month.month))
    svg.append(group)
  })
  container.replaceChildren(svg)
  scrollSelectedMonth(container)
  return keys
}

export function scrollSelectedMonth(container) {
  // On phones the timeline scrolls; open on the selected month rather than old empty months.
  const selected = container.querySelector('[data-month][aria-pressed="true"]')
  if (selected) {
    const bounds = selected.getBoundingClientRect(), viewport = container.getBoundingClientRect()
    container.scrollLeft = Math.max(0, container.scrollLeft + bounds.right - viewport.right + 24)
  }
}

export function renderPie(container, totals, categories, chosenCategory, onSelect) {
  const sum = totals.reduce((total, category) => total + category.cents, 0)
  if (sum <= 0 || totals.some(category => category.cents < 0)) {
    const empty = document.createElement("div")
    empty.className = "empty"
    empty.textContent = totals.length ? "Estes valores não podem ser exibidos em um gráfico de pizza. Selecione uma categoria abaixo para ver as compras." : "Nenhuma nota registrada neste mês."
    container.replaceChildren(empty)
    return
  }
  const svg = svgElement("svg", { viewBox: "0 0 400 240", class: chosenCategory ? "has-selection" : "" })
  chartTitle(svg, "Despesas por categoria no mês selecionado. Selecione uma categoria para ver os produtos.")
  const cx = 200, cy = 117, radius = 96
  let angle = -Math.PI / 2
  for (const category of totals) {
    if (category.cents <= 0) continue
    const next = angle + category.cents / sum * 2 * Math.PI
    const start = [cx + radius * Math.cos(angle), cy + radius * Math.sin(angle)]
    const end = [cx + radius * Math.cos(next), cy + radius * Math.sin(next)]
    const attributes = { fill: categoryColor(category.key), class: `pie-slice${chosenCategory === category.key ? " selected" : ""}`, role: "button", tabindex: 0, "aria-pressed": category.key === chosenCategory, "data-category": category.key }
    const slice = category.cents === sum ? svgElement("circle", { ...attributes, cx, cy, r: radius }) : svgElement("path", { ...attributes, d: `M ${cx} ${cy} L ${start.join(" ")} A ${radius} ${radius} 0 ${next - angle > Math.PI ? 1 : 0} 1 ${end.join(" ")} Z` })
    const middle = (angle + next) / 2
    slice.style.setProperty("--slice-x", `${Math.cos(middle) * 6}px`)
    slice.style.setProperty("--slice-y", `${Math.sin(middle) * 6}px`)
    const description = `${categoryLabel(category.key, categories)}: ${money(category.cents)} (${(category.cents / sum * 100).toFixed(1)}%)`
    slice.setAttribute("aria-label", `${description}. Mostrar produtos.`)
    tooltip(slice, description)
    activate(slice, () => onSelect(category.key))
    svg.append(slice)
    angle = next
  }
  container.replaceChildren(svg)
}
