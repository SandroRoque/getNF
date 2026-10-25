package io.github.sandroroque.getnf.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.productsIn
import io.github.sandroroque.getnf.ui.Format
import io.github.sandroroque.getnf.ui.calmo.AmountRow
import io.github.sandroroque.getnf.ui.calmo.BackHeader
import io.github.sandroroque.getnf.ui.calmo.BigNumber
import io.github.sandroroque.getnf.ui.calmo.ScreenTitle
import io.github.sandroroque.getnf.ui.calmo.SectionLabel
import io.github.sandroroque.getnf.ui.calmo.Sub

/** One store (D81's new lens): what was spent there, its receipts, and what you buy there most. */
@Composable
fun StoreScreen(cnpj: String, onBack: () -> Unit, onOpenReceipt: (String) -> Unit, onOpenProduct: (String) -> Unit) {
  val app = LocalContext.current.applicationContext as GetNFApp
  val lines by remember { app.database.spending().lines() }.collectAsStateWithLifecycle(emptyList())
  val receipts by remember { app.database.lists().receipts() }.collectAsStateWithLifecycle(emptyList())
  val products by remember { app.database.edit().products() }.collectAsStateWithLifecycle(emptyList())
  val here = remember(lines, cnpj) { lines.filter { it.storeCnpj == cnpj } }
  val chaves = here.map { it.chave }.toSet()
  val titles = products.associate { it.productId to it.title }
  val first = here.mapNotNull { it.issuedAt }.minOrNull()

  LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)) {
    item { BackHeader("Catálogo", onBack) }
    item {
      Column {
        ScreenTitle(here.firstNotNullOfOrNull { it.storeName } ?: "Loja")
        Sub("${chaves.size} ${if (chaves.size == 1) "nota" else "notas"}" + (first?.let { " desde ${Format.date(it)}" } ?: ""))
      }
    }
    item { BigNumber(Format.brl(here.sumOf { it.netCents })) }
    item { SectionLabel("Mais comprados aqui") }
    items(productsIn(here).take(8), key = { "p:${it.productId}" }) { p ->
      AmountRow(titles[p.productId] ?: "?", Format.brl(p.netCents), subtitle = "${p.lines} ${if (p.lines == 1) "compra" else "compras"}",
        onClick = { onOpenProduct(p.productId) })
    }
    item { SectionLabel("Notas") }
    items(receipts.filter { it.chave in chaves }, key = { "r:${it.chave}" }) { r ->
      AmountRow(Format.date(r.issuedAt ?: r.capturedAt), r.paidCents?.let(Format::brl), subtitle = "${r.lines} itens",
        onClick = { onOpenReceipt(r.chave) })
    }
  }
}
