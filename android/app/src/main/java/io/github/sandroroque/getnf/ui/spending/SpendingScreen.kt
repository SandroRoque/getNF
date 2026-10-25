package io.github.sandroroque.getnf.ui.spending

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.Caveats
import io.github.sandroroque.getnf.data.Ipci
import io.github.sandroroque.getnf.data.Spending
import io.github.sandroroque.getnf.data.SpendingLine
import io.github.sandroroque.getnf.data.productsIn
import io.github.sandroroque.getnf.data.storesIn
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.Format
import io.github.sandroroque.getnf.ui.calmo.AmountRow
import io.github.sandroroque.getnf.ui.calmo.EmptyState
import io.github.sandroroque.getnf.ui.calmo.MidNumber
import io.github.sandroroque.getnf.ui.calmo.SectionLabel
import io.github.sandroroque.getnf.ui.calmo.Segments
import io.github.sandroroque.getnf.ui.calmo.Sub
import io.github.sandroroque.getnf.ui.calmo.TabHeader
import io.github.sandroroque.getnf.ui.home.rememberCategoryColor

/**
 * Gastos (stage 5, D81): one period at a time. Bars to pick the month or year; its total and what's estimated or
 * missing; then by category (tap to go down the tree) or by store; then the products there. Offline: read from the
 * phone's own database.
 */
@Composable
fun SpendingScreen(
  onScan: () -> Unit, onOpenProduct: (String) -> Unit, onCategories: () -> Unit, onSettings: () -> Unit,
  onIpci: (String?) -> Unit = {}, onOpenStore: (String) -> Unit = {},
) {
  val app = LocalContext.current.applicationContext as GetNFApp
  val lines by remember { app.database.spending().lines() }.collectAsStateWithLifecycle(null)
  val waiting by remember { app.database.spending().waitingForPage() }.collectAsStateWithLifecycle(0)
  val categories by remember { app.database.edit().categories() }.collectAsStateWithLifecycle(emptyList())
  val products by remember { app.database.edit().products() }.collectAsStateWithLifecycle(emptyList())
  var yearly by rememberSaveable { mutableStateOf(false) }
  var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
  var path by rememberSaveable { mutableStateOf(listOf<String>()) }
  var lens by rememberSaveable { mutableStateOf(0) }

  val parents = remember(categories) { categories.associate { it.id to it.parentId } }
  val spending = remember(lines, parents, yearly) { lines?.let { Spending(it, parents, yearly) } }
  val byId = remember(categories) { categories.associateBy { it.id } }
  val colorOf = rememberCategoryColor(categories)
  val selected = spending?.periods?.let { ps -> ps.firstOrNull { it.key == selectedKey } ?: ps.lastOrNull() }
  val node = path.lastOrNull()
  val titles = products.associate { it.productId to it.title }

  LazyColumn(
    Modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item {
      TabHeader("Gastos") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          for ((i, l) in listOf("Mês", "Ano").withIndex()) Text(l, style = MaterialTheme.typography.bodyMedium,
            color = if ((i == 1) == yearly) Calmo.fg else Calmo.mute,
            modifier = Modifier.clickable { yearly = i == 1; selectedKey = null })
        }
      }
    }
    if (spending == null) return@LazyColumn
    if (selected == null) {
      item { EmptyState("Os gastos aparecem aqui quando a primeira nota tiver a página da SEFAZ lida.") }
      return@LazyColumn
    }
    item { Bars(spending, selected) { selectedKey = it.key } }
    item {
      Column {
        MidNumber(Format.brl(spending.total(selected)))
        Sub("${label(selected, long = true)} · ${Format.count(spending.linesIn(selected).map { it.chave }.distinct().size, "nota", "notas")}")
        CaveatLine(spending.caveats(selected), waiting)
      }
    }
    item { Segments(listOf("Categorias", "Lojas"), lens) { lens = it } }

    if (lens == 1) {
      items(storesIn(spending.linesIn(selected)), key = { "s:${it.cnpj}" }) { s ->
        AmountRow(s.name ?: "Loja sem nome", Format.brl(s.netCents), subtitle = "${s.receipts} ${if (s.receipts == 1) "nota" else "notas"} · ${s.lines} itens",
          onClick = s.cnpj?.let { c -> { onOpenStore(c) } })
      }
      return@LazyColumn
    }

    if (path.isNotEmpty()) item {
      // Where we are in the tree; each step goes back up.
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Tudo", style = MaterialTheme.typography.bodySmall, color = Calmo.acc, modifier = Modifier.clickable { path = emptyList() })
        path.forEachIndexed { i, id ->
          Sub("›")
          Text(byId[id]?.label ?: "?", style = MaterialTheme.typography.bodySmall,
            color = if (i == path.lastIndex) Calmo.fg else Calmo.acc, modifier = Modifier.clickable { path = path.take(i + 1) })
        }
      }
    }
    val slices = spending.slices(selected, node)
    val total = slices.sumOf { it.netCents }.takeIf { it > 0 } ?: 1
    items(slices, key = { "c:${it.categoryId}" }) { s ->
      val here = s.categoryId == node && node != null
      val deeper = s.categoryId != null && !here
      AmountRow(
        title = when { s.categoryId == null -> "Sem categoria"; here -> "${byId[node]?.label} (sem subcategoria)"; else -> byId[s.categoryId]?.label ?: "?" },
        subtitle = "${Math.round(s.netCents * 100.0 / total)}% · ${s.lines} itens",
        amount = Format.brl(s.netCents), dot = colorOf(s.categoryId ?: node),
        onClick = if (deeper) ({ path = path + s.categoryId!! }) else null,
      )
    }
    item { IpciLine(lines.orEmpty(), spending, node, onIpci) }
    item { SectionLabel(if (node == null) "Produtos" else "Produtos em ${byId[node]?.label}", Modifier.padding(top = 6.dp)) }
    items(productsIn(spending.linesUnder(selected, node)).take(40), key = { "p:${it.productId}" }) { p ->
      AmountRow(titles[p.productId] ?: "?", Format.brl(p.netCents),
        subtitle = p.qty.entries.joinToString(" + ") { (unit, q) -> "${Format.e4(q)} ${unit.lowercase()}" },
        onClick = { onOpenProduct(p.productId) })
    }
  }
}

