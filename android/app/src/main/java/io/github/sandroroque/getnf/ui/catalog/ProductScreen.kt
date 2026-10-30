package io.github.sandroroque.getnf.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.PricePoint
import io.github.sandroroque.getnf.data.ProductRow
import io.github.sandroroque.getnf.data.StoreItemDetail
import io.github.sandroroque.getnf.data.TagRow
import io.github.sandroroque.getnf.data.pricePoint
import io.github.sandroroque.getnf.sync.SyncWorker
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.Format
import io.github.sandroroque.getnf.ui.calmo.AmountRow
import io.github.sandroroque.getnf.ui.calmo.BackHeader
import io.github.sandroroque.getnf.ui.calmo.ButtonKind
import io.github.sandroroque.getnf.ui.calmo.DecisionCard
import io.github.sandroroque.getnf.ui.calmo.FactRow
import io.github.sandroroque.getnf.ui.calmo.Mark
import io.github.sandroroque.getnf.ui.calmo.MidNumber
import io.github.sandroroque.getnf.ui.calmo.Pill
import io.github.sandroroque.getnf.ui.calmo.ScreenTitle
import io.github.sandroroque.getnf.ui.calmo.SectionLabel
import io.github.sandroroque.getnf.ui.calmo.Sub
import io.github.sandroroque.getnf.ui.review.SOLD_BY
import io.github.sandroroque.getnf.ui.review.sizeText
import io.github.sandroroque.getnf.ui.spending.TimeSeries

private sealed interface Sheet {
  data object Edit : Sheet
  data object Name : Sheet
  data object Category : Sheet
  data object Size : Sheet
  data object SoldBy : Sheet
  data object Join : Sheet
  /** The other product is sold in another unit: price per kg stops being comparable (data-model.md). */
  data class JoinUnits(val other: String, val units: String) : Sheet
}

/**
 * One product (D81): its price over time first, then what it is and where it was bought. Editing (name, category,
 * size, tags, joining or separating store items) lives behind "Editar".
 */
