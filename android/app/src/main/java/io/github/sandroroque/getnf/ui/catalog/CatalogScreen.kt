package io.github.sandroroque.getnf.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.REVIEW_AUTOMATIC
import io.github.sandroroque.getnf.data.storesIn
import io.github.sandroroque.getnf.ui.Format
import io.github.sandroroque.getnf.ui.calmo.AmountRow
import io.github.sandroroque.getnf.ui.calmo.ButtonKind
import io.github.sandroroque.getnf.ui.calmo.EmptyState
import io.github.sandroroque.getnf.ui.calmo.Pill
import io.github.sandroroque.getnf.ui.calmo.SearchBox
import io.github.sandroroque.getnf.ui.calmo.Segments
import io.github.sandroroque.getnf.ui.calmo.TabHeader
import io.github.sandroroque.getnf.ui.home.rememberCategoryColor
import io.github.sandroroque.getnf.ui.page.rememberBudgetCheck

/**
 * Catálogo (D81): what you buy and where. Produtos, Categorias, Lojas and Notas, each searchable or browsable; all
 * editing starts from here.
 */
@Composable
fun CatalogScreen(
  initialSegment: Int, onOpenProduct: (String) -> Unit, onOpenStore: (String) -> Unit, onOpenReceipt: (String) -> Unit,
  onOpenPage: (String) -> Unit, onScanProduct: () -> Unit = {},
) {
  var segment by rememberSaveable { mutableStateOf(initialSegment) }
  Column(Modifier.fillMaxSize().statusBarsPadding()) {
    Column(Modifier.padding(horizontal = 20.dp)) {
      TabHeader("Catálogo")
      Segments(listOf("Produtos", "Categorias", "Lojas", "Notas"), segment) { segment = it }
    }
    when (segment) {
      0 -> ProductList(onOpenProduct, onScanProduct)
      1 -> CategoryTree()
      2 -> StoreList(onOpenStore)
      else -> ReceiptList(onOpenReceipt, onOpenPage)
    }
  }
}

@Composable
private fun ProductList(onOpenProduct: (String) -> Unit, onScanProduct: () -> Unit) {
  val app = LocalContext.current.applicationContext as GetNFApp
  val products by remember { app.database.edit().products() }.collectAsStateWithLifecycle(emptyList())
  val categories by remember { app.database.edit().categories() }.collectAsStateWithLifecycle(emptyList())
  val colorOf = rememberCategoryColor(categories)
  var query by rememberSaveable { mutableStateOf("") }
  var onlyAutomatic by rememberSaveable { mutableStateOf(false) }
  val automatic = products.count { it.categoryReview == REVIEW_AUTOMATIC }
  val q = fold(query.trim())
  val shown = products.filter { !onlyAutomatic || it.categoryReview == REVIEW_AUTOMATIC }
    .filter { q.isEmpty() || q in fold(it.title) || q in fold(it.description.orEmpty()) || q in fold(it.categoryLabel.orEmpty()) }
  LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp)) {
    item { SearchBox(query, { query = it }, "Buscar entre ${products.size} produtos") }
    // The camera, to find one by its barcode (camera plan, step 3).
    item { Row(Modifier.padding(top = 10.dp)) { Pill("Pelo código de barras", ButtonKind.Outline, onClick = onScanProduct) } }
    if (automatic > 0) item {
      Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Todos", if (!onlyAutomatic) ButtonKind.Solid else ButtonKind.Ghost) { onlyAutomatic = false }
        Pill("Não confirmadas ($automatic)", if (onlyAutomatic) ButtonKind.Solid else ButtonKind.Ghost) { onlyAutomatic = true }
      }
    }
    if (shown.isEmpty()) item { EmptyState(if (products.isEmpty()) "Os produtos aparecem aqui quando a primeira nota for lida." else "Nada com esse nome.") }
    items(shown, key = { it.productId }) { p ->
      AmountRow(
        title = p.title, amount = Format.brl(p.spentCents), dot = colorOf(p.categoryId),
        subtitle = "${p.categoryLabel ?: "Sem categoria"} · ${p.purchases} ${if (p.purchases == 1) "compra" else "compras"}",
        trailingMark = when { p.categoryReview == REVIEW_AUTOMATIC -> "auto"; else -> null },
        onClick = { onOpenProduct(p.productId) },
      )
    }
  }
}

@Composable
private fun StoreList(onOpenStore: (String) -> Unit) {
  val app = LocalContext.current.applicationContext as GetNFApp
  val lines by remember { app.database.spending().lines() }.collectAsStateWithLifecycle(emptyList())
  val stores = remember(lines) { storesIn(lines) }
  LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)) {
    if (stores.isEmpty()) item { EmptyState("As lojas aparecem aqui quando a primeira nota for lida.") }
    items(stores, key = { it.cnpj ?: "?" }) { s ->
      AmountRow(
        title = s.name ?: "Loja sem nome", amount = Format.brl(s.netCents),
        subtitle = "${s.receipts} ${if (s.receipts == 1) "nota" else "notas"} · ${s.lines} itens",
        onClick = s.cnpj?.let { c -> { onOpenStore(c) } },
      )
    }
  }
}

@Composable
private fun ReceiptList(onOpenReceipt: (String) -> Unit, onOpenPage: (String) -> Unit) {
  val app = LocalContext.current.applicationContext as GetNFApp
  val receipts by remember { app.database.lists().receipts() }.collectAsStateWithLifecycle(emptyList())
  val budgetCheck = rememberBudgetCheck()
  LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)) {
    if (receipts.isEmpty()) item { EmptyState("Nenhuma nota ainda. Toque no botão do meio para escanear o QR Code de uma nota fiscal.") }
    items(receipts, key = { it.chave }) { r ->
      AmountRow(
        title = r.title,
        subtitle = if (r.hasPage) Format.date(r.issuedAt ?: r.capturedAt) + " · ${r.lines} itens" + if (r.photos > 0) " · ${r.photos} fotos" else ""
          else listOfNotNull(r.storeName, "toque para buscar a página").joinToString(" · "),
        amount = r.paidCents?.let(Format::brl),
        trailingMark = r.scannedBy?.let { "por $it" },
        onClick = { if (r.hasPage) onOpenReceipt(r.chave) else budgetCheck.request(onFetch = { onOpenPage(r.chave) }, onLater = {}) },
      )
    }
  }
  budgetCheck.Dialog()
}
