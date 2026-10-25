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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen() {
  val context = LocalContext.current
  val app = context.applicationContext as GetNFApp
  val categories by remember { app.database.edit().categories() }.collectAsStateWithLifecycle(emptyList())
  var action by remember { mutableStateOf<Action?>(null) }
  val edit = rememberEdit()
  val editor = app.catalogEditor
  val done = { SyncWorker.enqueue(context) }
  // Products counted in a category and everything under it.
  val totals = remember(categories) {
    val children = categories.groupBy { it.parentId }
    fun total(c: CategoryNode): Int = c.products + children[c.id].orEmpty().sumOf(::total)
    categories.associate { it.id to total(it) }
  }

  Scaffold(
    topBar = { TopAppBar(title = { Text("Categorias") }) },
    floatingActionButton = { ExtendedFloatingActionButton(onClick = { action = Action.Create(null) }) { Text("Nova categoria") } },
  ) { padding ->
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
      items(flatten(categories), key = { it.first.id }) { (c, depth) ->
        Row(Modifier.fillMaxWidth().clickable { action = Action.Menu(c) }.padding(start = (depth * 16).dp, top = 10.dp, bottom = 10.dp)) {
          Column(Modifier.weight(1f)) {
            Text(c.label, style = if (depth == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge)
            c.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
          }
          val n = totals[c.id] ?: 0
          if (n > 0) Text("$n", style = MaterialTheme.typography.bodySmall)
        }
        if (depth == 0) HorizontalDivider()
      }
    }
  }

  when (val a = action) {
    is Action.Menu -> AlertDialog(
      onDismissRequest = { action = null },
      title = { Text(pathOf(categories, a.c.id) ?: a.c.label) },
      text = {
        Column {
          val hasChildren = categories.any { it.parentId == a.c.id }
          for ((label, next) in listOfNotNull(
            "Renomear e descrever" to Action.Rename(a.c),
            "Nova subcategoria" to Action.Create(a.c),
            "Mover para…" to Action.Move(a.c),
            "Juntar com…" to Action.Merge(a.c),
            ("Dividir em partes…" to Action.Split(a.c)).takeIf { !hasChildren },
            ("Excluir…" to Action.Delete(a.c)).takeIf { !hasChildren },
          )) Text(label, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().clickable { action = next }.padding(vertical = 12.dp))
          if (hasChildren) Text("Para excluir ou dividir, primeiro mova ou exclua as subcategorias.", style = MaterialTheme.typography.bodySmall)
        }
      },
      confirmButton = {},
      dismissButton = { TextButton(onClick = { action = null }) { Text("Fechar") } },
    )
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
      "Renomear", listOf("Nome" to a.c.label, "Para que serve (uma linha)" to (a.c.description ?: "")),
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
    is Action.Delete -> if ((totals[a.c.id] ?: 0) == 0) AlertDialog(
      onDismissRequest = { action = null },
      title = { Text("Excluir “${a.c.label}”?") },
      text = { Text("Nenhum produto está nela.") },
      confirmButton = { TextButton(onClick = { action = null; edit { editor.deleteCategory(a.c.id, null); done() } }) { Text("Excluir") } },
      dismissButton = { TextButton(onClick = { action = null }) { Text("Cancelar") } },
    ) else CategoryPickerDialog(
      "Excluir “${a.c.label}”: para onde vão os ${totals[a.c.id]} produtos?", categories, exclude = setOf(a.c.id),
      current = a.c.parentId,
      onPick = { destination -> action = null; edit { editor.deleteCategory(a.c.id, destination); done() } },
      onDismiss = { action = null },
    )
    null -> {}
  }
}