@Composable
fun ProductScreen(productId: String, onOpenProduct: (String) -> Unit, onBack: () -> Unit = {}, onOpenReceipt: (String) -> Unit = {}) {
  val context = LocalContext.current
  val app = context.applicationContext as GetNFApp
  val dao = app.database.edit()
  val product by remember(productId) { dao.product(productId) }.collectAsStateWithLifecycle(null)
  val items by remember(productId) { dao.storeItems(productId) }.collectAsStateWithLifecycle(emptyList())
  val purchases by remember(productId) { dao.purchases(productId) }.collectAsStateWithLifecycle(emptyList())
  val categories by remember { dao.categories() }.collectAsStateWithLifecycle(emptyList())
  val tags by remember { dao.tags() }.collectAsStateWithLifecycle(emptyList())
  val tagIds by remember(productId) { dao.tagIds(productId) }.collectAsStateWithLifecycle(emptyList())
  val products by remember { dao.products() }.collectAsStateWithLifecycle(emptyList())
  val allLines by remember { app.database.spending().lines() }.collectAsStateWithLifecycle(emptyList())
  val prices = remember(allLines, productId) { allLines.filter { it.productId == productId }.mapNotNull(::pricePoint) }
  var sheet by remember { mutableStateOf<Sheet?>(null) }
  val edit = rememberEdit()
  val editor = app.catalogEditor
  val done = { SyncWorker.enqueue(context) }
  val p = product ?: return
  val title = p.name ?: items.firstOrNull()?.description ?: "Produto"

  LazyColumn(
    Modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    item {
      BackHeader("Voltar", onBack) {
        Text("Editar", style = MaterialTheme.typography.bodyMedium, color = Calmo.acc,
          modifier = Modifier.clickable { sheet = Sheet.Edit }.padding(8.dp))
      }
    }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScreenTitle(title)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
          Sub(pathOf(categories, p.categoryId) ?: "Sem categoria")
          origin(p)?.let { Mark(it, you = it == "você") }
        }
      }
    }
    // A model's answer waiting for the user, right where the category is shown.
    if (p.categoryReview == "automatic" || (p.categoryId == null && p.suggestionCategoryId != null)) item {
      val proposed = p.categoryId ?: p.suggestionCategoryId
      DecisionCard(title = "É ${categories.firstOrNull { it.id == proposed }?.label ?: "?"}?", amount = null,
        why = if (p.categoryId == null) "Sugestão de um modelo, ainda não confirmada." else "Definida por um modelo, ainda não confirmada.") {
        Pill("Sim", ButtonKind.Solid) { edit { editor.confirm(p.id); done() } }
        Pill("Outra", ButtonKind.Ghost) { sheet = Sheet.Category }
      }
    }
    item { PriceBlock(prices) }
    item {
      Column {
        val byWeight = purchases.isNotEmpty() && purchases.all { it.unit?.uppercase() in setOf("KG", "L") }
        if (byWeight) FactRow("Vendido", "por ${purchases.first().unit!!.lowercase()}")
        else {
          val soldBy = items.mapNotNull { it.soldBy }.distinct().singleOrNull()
          // Shown once it's known; until then it's asked in Para você or set from Editar (D110).
          if (soldBy != null) FactRow("Vendido", SOLD_BY.getValue(soldBy).lowercase(),
            mark = if (items.any { it.soldByUser != null }) "você" else null, onClick = { sheet = Sheet.SoldBy })
          if (soldBy == null || soldBy == "unit") FactRow("Tamanho", size(p, items), mark = sizeMark(p, items), onClick = { sheet = Sheet.Size })
        }
        FactRow("Compras", "${Format.count(purchases.size, "compra", "compras")} · ${Format.brl(purchases.sumOf { it.netCents })}")
        FactRow("Nas notas", items.joinToString(" · ") { it.description })
        FactRow("Lojas", items.mapNotNull { it.issuerName }.distinct().joinToString(" · ").ifEmpty { "qualquer loja (código de barras)" })
        if (tagIds.isNotEmpty()) FactRow("Marcadores", tags.filter { it.id in tagIds }.joinToString(" · ") { it.description })
      }
    }
    item { SectionLabel("Compras", Modifier.padding(top = 6.dp)) }
    items(purchases, key = { "${it.chave}/${it.lineNo}" }) { b ->
      AmountRow(
        title = b.issuedAt?.let(Format::date) ?: "?",
        subtitle = "${b.issuerName ?: ""} · ${Format.e4(b.qtyE4)} ${b.unit.orEmpty().lowercase()} × ${Format.brl(Math.round(b.unitPriceE4 / 100.0))}" +
          if (b.override) " · só esta linha" else "",
        amount = Format.brl(b.netCents), onClick = { onOpenReceipt(b.chave) },
      )
    }
  }

  when (val s = sheet) {
    Sheet.Edit -> EditSheet(p, items, tags, tagIds, onDismiss = { sheet = null }, onPick = { sheet = it },
      onTag = { id, on -> edit { editor.setTag(p.id, id, on); done() } },
      onSeparate = { id -> sheet = null; edit { val fresh = editor.unlink(id); done(); onOpenProduct(fresh) } })
    Sheet.Name -> TextDialog("Nome do produto", listOf("Nome" to (p.name ?: "")), hint = "Vazio usa a descrição da nota mais recente.",
      onSave = { (name) -> sheet = null; edit { editor.rename(p.id, name); done() } }, onDismiss = { sheet = null })
    Sheet.Category -> CategoryPickerDialog(title, categories, current = p.categoryId, noneLabel = "Sem categoria",
      onPick = { id -> sheet = null; edit { editor.setCategory(p.id, id); done() } }, onDismiss = { sheet = null })
    Sheet.SoldBy -> CalmoSheet("Como é vendido", onDismiss = { sheet = null }) {
      Sub("Por quilo ou por litro, a quantidade da nota é o peso ou o volume, e o preço por kg ou litro sai dela.")
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val current = items.mapNotNull { it.soldBy }.distinct().singleOrNull()
        for ((way, label) in SOLD_BY) Pill(label, if (way == current) ButtonKind.Solid else ButtonKind.Outline) {
          sheet = null; edit { for (s in items) editor.setSoldBy(s.id, way); done() }
        }
      }
    }
    Sheet.Size -> SizeSheet(p, onDismiss = { sheet = null }) { e6, unit, manual -> sheet = null; edit { editor.setSize(p.id, e6, unit, manual); done() } }
    Sheet.Join -> ProductPickerDialog("Juntar a “$title”", products.filter { it.productId != p.id },
      onPick = { other ->
        sheet = null
        edit {
          val mine = dao.lineUnits(p.id).map(::byWeight).toSet(); val theirs = dao.lineUnits(other).map(::byWeight).toSet()
          if (mine.isNotEmpty() && theirs.isNotEmpty() && mine != theirs)
            sheet = Sheet.JoinUnits(other, (dao.lineUnits(p.id) + dao.lineUnits(other)).distinct().joinToString(" e "))
          else { join(app, other, p.id); done() }
        }
      }, onDismiss = { sheet = null })
    is Sheet.JoinUnits -> CalmoSheet("Unidades diferentes", onDismiss = { sheet = null }) {
      Sub("Um é vendido por peso e o outro por unidade (${s.units}). Juntos, o preço por kg deixa de ser comparável.")
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Juntar mesmo assim", ButtonKind.Solid) { sheet = null; edit { join(app, s.other, p.id); done() } }
        Pill("Cancelar", ButtonKind.Ghost) { sheet = null }
      }
    }
    null -> {}
  }
}

