package io.github.sandroroque.getnf.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.ProductRow
import io.github.sandroroque.getnf.data.StoreItemDetail
import io.github.sandroroque.getnf.sync.SyncWorker
import io.github.sandroroque.getnf.ui.Format
import java.text.DateFormat
import java.util.Date

private sealed interface Dialog {
  data object Name : Dialog
  data object Category : Dialog
  data object Size : Dialog
  data object Join : Dialog
  /** The other product is sold in another unit: price per kg stops being comparable (data-model.md). */
  data class JoinUnits(val other: String, val units: String) : Dialog
}

/**
 * One product: its name, category (with where it came from), size, tags, the store items that count as it, and its
 * purchases. "Juntar" makes another product's store items count as this one (banana caturra in two markets).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProductScreen(productId: String, onOpenProduct: (String) -> Unit) {
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
  var dialog by remember { mutableStateOf<Dialog?>(null) }
  val edit = rememberEdit()
  val editor = app.catalogEditor
  val done = { SyncWorker.enqueue(context) }
  val p = product ?: return
  val title = p.name ?: items.firstOrNull()?.description ?: "Produto"

  Scaffold(topBar = { TopAppBar(title = { Text(title, maxLines = 1) }) }) { padding ->
    LazyColumn(
      Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item {
        Field("Nome", p.name ?: "(o da nota: ${items.firstOrNull()?.description ?: "?"})") { dialog = Dialog.Name }
        Field("Categoria", (pathOf(categories, p.categoryId) ?: "Sem categoria") + " · " + origin(p)) { dialog = Dialog.Category }
        if (p.categoryId == null && p.suggestionCategoryId != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Sugestão: ${pathOf(categories, p.suggestionCategoryId)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
          OutlinedButton(onClick = { edit { editor.confirm(p.id); done() } }) { Text("Aceitar") }
        }
        if (p.categoryReview == "automatic") OutlinedButton(onClick = { edit { editor.confirm(p.id); done() } }) { Text("Confirmar categoria") }
        Field("Tamanho", size(p, items)) { dialog = Dialog.Size }
        if (tags.isNotEmpty()) {
          Text("Marcadores", style = MaterialTheme.typography.labelLarge)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in tags) FilterChip(selected = t.id in tagIds, label = { Text(t.description) },
              onClick = { edit { editor.setTag(p.id, t.id, t.id !in tagIds); done() } })
          }
        }
        HorizontalDivider()
        Text("Itens nas notas", style = MaterialTheme.typography.titleSmall)
      }
      items(items, key = { it.id }) { s ->
        StoreItem(s, canSplit = items.size > 1) { edit { val fresh = editor.unlink(s.id); done(); onOpenProduct(fresh) } }
      }
      item {
        OutlinedButton(onClick = { dialog = Dialog.Join }) { Text("Juntar outro produto a este…") }
        Text("Só o mesmo item, mesma variedade e forma de venda; produtos parecidos ficam separados e se agrupam pela categoria.",
          style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Compras (${purchases.size}) · ${Format.brl(purchases.sumOf { it.netCents })}", style = MaterialTheme.typography.titleSmall)
      }
      items(purchases, key = { "${it.chave}/${it.lineNo}" }) { b ->
        Row(Modifier.fillMaxWidth()) {
          Column(Modifier.weight(1f)) {
            Text(b.issuedAt?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "?", style = MaterialTheme.typography.bodyMedium)
            Text("${b.issuerName ?: ""} · ${Format.e4(b.qtyE4)} ${b.unit.orEmpty()} × ${Format.brl(Math.round(b.unitPriceE4 / 100.0))}" +
              if (b.override) " · só esta linha" else "", style = MaterialTheme.typography.bodySmall)
          }
          Text(Format.brl(b.netCents), style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
  }

  when (dialog) {
    Dialog.Name -> TextDialog("Nome do produto", listOf("Nome" to (p.name ?: "")),
      hint = "Vazio usa a descrição da nota mais recente.",
      onSave = { (name) -> dialog = null; edit { editor.rename(p.id, name); done() } }, onDismiss = { dialog = null })
    Dialog.Category -> CategoryPickerDialog(title, categories, current = p.categoryId, noneLabel = "Sem categoria",
      onPick = { id -> dialog = null; edit { editor.setCategory(p.id, id); done() } }, onDismiss = { dialog = null })
    Dialog.Size -> SizeDialog(p, onDismiss = { dialog = null }) { e6, unit, manual ->
      dialog = null; edit { editor.setSize(p.id, e6, unit, manual); done() }
    }
    Dialog.Join -> ProductPickerDialog("Juntar a “$title”", products.filter { it.productId != p.id },
      onPick = { other ->
        dialog = null
        edit {
          val mine = dao.lineUnits(p.id).map(::byWeight).toSet(); val theirs = dao.lineUnits(other).map(::byWeight).toSet()
          if (mine.isNotEmpty() && theirs.isNotEmpty() && mine != theirs)
            dialog = Dialog.JoinUnits(other, (dao.lineUnits(p.id) + dao.lineUnits(other)).distinct().joinToString(" e "))
          else { join(app, other, p.id); done() }
        }
      },
      onDismiss = { dialog = null })
    is Dialog.JoinUnits -> AlertDialog(
      onDismissRequest = { dialog = null },
      title = { Text("Unidades diferentes") },
      text = { Text("Um é vendido por peso e o outro por unidade (${(dialog as Dialog.JoinUnits).units}). Juntos, o preço por kg deixa de ser comparável. Juntar mesmo assim?") },
      confirmButton = { TextButton(onClick = { val o = (dialog as Dialog.JoinUnits).other; dialog = null; edit { join(app, o, p.id); done() } }) { Text("Juntar") } },
      dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancelar") } },
    )
    null -> {}
  }
}

private suspend fun join(app: GetNFApp, other: String, into: String) {
  for (s in app.database.edit().storeItemsOf(other)) app.catalogEditor.link(s.id, into)
}

private fun byWeight(unit: String?) = unit?.uppercase() in setOf("KG", "G", "L", "ML")

@Composable
private fun Field(label: String, value: String, onEdit: () -> Unit) {
  Row(Modifier.fillMaxWidth()) {
    Column(Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.labelLarge)
      Text(value, style = MaterialTheme.typography.bodyLarge)
    }
    TextButton(onClick = onEdit) { Text("Alterar") }
  }
}

@Composable
private fun StoreItem(s: StoreItemDetail, canSplit: Boolean, onSplit: () -> Unit) {
  Row(Modifier.fillMaxWidth()) {
    Column(Modifier.weight(1f)) {
      Text(s.description, style = MaterialTheme.typography.bodyMedium)
      val code = if (s.codeKind == "gtin") "código de barras ${s.code}" else "código ${s.code} da loja"
      Text("${s.issuerName ?: "qualquer loja"} · $code · ${s.lines} compra(s)", style = MaterialTheme.typography.bodySmall)
    }
    if (canSplit) TextButton(onClick = onSplit) { Text("Separar") }
  }
}

private fun origin(p: ProductRow) = when {
  p.categoryId == null -> "sem categoria"
  p.categorySource == "user" && p.categoryReview == "corrected" -> "corrigida por você"
  p.categorySource == "user" -> "escolhida por você"
  p.categoryReview == "automatic" -> "automática (${p.categorySource}${p.categoryConfidence?.let { ", ${Math.round(it * 100)}%" } ?: ""}), não confirmada"
  p.categorySource == "import" -> "importada"
  else -> p.categorySource ?: "?"
}

private fun size(p: ProductRow, items: List<StoreItemDetail>): String {
  val parsed = items.firstNotNullOfOrNull { s -> s.parsedSizeE6?.let { it to s.parsedSizeUnit } }
  return when {
    p.sizeSource == "manual" && p.sizeE6 == null -> "sem tamanho (definido por você)"
    p.sizeSource == "manual" -> "${Format.e4(p.sizeE6!! / 100)} ${p.sizeUnit} (definido por você)"
    parsed != null -> "${Format.e4(parsed.first / 100)} ${parsed.second} (lido da descrição)"
    else -> "desconhecido"
  }
}

/** A size by hand in kg or litres, "no size", or back to what the description says. */
@Composable
private fun SizeDialog(p: ProductRow, onDismiss: () -> Unit, onSave: (Long?, String?, Boolean) -> Unit) {
  var amount by remember { mutableStateOf(p.sizeE6?.let { Format.e4(it / 100) } ?: "") }
  var unit by remember { mutableStateOf(p.sizeUnit ?: "kg") }
  val e6 = amount.replace(".", "").replace(",", ".").toBigDecimalOrNull()?.movePointRight(6)?.toLong()
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Tamanho") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Quanto vem na embalagem, em kg ou litros (500 g = 0,5 kg).", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(amount, { amount = it }, label = { Text("Quantidade") }, singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for (u in listOf("kg", "l")) FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = { onSave(null, null, true) }) { Text("Sem tamanho") }
          TextButton(onClick = { onSave(null, null, false) }) { Text("Usar o da descrição") }
        }
      }
    },
    confirmButton = { TextButton(enabled = e6 != null && e6 > 0, onClick = { onSave(e6, unit, true) }) { Text("Salvar") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
  )
}
