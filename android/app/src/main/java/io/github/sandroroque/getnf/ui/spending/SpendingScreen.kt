package io.github.sandroroque.getnf.ui.spending

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.CategoryNode
import io.github.sandroroque.getnf.data.Period
import io.github.sandroroque.getnf.data.Spending
import io.github.sandroroque.getnf.data.categoryTrend
import io.github.sandroroque.getnf.data.productsIn
import io.github.sandroroque.getnf.ui.Format
import io.github.sandroroque.getnf.ui.catalog.flatten

/**
 * Gastos (stage 5): spending per month or year as stacked bars, the selected period's categories (tap to go down the
 * tree), the products there, and what the numbers can't say (estimated discounts, uncategorized spending, receipts
 * without their page). Read from the phone's own database: works offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingScreen(onScan: () -> Unit, onOpenProduct: (String) -> Unit, onCategories: () -> Unit, onSettings: () -> Unit) {
  val app = LocalContext.current.applicationContext as GetNFApp
  val lines by remember { app.database.spending().lines() }.collectAsStateWithLifecycle(null)
  val waiting by remember { app.database.spending().waitingForPage() }.collectAsStateWithLifecycle(0)
  val categories by remember { app.database.edit().categories() }.collectAsStateWithLifecycle(emptyList())
  val products by remember { app.database.edit().products() }.collectAsStateWithLifecycle(emptyList())
  var yearly by rememberSaveable { mutableStateOf(false) }
  var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
  var path by rememberSaveable { mutableStateOf(listOf<String>()) }
  var menu by remember { mutableStateOf(false) }

  val parents = remember(categories) { categories.associate { it.id to it.parentId } }
  val spending = remember(lines, parents, yearly) { lines?.let { Spending(it, parents, yearly) } }
  val byId = remember(categories) { categories.associateBy { it.id } }
  val colorOf = remember(categories) {
    val tops = flatten(categories).filter { it.second == 0 }.map { it.first.id }
    val f: (String?) -> Color = { id -> tops.indexOf(id).takeIf { it >= 0 }?.let { PALETTE[it % PALETTE.size] } ?: UNCATEGORIZED }
    f
  }
  val selected = spending?.periods?.let { ps -> ps.firstOrNull { it.key == selectedKey } ?: ps.lastOrNull() }
  val node = path.lastOrNull()

  Scaffold(
    topBar = {
      TopAppBar(title = { Text("Gastos") }, actions = {
        TextButton(onClick = { menu = true }) { Text("Mais") }
        DropdownMenu(menu, { menu = false }) {
          DropdownMenuItem(text = { Text("Categorias") }, onClick = { menu = false; onCategories() })
          DropdownMenuItem(text = { Text("Ajustes") }, onClick = { menu = false; onSettings() })
        }
      })
    },
    floatingActionButton = { ExtendedFloatingActionButton(onClick = onScan) { Text("Escanear nota") } },
  ) { padding ->
    if (spending == null) return@Scaffold
    if (selected == null) {
      Text("Nenhum gasto ainda. Escaneie uma nota e busque a página dela.", Modifier.padding(padding).padding(16.dp))
      return@Scaffold
    }
    val slices = spending.slices(selected, node)
    val total = slices.sumOf { it.netCents }
    val nodeLines = spending.linesUnder(selected, node)
    val caveats = spending.caveats(selected)
    LazyColumn(
      Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(selected = !yearly, onClick = { yearly = false; selectedKey = null }, label = { Text("Mês") })
          FilterChip(selected = yearly, onClick = { yearly = true; selectedKey = null }, label = { Text("Ano") })
        }
        StackedBars(spending.periods, spending.stacks(), colorOf, selected) { selectedKey = it.key }
        Text("${label(selected, long = true).replaceFirstChar { it.uppercase() }}: ${Format.brl(spending.total(selected))}",
          style = MaterialTheme.typography.titleMedium)
        Text("${spending.linesIn(selected).map { it.chave }.distinct().size} nota(s)", style = MaterialTheme.typography.bodySmall)
      }
      item { Caveats(caveats, waiting) }
      item {
        // Where we are in the tree: "Tudo › Alimentos › Frutas"; each step goes back up.
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
          TextButton(onClick = { path = emptyList() }) { Text("Tudo") }
          path.forEachIndexed { i, id ->
            Text("›")
            TextButton(onClick = { path = path.take(i + 1) }) { Text(byId[id]?.label ?: "?") }
          }
        }
      }
      items(slices, key = { "slice:${it.categoryId}" }) { slice ->
        val here = slice.categoryId == node && node != null
        val drillable = slice.categoryId != null && !here && categories.any { it.parentId == slice.categoryId }
        val name = when {
          slice.categoryId == null -> "Sem categoria"
          here -> "${byId[node]?.label} (sem subcategoria)"
          else -> byId[slice.categoryId]?.label ?: "?"
        }
        Row(
          Modifier.fillMaxWidth().clickable(enabled = drillable || (slice.categoryId != null && !here)) {
            path = path + slice.categoryId!!
          }.padding(vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Canvas(Modifier.size(12.dp)) { drawCircle(colorOf(path.firstOrNull() ?: slice.categoryId)) }
          Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Row {
              Text(name + if (drillable) " ›" else "", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
              Text(Format.brl(slice.netCents), style = MaterialTheme.typography.bodyLarge)
            }
            ShareBar(if (total > 0) slice.netCents.toFloat() / total else 0f, colorOf(path.firstOrNull() ?: slice.categoryId), Modifier.fillMaxWidth())
            Text("${percent(slice.netCents, total)} · ${slice.lines} item(ns)", style = MaterialTheme.typography.bodySmall)
          }
        }
      }
      if (node != null) item { Trend(spending, node) }
      item {
        HorizontalDivider()
        Text(if (node == null) "Produtos" else "Produtos em ${byId[node]?.label}", style = MaterialTheme.typography.titleSmall,
          modifier = Modifier.padding(top = 8.dp))
      }
      val titles = products.associate { it.productId to it.title }
      items(productsIn(nodeLines).take(50), key = { "product:${it.productId}" }) { p ->
        Row(Modifier.fillMaxWidth().clickable { onOpenProduct(p.productId) }.padding(vertical = 6.dp)) {
          Column(Modifier.weight(1f)) {
            Text(titles[p.productId] ?: "?", style = MaterialTheme.typography.bodyMedium)
            Text(p.qty.entries.joinToString(" + ") { (unit, q) -> "${Format.e4(q)} ${unit.lowercase()}" }, style = MaterialTheme.typography.bodySmall)
          }
          Text(Format.brl(p.netCents), style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
  }
}

/** What the numbers can't fully say, in plain words, only when it applies. */
@Composable
private fun Caveats(c: io.github.sandroroque.getnf.data.Caveats, waiting: Int) {
  val notes = listOfNotNull(
    "${Format.brl(c.proratedDiscountCents)} de desconto foi rateado entre os itens: o valor por item é estimado.".takeIf { c.proratedDiscountCents > 0 },
    "${Format.brl(c.uncategorizedCents)} ainda sem categoria.".takeIf { c.uncategorizedCents > 0 },
    "$waiting nota(s) esperando a página da SEFAZ não entram aqui.".takeIf { waiting > 0 },
    "${c.undatedLines} item(ns) sem data ficam de fora.".takeIf { c.undatedLines > 0 },
  )
  if (notes.isEmpty()) return
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
  }
}

/** A category's price direction across months: preliminary, until stage 7's method (D64). */
@Composable
private fun Trend(spending: Spending, categoryId: String) {
  val all = spending.periods.flatMap { spending.linesUnder(it, categoryId) }
  val trend = remember(all) { categoryTrend(all) }
  if (trend.size < 2) return
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text("Preços nesta categoria", style = MaterialTheme.typography.titleSmall)
      TimeSeries(mapOf("" to trend.map { (p, v) -> java.time.YearMonth.parse(p.key).atDay(15).toEpochDay() to v }),
        format = { "%.0f".format(it) })
      val change = trend.last().second - 100
      Text("${if (change >= 0) "+" else ""}${"%.1f".format(change)}% desde ${label(trend.first().first, long = true)}, " +
        "comparando cada produto com o próprio preço. Prévia: o método do índice vem na etapa 7.",
        style = MaterialTheme.typography.bodySmall)
    }
  }
}

private fun percent(part: Long, total: Long) = if (total > 0) "${Math.round(part * 100.0 / total)}%" else "–"