/** One bar per period, the selected one dark; tap to pick. A gap means no receipts, not zero spending. */
@Composable
private fun Bars(spending: Spending, selected: io.github.sandroroque.getnf.data.Period, onSelect: (io.github.sandroroque.getnf.data.Period) -> Unit) {
  val periods = spending.periods.takeLast(12)
  val max = periods.maxOfOrNull { spending.total(it) }?.takeIf { it > 0 } ?: 1
  val c = Calmo
  // Fixed-width bars, newest at the right: one month alone stays a bar, not a block.
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
      for (p in periods) {
        val f = spending.total(p).toFloat() / max
        androidx.compose.foundation.Canvas(Modifier.width(22.dp).height(78.dp).clickable { onSelect(p) }) {
          val h = size.height * f.coerceAtLeast(.02f)
          drawRoundRect(if (p == selected) c.fg else c.line, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h),
            size = androidx.compose.ui.geometry.Size(size.width, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f))
        }
      }
    }
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for (p in periods) Text(label(p).substringBefore("\n"), Modifier.width(22.dp), style = MaterialTheme.typography.labelSmall,
        color = if (p == selected) Calmo.fg else Calmo.mute, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
  }
}

/** What the number can't say, in one quiet line. */
@Composable
private fun CaveatLine(c: Caveats, waiting: Int) {
  val notes = listOfNotNull(
    "${Format.brl(c.proratedDiscountCents)} de desconto rateado (estimado)".takeIf { c.proratedDiscountCents > 0 },
    "${Format.brl(c.uncategorizedCents)} sem categoria".takeIf { c.uncategorizedCents > 0 },
    "$waiting ${if (waiting == 1) "nota esperando" else "notas esperando"} a página".takeIf { waiting > 0 },
  )
  if (notes.isNotEmpty()) Sub(notes.joinToString(" · "), Modifier.padding(top = 2.dp))
}

/** The IPCI's latest reading here, opening the full view (stage 7, D79). */
@Composable
private fun IpciLine(all: List<SpendingLine>, spending: Spending, categoryId: String?, onOpen: (String?) -> Unit) {
  val months = remember(all, categoryId) { Ipci.compute(if (categoryId == null) all else all.filter { categoryId in spending.ancestry(it.categoryId) }) }
  if (months.size < 2) return
  val latest = months.lastOrNull { it.reading != null }
  Row(Modifier.fillMaxWidth().clickable { onOpen(categoryId) }.padding(vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
    Sub(if (latest == null) "Preços: dados insuficientes por enquanto" else "Preços, ${label(latest.period, long = true)}", Modifier.weight(1f))
    if (latest != null) Text("%+.1f%%".format(latest.reading).replace('.', ','), style = MaterialTheme.typography.bodyLarge,
      color = if (latest.reading!! > 0) Calmo.up else Calmo.down)
    Sub(" ›")
  }
}
