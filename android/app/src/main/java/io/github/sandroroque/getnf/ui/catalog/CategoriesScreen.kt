package io.github.sandroroque.getnf.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.CategoryNode
import io.github.sandroroque.getnf.sync.SyncWorker
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.calmo.ButtonKind
import io.github.sandroroque.getnf.ui.calmo.Dot
import io.github.sandroroque.getnf.ui.calmo.Hairline
import io.github.sandroroque.getnf.ui.calmo.Pill
import io.github.sandroroque.getnf.ui.calmo.Sub
import androidx.compose.ui.Alignment

private sealed interface Action {
  data class Menu(val c: CategoryNode) : Action
  data class Create(val parent: CategoryNode?) : Action
  data class Rename(val c: CategoryNode) : Action
  data class Move(val c: CategoryNode) : Action
  data class Merge(val c: CategoryNode) : Action
  data class Split(val c: CategoryNode) : Action
  data class Delete(val c: CategoryNode) : Action
}

/**
 * The category tree, editable (D75): create, rename and describe, move, merge, split and delete. Every edit is logged
 * so later reviews and models can learn from it. A split keeps the products in the category and sends them to the
 * review list to pick one of the new parts.
 */
@Composable
fun CategoryTree() {
  val context = LocalContext.current
  val app = context.applicationContext as GetNFApp
  val categories by remember { app.database.edit().categories() }.collectAsStateWithLifecycle(emptyList())
  var action by remember { mutableStateOf<Action?>(null) }
  val edit = rememberEdit()
  val editor = app.catalogEditor
  val done = { SyncWorker.enqueue(context) }
  val colorOf = io.github.sandroroque.getnf.ui.home.rememberCategoryColor(categories)
  // Products counted in a category and everything under it.
  val totals = remember(categories) {
    val children = categories.groupBy { it.parentId }
    fun total(c: CategoryNode): Int = c.products + children[c.id].orEmpty().sumOf(::total)
    categories.associate { it.id to total(it) }
  }

  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
    item {
      Row(Modifier.padding(vertical = 10.dp)) { Pill("Nova categoria", ButtonKind.Outline) { action = Action.Create(null) } }
    }
    items(flatten(categories), key = { it.first.id }) { (c, depth) ->
      Column(Modifier.fillMaxWidth().clickable { action = Action.Menu(c) }) {
        Row(Modifier.fillMaxWidth().padding(start = (depth * 18).dp, top = 11.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
          if (depth == 0) Dot(colorOf(c.id))
          Column(Modifier.weight(1f).padding(start = if (depth == 0) 10.dp else 0.dp)) {
            Text(c.label, style = if (depth == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium, color = Calmo.fg)
            c.description?.let { Sub(it) }
              ?: c.descriptionSuggestion?.let { Text("Sugestão do LLM: $it", style = MaterialTheme.typography.bodySmall, color = Calmo.acc) }
          }
          val n = totals[c.id] ?: 0
          if (n > 0) Sub("$n")
        }
        if (depth == 0) Hairline()
      }
    }
  }

  when (val a = action) {
    is Action.Menu -> CalmoSheet(pathOf(categories, a.c.id) ?: a.c.label, onDismiss = { action = null }) {
        Column {
          val hasChildren = categories.any { it.parentId == a.c.id }
          if (a.c.description == null && a.c.descriptionSuggestion != null) {
            Text("Sugestão do LLM: ${a.c.descriptionSuggestion}", style = MaterialTheme.typography.bodySmall)
            Text("Usar a descrição sugerida", style = MaterialTheme.typography.bodyLarge,
              modifier = Modifier.fillMaxWidth().clickable { val id = a.c.id; action = null; edit { editor.acceptDescription(id); done() } }.padding(vertical = 12.dp))
          }
          for ((label, next) in listOfNotNull(
            "Renomear e descrever" to Action.Rename(a.c),
            "Nova subcategoria" to Action.Create(a.c),
            "Mover para…" to Action.Move(a.c),
            "Juntar com…" to Action.Merge(a.c),
            ("Dividir em partes…" to Action.Split(a.c)).takeIf { !hasChildren },
            ("Excluir…" to Action.Delete(a.c)).takeIf { !hasChildren },
          )) Text(label, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().clickable { action = next }.padding(vertical = 12.dp))
          if (hasChildren) Sub("Para excluir ou dividir, primeiro mova ou exclua as subcategorias.")
        }
    }
    is Action.Create -> TextDialog(
      if (a.parent == null) "Nova categoria" else "Nova em ${a.parent.label}",
      listOf("Nome" to "", "Para que serve (uma linha)" to ""),
      hint = "A descrição ajuda o modelo a escolher esta categoria.",
      onSave = { (label, description) ->
        action = null
        if (label.isNotBlank()) edit { editor.createCategory(a.parent?.id, label, description); done() }
      },
      onDismiss = { action = null },
    )
    is Action.Rename -> TextDialog(
      "Renomear", listOf("Nome" to a.c.label, "Para que serve (uma linha)" to (a.c.description ?: a.c.descriptionSuggestion ?: "")),
      onSave = { (label, description) ->
        action = null
        if (label.isNotBlank()) edit { editor.renameCategory(a.c.id, label, description); done() }
      },
      onDismiss = { action = null },
    )
    is Action.Move -> CategoryPickerDialog(
      "Mover “${a.c.label}” para", categories, exclude = setOf(a.c.id), noneLabel = "Topo da árvore",
      onPick = { parent -> action = null; edit { editor.moveCategory(a.c.id, parent); done() } },
      onDismiss = { action = null },
    )
    is Action.Merge -> CategoryPickerDialog(
      "Juntar “${a.c.label}” com", categories, exclude = setOf(a.c.id),
      onPick = { into -> action = null; if (into != null) edit { editor.mergeCategory(a.c.id, into); done() } },
      onDismiss = { action = null },
    )
    is Action.Split -> TextDialog(
      "Dividir “${a.c.label}”", listOf("Partes, uma por linha" to ""),
      hint = "Os produtos ficam em “${a.c.label}” até você escolher uma parte para cada um, na lista Para escolher.",
      confirm = "Dividir",
      onSave = { (text) -> action = null; edit { editor.splitCategory(a.c.id, text.split('\n', ',')); done() } },
      onDismiss = { action = null },
    )
    is Action.Delete -> if ((totals[a.c.id] ?: 0) == 0) CalmoSheet("Excluir “${a.c.label}”?", onDismiss = { action = null }) {
      Sub("Nenhum produto está nela.")
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Excluir", ButtonKind.Solid) { action = null; edit { editor.deleteCategory(a.c.id, null); done() } }
        Pill("Cancelar", ButtonKind.Ghost) { action = null }
      }
    } else CategoryPickerDialog(
      "Excluir “${a.c.label}”: para onde vão os ${totals[a.c.id]} produtos?", categories, exclude = setOf(a.c.id),
      current = a.c.parentId,
      onPick = { destination -> action = null; edit { editor.deleteCategory(a.c.id, destination); done() } },
      onDismiss = { action = null },
    )
    null -> {}
  }
}
