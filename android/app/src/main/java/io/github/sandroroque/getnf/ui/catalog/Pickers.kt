package io.github.sandroroque.getnf.ui.catalog

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sandroroque.getnf.data.CategoryNode
import io.github.sandroroque.getnf.data.ProductSummary
import io.github.sandroroque.getnf.ui.Format
import java.text.Normalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Runs a catalog edit; a refusal (IllegalArgumentException, worded for the user) shows as a short message. */
@Composable
fun rememberEdit(): (suspend () -> Unit) -> Unit {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  return remember(scope) { { block -> scope.launchEdit(context, block) } }
}

private fun CoroutineScope.launchEdit(context: android.content.Context, block: suspend () -> Unit) = launch {
  try { block() } catch (e: IllegalArgumentException) {
    Toast.makeText(context, e.message ?: "Não foi possível.", Toast.LENGTH_LONG).show()
  }
}

/** The tree in reading order, each with its depth: parents before their children, siblings by their order. */
fun flatten(categories: List<CategoryNode>): List<Pair<CategoryNode, Int>> {
  val children = categories.groupBy { it.parentId }
  val ids = categories.map { it.id }.toSet()
  fun walk(parent: String?, depth: Int): List<Pair<CategoryNode, Int>> =
    children[parent].orEmpty().sortedWith(compareBy({ it.sort }, { it.label })).flatMap { listOf(it to depth) + walk(it.id, depth + 1) }
  // A category whose parent is gone (deleted elsewhere) still shows, at the top.
  return walk(null, 0) + categories.filter { it.parentId != null && it.parentId !in ids }.flatMap { listOf(it to 0) + walk(it.id, 1) }
}

/** "Alimentos › Frutas" for a category id. */
fun pathOf(categories: List<CategoryNode>, id: String?): String? {
  val byId = categories.associateBy { it.id }
  val chain = generateSequence(id?.let(byId::get)) { c -> c.parentId?.let(byId::get) }.toList().reversed()
  return chain.takeIf { it.isNotEmpty() }?.joinToString(" › ") { it.label }
}

private fun fold(text: String) =
  Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

/**
 * Picks a category from the tree, searchable by label or description. A search keeps the matches' parents visible.
 * [exclude] hides categories (and their subtrees) that can't be chosen, such as the one being moved; [allowNone]
 * offers "no category" / "top level".
 */
@Composable
fun CategoryPickerDialog(
  title: String,
  categories: List<CategoryNode>,
  onPick: (String?) -> Unit,
  onDismiss: () -> Unit,
  current: String? = null,
  exclude: Set<String> = emptySet(),
  noneLabel: String? = null,
) {
  var query by remember { mutableStateOf("") }
  val visible = remember(categories, exclude) {
    val hidden = mutableSetOf<String>().apply { addAll(exclude) }
    flatten(categories).filter { (c, _) -> (c.id !in hidden && c.parentId !in hidden).also { if (!it) hidden += c.id } }
  }
  val q = fold(query.trim())
  val shown = if (q.isEmpty()) visible else {
    val matches = visible.filter { (c, _) -> q in fold(c.label) || q in fold(c.description.orEmpty()) }.map { it.first.id }.toSet()
    val byId = categories.associateBy { it.id }
    val keep = matches + matches.flatMap { generateSequence(byId[it]?.parentId) { p -> byId[p]?.parentId }.toList() }
    visible.filter { it.first.id in keep }
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column {
        OutlinedTextField(query, { query = it }, label = { Text("Buscar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
          if (noneLabel != null) item {
            Text(noneLabel, style = MaterialTheme.typography.bodyLarge,
              modifier = Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 10.dp))
          }
          items(shown, key = { it.first.id }) { (c, depth) ->
            Text(
              c.label,
              style = MaterialTheme.typography.bodyLarge,
              fontWeight = if (c.id == current) FontWeight.Bold else null,
              modifier = Modifier.fillMaxWidth().clickable { onPick(c.id) }.padding(start = (depth * 16).dp, top = 10.dp, bottom = 10.dp),
            )
          }
        }
      }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
  )
}

/** Picks another product, searchable by name or receipt wording. */
@Composable
fun ProductPickerDialog(title: String, products: List<ProductSummary>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
  var query by remember { mutableStateOf("") }
  val q = fold(query.trim())
  val shown = products.filter { q.isEmpty() || q in fold(it.title) || q in fold(it.description.orEmpty()) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column {
        OutlinedTextField(query, { query = it }, label = { Text("Buscar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
          items(shown, key = { it.productId }) { p ->
            Column(Modifier.fillMaxWidth().clickable { onPick(p.productId) }.padding(vertical = 8.dp)) {
              Text(p.title, style = MaterialTheme.typography.bodyLarge)
              Text("${p.categoryLabel ?: "Sem categoria"} · ${Format.brl(p.spentCents)}", style = MaterialTheme.typography.bodySmall)
            }
          }
        }
      }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
  )
}

/** A one- or two-field text dialog (label and description, a new name...). */
@Composable
fun TextDialog(
  title: String,
  fields: List<Pair<String, String>>,
  onSave: (List<String>) -> Unit,
  onDismiss: () -> Unit,
  confirm: String = "Salvar",
  hint: String? = null,
) {
  val values = remember { fields.map { mutableStateOf(it.second) } }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column {
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        fields.forEachIndexed { i, (label, _) ->
          OutlinedTextField(values[i].value, { values[i].value = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
        }
      }
    },
    confirmButton = { TextButton(onClick = { onSave(values.map { it.value }) }) { Text(confirm) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
  )
}