/** Everything about a product that can be changed by hand, in one sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditSheet(
  p: ProductRow, items: List<StoreItemDetail>, tags: List<TagRow>, tagIds: List<String>,
  onDismiss: () -> Unit, onPick: (Sheet) -> Unit, onTag: (String, Boolean) -> Unit, onSeparate: (String) -> Unit,
) {
  CalmoSheet("Editar produto", onDismiss) {
    Column {
      FactRow("Nome", p.name ?: "o da nota", onClick = { onPick(Sheet.Name) })
      FactRow("Categoria", "trocar", onClick = { onPick(Sheet.Category) })
      FactRow("Tamanho", "definir", onClick = { onPick(Sheet.Size) })
      FactRow("Vendido", "por unidade, quilo ou litro", onClick = { onPick(Sheet.SoldBy) })
      FactRow("Juntar", "outro produto a este", onClick = { onPick(Sheet.Join) })
    }
    if (tags.isNotEmpty()) {
      SectionLabel("Marcadores")
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in tags) Pill(t.description, if (t.id in tagIds) ButtonKind.Solid else ButtonKind.Ghost) { onTag(t.id, t.id !in tagIds) }
      }
    }
    if (items.size > 1) {
      SectionLabel("Itens das notas")
      for (s in items) AmountRow(s.description, null, subtitle = s.issuerName ?: "qualquer loja", trailingMark = "separar", onClick = { onSeparate(s.id) })
    }
  }
}

/** The latest price per kg/l (or unit), its change since the first, and the chart: one line per store. */
@Composable
private fun PriceBlock(points: List<PricePoint>) {
  if (points.isEmpty()) return
  val basis = points.groupBy { it.basis }.maxBy { it.value.size }
  val shown = basis.value.sortedBy { it.at }
  val last = shown.last(); val first = shown.first()
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(verticalAlignment = Alignment.Bottom) {
      MidNumber(Format.brl(last.cents))
      Sub(" /${basis.key}", Modifier.padding(bottom = 4.dp).weight(1f))
      if (shown.size > 1) {
        val change = (last.cents - first.cents) * 100.0 / first.cents
        Text(if (change == 0.0) "igual a ${Format.date(first.at)}" else "%+.1f%% desde %s".format(change, Format.date(first.at)).replace('.', ','),
          style = MaterialTheme.typography.bodySmall, color = when { change > 0 -> Calmo.up; change < 0 -> Calmo.down; else -> Calmo.mute })
      }
    }
    last.perItemCents?.let { Sub("${Format.brl(it)} cada, no pacote com ${last.packCount}") }
    if (shown.size > 1) {
      TimeSeries(shown.groupBy { it.storeName ?: "?" }.mapValues { (_, ps) -> ps.map { it.at to it.cents.toDouble() } },
        format = { Format.brl(Math.round(it)) })
      Sub("Preço de prateleira." + if (shown.map { it.storeName }.distinct().size > 1) " Uma linha por loja." else "")
    }
    // One purchase so far: what a second one gives, here where it will show (D116).
    else Sub("Compre de novo e o histórico de preço aparece aqui, loja a loja.")
  }
}

