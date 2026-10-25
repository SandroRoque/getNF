package io.github.sandroroque.getnf.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.CategoryNode
import io.github.sandroroque.getnf.data.ProductSummary
import io.github.sandroroque.getnf.sync.SyncWorker
import io.github.sandroroque.getnf.ui.Format
import java.text.Normalizer

private enum class Filter(val label: String) { PICK("Para escolher"), SUGGESTIONS("Sugestões"), AUTOMATIC("Automáticas"), ALL("Todos") }

/**
 * The products, by what was spent on them. "Para escolher": no category yet, only a model's suggestion, or a category
 * that was split. "Automáticas": set by a model, not confirmed (D69). Confirm with one tap, or pick another category.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductsScreen(onOpenProduct: (String) -> Unit, onCategories: () -> Unit = {}) {
  val context = LocalContext.current
  val app = context.applicationContext as GetNFApp
  val products by remember { app.database.edit().products() }.collectAsStateWithLifecycle(emptyList())
  val categories by remember { app.database.edit().categories() }.collectAsStateWithLifecycle(emptyList())
  val links by remember { app.database.edit().linkSuggestions() }.collectAsStateWithLifecycle(emptyList())
  val sizes by remember { app.database.edit().sizeSuggestions() }.collectAsStateWithLifecycle(emptyList())
  val toPick = products.count { it.toPick }
  val automatic = products.count { it.unconfirmed && !it.toPick }
  var filter by rememberSaveable { mutableStateOf<Filter?>(null) }
  val current = filter ?: when { toPick > 0 -> Filter.PICK; automatic > 0 -> Filter.AUTOMATIC; else -> Filter.ALL }
  var query by rememberSaveable { mutableStateOf("") }
  var picking by remember { mutableStateOf<ProductSummary?>(null) }
  val edit = rememberEdit()
  val done = { SyncWorker.enqueue(context) }

  val q = fold(query.trim())
  val shown = products.filter {
    when (current) { Filter.PICK -> it.toPick; Filter.AUTOMATIC -> it.unconfirmed && !it.toPick; Filter.ALL -> true; Filter.SUGGESTIONS -> false }
  }.filter { q.isEmpty() || q in fold(it.title) || q in fold(it.description.orEmpty()) || q in fold(it.categoryLabel.orEmpty()) }

  Scaffold(topBar = { TopAppBar(title = { Text("Produtos") }, actions = { TextButton(onClick = onCategories) { Text("Categorias") } }) }) { padding ->
    LazyColumn(
      Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      item {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for (f in Filter.entries) {
            val n = when (f) { Filter.PICK -> toPick; Filter.SUGGESTIONS -> links.size + sizes.size; Filter.AUTOMATIC -> automatic; Filter.ALL -> products.size }
            if (f == Filter.SUGGESTIONS && n == 0) continue
            FilterChip(selected = f == current, onClick = { filter = f }, label = { Text("${f.label} ($n)") })
          }
        }
        OutlinedTextField(query, { query = it }, label = { Text("Buscar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (shown.isEmpty() && current != Filter.SUGGESTIONS) Text(
          if (current == Filter.PICK) "Nada para escolher. ✓" else "Nenhum produto.",
          style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp),
        )
      }
      if (current == Filter.SUGGESTIONS) {
        items(links, key = { "link:${it.id}" }) { l ->
          Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Mesmo produto?", style = MaterialTheme.typography.labelLarge)
            Text("${l.itemDescription}${l.itemStore?.let { " ($it)" } ?: ""}", style = MaterialTheme.typography.bodyLarge)
            Text("= ${l.productName ?: l.productDescription}${l.productStore?.let { " ($it)" } ?: ""}", style = MaterialTheme.typography.bodyLarge)
            l.reason?.let { Text("O LLM: $it", style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(onClick = { edit { app.catalogEditor.acceptLink(l.id); done() } }) { Text("É o mesmo") }
              TextButton(onClick = { edit { app.catalogEditor.refuseLink(l.id); done() } }) { Text("Não é") }
            }
          }
          HorizontalDivider()
        }
        items(sizes, key = { "size:${it.productId}" }) { z ->
          Column(Modifier.fillMaxWidth().clickable { onOpenProduct(z.productId) }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Tamanho", style = MaterialTheme.typography.labelLarge)
            Text(z.name ?: z.description ?: "?", style = MaterialTheme.typography.bodyLarge)
            Text("O LLM leu ${Format.e4(z.sizeE6 / 100)} ${z.unit} na descrição.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(onClick = { edit { app.catalogEditor.acceptSize(z.productId); done() } }) { Text("Aceitar") }
              TextButton(onClick = { onOpenProduct(z.productId) }) { Text("Outro tamanho") }
            }
          }
          HorizontalDivider()
        }
      }
      items(shown, key = { it.productId }) { p ->
        ProductRow(p, categories, onOpen = { onOpenProduct(p.productId) },
          onConfirm = { edit { app.catalogEditor.confirm(p.productId); done() } },
          onUseSuggestion = { edit { app.catalogEditor.setCategory(p.productId, p.suggestionCategoryId); done() } },
          onChange = { picking = p })
        HorizontalDivider()
      }
    }
  }

  picking?.let { p ->
    CategoryPickerDialog(
      title = p.title, categories = categories, current = p.categoryId ?: p.suggestionCategoryId,
      onPick = { id -> picking = null; edit { app.catalogEditor.setCategory(p.productId, id); done() } },
      onDismiss = { picking = null },
    )
  }
}

@Composable
private fun ProductRow(
  p: ProductSummary, categories: List<CategoryNode>, onOpen: () -> Unit, onConfirm: () -> Unit, onUseSuggestion: () -> Unit, onChange: () -> Unit,
) {
  Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(verticalAlignment = Alignment.Top) {
      Text(p.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Text(Format.brl(p.spentCents), style = MaterialTheme.typography.bodyMedium)
    }
    if (p.name != null && p.description != null) Text(p.description, style = MaterialTheme.typography.bodySmall)
    val proposal = when {
      p.categoryHasChildren -> "${pathOf(categories, p.categoryId)} foi dividida: escolha uma parte"
      p.disputed -> "${who(p.categorySource)}: ${pathOf(categories, p.categoryId)} · o LLM acha: ${pathOf(categories, p.suggestionCategoryId)}"
      p.categoryId != null -> pathOf(categories, p.categoryId) + when {
        p.categoryReview == "automatic" -> " · automática" + (p.categoryConfidence?.let { " ${percent(it)}" } ?: "")
        p.categoryReview == "confirmed" || p.categoryReview == "corrected" -> " ✓"
        else -> ""
      }
      p.suggestionCategoryId != null -> "Sugestão: ${pathOf(categories, p.suggestionCategoryId)} ${percent(p.suggestionConfidence ?: 0.0)}"
      else -> "Sem categoria"
    }
    Text(proposal, style = MaterialTheme.typography.bodySmall,
      color = if (p.toPick) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
    if (p.toPick || p.unconfirmed) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      val confirmable = !p.categoryHasChildren && (p.categoryId != null || p.suggestionCategoryId != null)
      if (p.disputed) {
        OutlinedButton(onClick = onConfirm) { Text("Manter") }
        OutlinedButton(onClick = onUseSuggestion) { Text("Usar a do LLM") }
      } else if (confirmable) OutlinedButton(onClick = onConfirm) { Text("Confirmar") }
      TextButton(onClick = onChange) { Text(if (confirmable) "Outra categoria" else "Escolher categoria") }
    }
  }
}

/** Who set a category, in a word. */
private fun who(source: String?) = when (source) { "jev" -> "Jev"; "import" -> "Importada"; "llm" -> "LLM"; else -> source ?: "?" }

private fun percent(x: Double) = "${Math.round(x * 100)}%"

private fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
