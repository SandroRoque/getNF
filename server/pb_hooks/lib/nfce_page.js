// Parser for the national NFC-e consultation page (`NFCe_Detalhes.aspx`, docs/sefaz-sc.md).
// Pure function, no dependencies: runs in PocketBase's JS hooks (goja) and in Node for tests.
// Accepts both the server's HTML and the WebView's DOM serialisation (jQuery Mobile adds markup).
//
// Money values are reais as numbers with 2 decimals; quantities as numbers. Brazilian "1.234,56" is converted.

const VERSION = 1

// UTC offsets by UF code (first 2 digits of the chave). No DST in Brazil since 2019.
const UF_OFFSET = { 11: "-04:00", 12: "-05:00", 13: "-04:00", 14: "-04:00", 50: "-04:00", 51: "-04:00" }

const ENTITIES = { nbsp: " ", amp: "&", lt: "<", gt: ">", quot: '"', apos: "'" }

function decode(s) {
  return s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (all, e) => {
    if (e[0] === "#") return String.fromCharCode(e[1] === "x" || e[1] === "X" ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10))
    const v = ENTITIES[e.toLowerCase()]
    return v === undefined ? all : v
  })
}

/** Visible text of an HTML fragment, whitespace collapsed. */
function text(html) {
  return decode(html.replace(/<[^>]*>/g, " ")).replace(/[\s\u00a0]+/g, " ").trim()
}

/** "1.234,56" → 1234.56; null if there's no number. */
function num(s) {
  const m = /-?[\d.]*\d(?:,\d+)?/.exec(s || "")
  if (!m) return null
  return parseFloat(m[0].replace(/\./g, "").replace(",", "."))
}

/** Text after the last ':' ("Qtde.: 0,64" → "0,64"). */
function afterLabel(s) {
  const i = s.lastIndexOf(":")
  return (i >= 0 ? s.slice(i + 1) : s).trim()
}

function spanByClass(html, cls) {
  const m = new RegExp('<span[^>]*class="' + cls + '(?:\\s[^"]*)?"[^>]*>([\\s\\S]*?)</span>').exec(html)
  return m ? text(m[1]) : null
}

function parseItems(html) {
  const items = []
  const row = /<tr[^>]*id="Item \+ (\d+)"[^>]*>([\s\S]*?)<\/tr>/g
  let m
  while ((m = row.exec(html))) {
    const r = m[2]
    const code = /(\d+)/.exec(spanByClass(r, "RCod") || "")
    items.push({
      line: parseInt(m[1], 10),
      description: spanByClass(r, "txtTit"),
      code: code ? code[1] : null,
      qty: num(afterLabel(spanByClass(r, "Rqtd") || "")),
      unit: afterLabel(spanByClass(r, "RUN") || "") || null,
      unit_price: num(afterLabel(spanByClass(r, "RvlUnit") || "")),
      total: num(spanByClass(r, "valor")),
    })
  }
  return items
}

const TOTAL_LABELS = [
  [/qtd\. total de itens/i, "items_count"],
  [/valor total/i, "gross"],
  [/desconto/i, "discount"],
  [/acr[ée]scimo|outras despesas/i, "surcharge"],
  [/valor a pagar/i, "payable"],
  [/troco/i, "change"],
]

function parseTotals(html) {
  const start = html.indexOf('id="totalNota"')
  const end = html.indexOf('id="infos"', start)
  const block = start < 0 ? "" : html.slice(start, end < 0 ? undefined : end)
  const totals = { raw: {} }
  const payments = []
  const pair = /<label([^>]*)>([\s\S]*?)<\/label>\s*<span[^>]*>([\s\S]*?)<\/span>/g
  let m
  while ((m = pair.exec(block))) {
    const label = text(m[2])
    const value = text(m[3])
    if (/class="tx"/.test(m[1])) {
      payments.push({ method: label, amount: num(value) })
      continue
    }
    if (/forma de pagamento/i.test(label)) continue
    totals.raw[label] = value
    for (const [re, key] of TOTAL_LABELS) {
      if (re.test(label)) {
        totals[key] = num(value)
        break
      }
    }
  }
  return { totals, payments }
}

function parseStore(html) {
  const m = /<div[^>]*class="txtTopo"[^>]*>([\s\S]*?)<\/div>\s*<div[^>]*class="text"[^>]*>([\s\S]*?)<\/div>\s*<div[^>]*class="text"[^>]*>([\s\S]*?)<\/div>/.exec(html)
  if (!m) return null
  return { name: text(m[1]), cnpj: text(m[2]).replace(/\D/g, ""), address: text(m[3]) }
}

function toIso(date, time, offset) {
  const [d, mo, y] = date.split("/")
  return `${y}-${mo}-${d}T${time}${offset}`
}

function parse(html) {
  const items = parseItems(html)
  if (items.length === 0) throw new Error("no items found: not an NFC-e details page?")

  const body = html.replace(/<script[\s\S]*?<\/script>|<style[\s\S]*?<\/style>/gi, " ")
  const all = text(body)
  const chaveM = /class="chave"[^>]*>([\d\s]+)</.exec(html)
  const chave = chaveM ? chaveM[1].replace(/\D/g, "") : null
  const offset = (chave && UF_OFFSET[chave.slice(0, 2)]) || "-03:00"

  const info = /Número:\s*(\d+)\s*Série:\s*(\d+)\s*Emissão:\s*(\d{2}\/\d{2}\/\d{4})\s+(\d{2}:\d{2}:\d{2})/.exec(all)
  const prot = /Protocolo de Autorização:\s*(\d+)\s+(\d{2}\/\d{2}\/\d{4})\s+às\s+(\d{2}:\d{2}:\d{2})/.exec(all)
  const ibpt = /FEDERAL R\$\s*([\d.,]+)\s*ESTADUAL R\$\s*([\d.,]+)\s*MUNICIPAL R\$\s*([\d.,]+)/i.exec(all)
  const { totals, payments } = parseTotals(html)

  // Pages without a discount omit "Valor total" and "Descontos": fill them in so consumers don't have to.
  const cents = (x) => Math.round(x * 100)
  const itemsCents = items.reduce((s, i) => s + cents(i.total || 0), 0)
  if (totals.gross == null) totals.gross = itemsCents / 100
  if (totals.discount == null) totals.discount = 0

  const warnings = []
  if (totals.items_count != null && totals.items_count !== items.length)
    warnings.push(`page says ${totals.items_count} items, parsed ${items.length}`)
  if (cents(totals.gross) !== itemsCents) warnings.push(`items sum to ${itemsCents / 100}, page total is ${totals.gross}`)
  if (totals.payable != null && cents(totals.gross) - cents(totals.discount) + cents(totals.surcharge || 0) !== cents(totals.payable))
    warnings.push(`gross - discount + surcharge != payable (${totals.payable})`)

  return {
    parser: "nfce_page",
    version: VERSION,
    chave,
    store: parseStore(html),
    number: info ? info[1] : null,
    series: info ? info[2] : null,
    issued_at: info ? toIso(info[3], info[4], offset) : null,
    authorization: prot ? { protocol: prot[1], at: toIso(prot[2], prot[3], offset) } : null,
    items,
    totals,
    payments,
    taxes_estimate: ibpt ? { federal: num(ibpt[1]), state: num(ibpt[2]), municipal: num(ibpt[3]) } : null,
    warnings,
  }
}

module.exports = { VERSION, parse, num, text }
