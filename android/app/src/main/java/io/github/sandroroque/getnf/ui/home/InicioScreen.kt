package io.github.sandroroque.getnf.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.CategoryNode
import io.github.sandroroque.getnf.data.Decision
import io.github.sandroroque.getnf.data.Ipci
import io.github.sandroroque.getnf.data.Spending
import io.github.sandroroque.getnf.data.decisions
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.Format
import io.github.sandroroque.getnf.ui.calmo.AccountBadge
import io.github.sandroroque.getnf.ui.calmo.AmountRow
import io.github.sandroroque.getnf.ui.calmo.BigNumber
import io.github.sandroroque.getnf.ui.calmo.DecisionCard
import io.github.sandroroque.getnf.ui.calmo.EmptyState
import io.github.sandroroque.getnf.ui.calmo.MidNumber
import io.github.sandroroque.getnf.ui.calmo.SectionLabel
import io.github.sandroroque.getnf.ui.calmo.Sub
import io.github.sandroroque.getnf.ui.catalog.flatten
import io.github.sandroroque.getnf.ui.spending.label

/**
 * Início (D81): how this month is going. The month's total, its biggest categories, what's waiting for the user,
 * personal inflation, and the last receipts; each opens its full view.
 */
@Composable
fun InicioScreen(
  onSpending: () -> Unit, onDecisions: () -> Unit, onIpci: () -> Unit, onReceipt: (String) -> Unit, onSettings: () -> Unit,
) {
  val app = LocalContext.current.applicationContext as GetNFApp
  val lines by remember { app.database.spending().lines() }.collectAsStateWithLifecycle(null)
  val categories by remember { app.database.edit().categories() }.collectAsStateWithLifecycle(emptyList())
  val products by remember { app.database.edit().products() }.collectAsStateWithLifecycle(emptyList())
  val receipts by remember { app.database.lists().receipts() }.collectAsStateWithLifecycle(emptyList())
  val links by remember { app.database.edit().linkSuggestions() }.collectAsStateWithLifecycle(emptyList())
  val sizes by remember { app.database.edit().sizeSuggestions() }.collectAsStateWithLifecycle(emptyList())
  val checks by remember { app.database.lists().photoDiscountChecks() }.collectAsStateWithLifecycle(emptyList())
  val account = remember { app.accountSettings.account() }
  val parents = remember(categories) { categories.associate { it.id to it.parentId } }
  val spending = remember(lines, parents) { lines?.let { Spending(it, parents) } }
  val queue = remember(receipts, products, categories, links, sizes, checks) { decisions(receipts, products, categories, links, sizes, checks) }
  val ipci = remember(lines) { lines?.let { Ipci.compute(it) }?.lastOrNull { it.reading != null } }
  val colorOf = rememberCategoryColor(categories)

  val month = spending?.periods?.lastOrNull()
  LazyColumn(
    Modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    item {
      Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Sub(month?.let { label(it, long = true).replaceFirstChar { c -> c.uppercase() } } ?: "getNF", Modifier.weight(1f))
        AccountBadge(account?.email?.take(1) ?: "·", onSettings)
      }
    }
    if (spending == null) return@LazyColumn
    if (month == null) {
      item { EmptyState("Nenhuma nota ainda. Toque no botão do meio para escanear o QR Code de uma nota fiscal.") }
    } else {
      val previous = spending.periods.dropLast(1).lastOrNull()
      item {
        Column(Modifier.clickable(onClick = onSpending)) {
          BigNumber(Format.brl(spending.total(month)))
          Sub("em ${Format.count(spending.linesIn(month).map { it.chave }.distinct().size, "nota", "notas")}" +
            (previous?.let { " · ${label(it, long = true)} foi ${Format.brl(spending.total(it))}" } ?: ""))
        }
      }
      item {
        Column {
          for (s in spending.slices(month, null).take(4)) {
            AmountRow(
              title = categories.firstOrNull { it.id == s.categoryId }?.label ?: "Sem categoria",
              amount = Format.brl(s.netCents), dot = colorOf(s.categoryId), onClick = onSpending,
            )
          }
        }
      }
    }
    item { QueueCard(queue, onDecisions) }
    if (ipci != null) item {
      Row(Modifier.fillMaxWidth().clickable(onClick = onIpci).padding(vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
        Sub("Inflação pessoal, ${label(ipci.period, long = true)}", Modifier.weight(1f))
        MidNumber("%+.1f%%".format(ipci.reading).replace('.', ','), color = if (ipci.reading!! > 0) Calmo.up else Calmo.down)
      }
    }
    if (receipts.isNotEmpty()) {
      item { SectionLabel("Últimas notas") }
      items(receipts.take(3), key = { it.chave }) { r ->
        AmountRow(
          title = r.title,
          subtitle = if (r.hasPage) Format.date(r.issuedAt ?: r.capturedAt) + " · ${r.lines} itens"
            else listOfNotNull(r.storeName, "busque a página").joinToString(" · "),
          amount = r.paidCents?.let(Format::brl), onClick = { onReceipt(r.chave) },
        )
      }
    }
  }
}

/** "5 decisões para você", with what kinds; or that there's nothing to decide. */
@Composable
private fun QueueCard(queue: List<Decision>, onOpen: () -> Unit) {
  if (queue.isEmpty()) {
    Sub("Nada para decidir agora. ✓")
    return
  }
  val kinds = listOfNotNull(
    queue.count { it is Decision.FetchPage }.takeIf { it > 0 }?.let { "$it ${if (it == 1) "página" else "páginas"} para buscar" },
    queue.count { it is Decision.PickCategory || it is Decision.PickPart }.takeIf { it > 0 }?.let { "$it para escolher categoria" },
    queue.count { it is Decision.Dispute }.takeIf { it > 0 }?.let { "$it em dúvida" },
    queue.count { it is Decision.SameProduct || it is Decision.Size || it is Decision.Description || it is Decision.CheckPhotoDiscounts }
      .takeIf { it > 0 }?.let { "$it ${if (it == 1) "sugestão" else "sugestões"}" },
  )
  DecisionCard(
    title = "${queue.size} ${if (queue.size == 1) "decisão" else "decisões"} para você", amount = null,
    why = kinds.joinToString(" · "), onOpen = onOpen,
  ) {}
}

/** A category's colour: its top-level ancestor's, by tree order (the same everywhere in the app). */
@Composable
fun rememberCategoryColor(categories: List<CategoryNode>): (String?) -> Color {
  val c = Calmo
  return remember(categories, c) {
    val byId = categories.associateBy { it.id }
    val tops = flatten(categories).filter { it.second == 0 }.map { it.first.id }
    val f: (String?) -> Color = { id ->
      var top = id?.let(byId::get)
      while (top?.parentId != null && byId[top.parentId] != null) top = byId[top.parentId]
      tops.indexOf(top?.id).let { i -> if (i < 0) c.none else c.categories[i % c.categories.size] }
    }
    f
  }
}
