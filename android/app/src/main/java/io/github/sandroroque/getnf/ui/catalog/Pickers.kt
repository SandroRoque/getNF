package io.github.sandroroque.getnf.ui.catalog

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import io.github.sandroroque.getnf.ui.calmo.Icons
import io.github.sandroroque.getnf.ui.calmo.Mark
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.calmo.AmountRow
import io.github.sandroroque.getnf.ui.calmo.ButtonKind
import io.github.sandroroque.getnf.ui.calmo.Pill
import io.github.sandroroque.getnf.ui.calmo.ScreenTitle
import io.github.sandroroque.getnf.ui.calmo.SearchBox
import io.github.sandroroque.getnf.ui.calmo.Sub
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

internal fun fold(text: String) =
  Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

/** A sheet from the bottom with a title, in the Calmo look (D81): every picker and editor uses it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalmoSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Calmo.sheet, contentColor = Calmo.fg) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      ScreenTitle(title)
      content()
    }
  }
}

/**
 * Picks a category from the tree, searchable by label or description. A search keeps the matches' parents visible.
 * [exclude] hides categories (and their subtrees) that can't be chosen, such as the one being moved; [noneLabel]
 * offers "no category" / "top level". The tree opens collapsed, except down to [current]; + and − open and close a
 * category's children (D124). A search shows every match, opened.
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
  val byId = remember(categories) { categories.associateBy { it.id } }
  fun ancestors(id: String?) = generateSequence(byId[id]?.parentId) { p -> byId[p]?.parentId }
  val visible = remember(categories, exclude) {
    val hidden = mutableSetOf<String>().apply { addAll(exclude) }
    flatten(categories).filter { (c, _) -> (c.id !in hidden && c.parentId !in hidden).also { if (!it) hidden += c.id } }
  }
  val parents = remember(visible) { visible.mapNotNull { it.first.parentId }.toSet() }
  val expanded = remember(categories) { mutableStateListOf<String>().apply { addAll(ancestors(current)) } }
  val q = fold(query.trim())
  val shown = if (q.isEmpty()) visible.filter { (c, _) -> ancestors(c.id).all { it in expanded } } else {
    val matches = visible.filter { (c, _) -> q in fold(c.label) || q in fold(c.description.orEmpty()) }.map { it.first.id }.toSet()
    val keep = matches + matches.flatMap { ancestors(it).toList() }
    visible.filter { it.first.id in keep }
  }
  CalmoSheet(title, onDismiss) {
    SearchBox(query, { query = it }, "Buscar categoria")
    LazyColumn(Modifier.heightIn(max = 460.dp)) {
      if (noneLabel != null) item {
        Text(noneLabel, style = MaterialTheme.typography.bodyLarge, color = Calmo.mute,
          modifier = Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 12.dp))
      }
      items(shown, key = { it.first.id }) { (c, depth) ->
        val open = q.isNotEmpty() || c.id in expanded
        Row(Modifier.fillMaxWidth().clickable { onPick(c.id) }.padding(start = (depth * 18).dp),
          verticalAlignment = Alignment.CenterVertically) {
          // The toggle has its own touch target, so opening a category never picks it.
          Box(Modifier.size(44.dp).then(if (c.id in parents && q.isEmpty()) Modifier.clickable {
            if (open) expanded.remove(c.id) else expanded.add(c.id)
          } else Modifier), contentAlignment = Alignment.Center) {
            if (c.id in parents) Box(Modifier.size(22.dp).border(1.dp, if (q.isEmpty()) Calmo.fg else Calmo.line, RoundedCornerShape(6.dp)),
              contentAlignment = Alignment.Center) {
              Icon(if (open) Icons.Minus else Icons.Plus, if (open) "Fechar ${c.label}" else "Abrir ${c.label}",
                Modifier.size(14.dp), tint = if (q.isEmpty()) Calmo.fg else Calmo.mute)
            }
          }
          Text(
            c.label,
            style = if (depth == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
            fontWeight = if (c.id == current) FontWeight.Bold else null,
            color = Calmo.fg,
            modifier = Modifier.weight(1f).padding(vertical = 11.dp),
          )
          if (c.id == current) Mark("atual")
        }
      }
    }
  }
}

/** Picks another product, searchable by name or receipt wording. */
@Composable
fun ProductPickerDialog(title: String, products: List<ProductSummary>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
  var query by remember { mutableStateOf("") }
  val q = fold(query.trim())
  val shown = products.filter { q.isEmpty() || q in fold(it.title) || q in fold(it.description.orEmpty()) }
  CalmoSheet(title, onDismiss) {
    SearchBox(query, { query = it }, "Buscar produto")
    LazyColumn(Modifier.heightIn(max = 460.dp)) {
      items(shown, key = { it.productId }) { p ->
        AmountRow(p.title, Format.brl(p.spentCents), subtitle = p.categoryLabel ?: "Sem categoria", onClick = { onPick(p.productId) })
      }
    }
  }
}

/** One or two text fields (a name and a description, say) in a sheet. */
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
  CalmoSheet(title, onDismiss) {
    hint?.let { Sub(it) }
    fields.forEachIndexed { i, (label, _) ->
      OutlinedTextField(values[i].value, { values[i].value = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Pill(confirm, ButtonKind.Solid) { onSave(values.map { it.value }) }
      Pill("Cancelar", ButtonKind.Ghost, onClick = onDismiss)
    }
  }
}