private suspend fun join(app: GetNFApp, other: String, into: String) {
  for (s in app.database.edit().storeItemsOf(other)) app.catalogEditor.link(s.id, into)
}

private fun byWeight(unit: String?) = unit?.uppercase() in setOf("KG", "G", "L", "ML")

/** Who set the category, as a mark (D81). */
private fun origin(p: ProductRow): String? = when {
  p.categoryId == null -> null
  p.categorySource == "user" -> "você"
  p.categorySource?.startsWith("jev") == true -> "auto"
  p.categorySource?.startsWith("llm") == true -> "revisor"
  p.categorySource == "import" -> "antes"
  else -> p.categorySource
}

/** The size as prices use it: by hand, else System-1's sure one (D108), else the parsed one; a pack says what's in it. */
private fun size(p: ProductRow, items: List<StoreItemDetail>): String {
  val read = items.firstOrNull { it.readSizeSource != null }
  val parsed = items.firstNotNullOfOrNull { s -> s.parsedSizeE6?.let { it to s.parsedSizeUnit } }
  val (e6, unit) = when {
    p.sizeSource == "manual" -> p.sizeE6 to p.sizeUnit
    read != null -> read.readSizeE6 to read.readSizeUnit
    else -> parsed?.first to parsed?.second
  }
  val pack = read?.readPackCount?.takeIf { it > 1 }
  return when {
    e6 == null && pack != null -> "pacote com $pack"
    e6 == null && (p.sizeSource == "manual" || read != null) -> "sem tamanho"
    e6 == null -> "desconhecido"
    pack != null -> "${sizeText(e6, unit ?: "kg")} ($pack × ${sizeText(e6 / pack, unit ?: "kg")})"
    else -> sizeText(e6, unit ?: "kg")
  }
}

private fun sizeMark(p: ProductRow, items: List<StoreItemDetail>) = when {
  p.sizeSource == "manual" -> "você"
  items.any { it.readSizeSource != null } -> "auto"
  items.any { it.parsedSizeE6 != null } -> "da nota"
  else -> null
}

/** A size by hand in kg or litres, "no size", or back to what the description says. */
@Composable
private fun SizeSheet(p: ProductRow, onDismiss: () -> Unit, onSave: (Long?, String?, Boolean) -> Unit) {
  var amount by remember { mutableStateOf(p.sizeE6?.let { Format.e4(it / 100) } ?: "") }
  var unit by remember { mutableStateOf(p.sizeUnit ?: "kg") }
  val e6 = amount.replace(".", "").replace(",", ".").toBigDecimalOrNull()?.movePointRight(6)?.toLong()
  CalmoSheet("Tamanho", onDismiss) {
    Sub("Quanto vem na embalagem, em kg ou litros (500 g = 0,5 kg).")
    OutlinedTextField(amount, { amount = it }, label = { Text("Quantidade") }, singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for (u in listOf("kg", "l")) Pill(u, if (unit == u) ButtonKind.Solid else ButtonKind.Ghost) { unit = u }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Pill("Salvar", ButtonKind.Solid, enabled = e6 != null && e6 > 0) { onSave(e6, unit, true) }
      Pill("Sem tamanho", ButtonKind.Ghost) { onSave(null, null, true) }
      Pill("O da nota", ButtonKind.Ghost) { onSave(null, null, false) }
    }
  }
}
