package io.github.sandroroque.getnf.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.CurrentNetwork
import io.github.sandroroque.getnf.data.Decision
import io.github.sandroroque.getnf.data.decisions
import io.github.sandroroque.getnf.sync.SyncWorker
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.Format
import io.github.sandroroque.getnf.ui.calmo.ButtonKind
import io.github.sandroroque.getnf.ui.calmo.DecisionCard
import io.github.sandroroque.getnf.ui.calmo.EmptyState
import io.github.sandroroque.getnf.ui.calmo.Pill
import io.github.sandroroque.getnf.ui.calmo.Sub
import io.github.sandroroque.getnf.ui.calmo.TabHeader
import io.github.sandroroque.getnf.ui.catalog.CategoryPickerDialog
import io.github.sandroroque.getnf.ui.catalog.rememberEdit
import io.github.sandroroque.getnf.ui.page.budgetText
import io.github.sandroroque.getnf.ui.page.rememberBudgetCheck
import kotlinx.coroutines.delay

/**
 * Para você (D81): every decision only the user can make, one card each, the most money first. A card's answers are
 * its buttons; "Outra" opens the category list. Settled cards leave the queue at once.
 */
@Composable
fun ParaVoceScreen(onOpenPage: (String) -> Unit, onOpenProduct: (String) -> Unit, onOpenReceipt: (String) -> Unit, onCategories: () -> Unit) {
  val context = LocalContext.current
  val app = context.applicationContext as GetNFApp
  val products by remember { app.database.edit().products() }.collectAsStateWithLifecycle(emptyList())
  val categories by remember { app.database.edit().categories() }.collectAsStateWithLifecycle(emptyList())
  val receipts by remember { app.database.lists().receipts() }.collectAsStateWithLifecycle(emptyList())
  val links by remember { app.database.edit().linkSuggestions() }.collectAsStateWithLifecycle(emptyList())
  val sizes by remember { app.database.edit().sizeSuggestions() }.collectAsStateWithLifecycle(emptyList())
  val checks by remember { app.database.lists().photoDiscountChecks() }.collectAsStateWithLifecycle(emptyList())
  val queue = remember(receipts, products, categories, links, sizes, checks) { decisions(receipts, products, categories, links, sizes, checks) }
  val byId = remember(categories) { categories.associateBy { it.id } }
  val edit = rememberEdit()
  val editor = app.catalogEditor
  val done = { SyncWorker.enqueue(context) }
  var picking by remember { mutableStateOf<Pair<String, String?>?>(null) } // product id, current category
  val budgetCheck = rememberBudgetCheck()
  var budget by remember { mutableStateOf("") }
  LaunchedEffect(Unit) {
    while (true) {
      val network = CurrentNetwork.of(context)
      budget = budgetText(network, app.lookupBudget.status(network.key))
      delay(30_000)
    }
  }
  fun label(id: String?) = id?.let { byId[it]?.label } ?: "?"

  LazyColumn(
    Modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item { TabHeader("Para você") { if (queue.isNotEmpty()) Text("${queue.size}", style = MaterialTheme.typography.bodyMedium, color = Calmo.mute) } }
    if (queue.isEmpty()) item {
      EmptyState("Nada para decidir. Notas novas e sugestões do revisor aparecem aqui quando precisarem de você.")
    }
    items(queue, key = { it.key }) { d ->
      when (d) {
        is Decision.FetchPage -> DecisionCard(
          title = "${d.receipt.title} sem página da SEFAZ", amount = null,
          why = listOfNotNull(d.receipt.storeName, budget.ifEmpty { null }).joinToString(" · "),
        ) {
          Pill("Buscar agora", ButtonKind.Solid) { budgetCheck.request(onFetch = { onOpenPage(d.receipt.chave) }, onLater = {}) }
        }
        is Decision.PickCategory -> {
          val p = d.product
          val suggestion = p.suggestionCategoryId
          DecisionCard(
            title = p.title, amount = Format.brl(p.spentCents), onOpen = { onOpenProduct(p.productId) },
            why = if (suggestion != null) "Qual categoria? A sugestão tem ${percent(p.suggestionConfidence)} de confiança." else "Ainda sem categoria.",
          ) {
            if (suggestion != null) Pill(label(suggestion), ButtonKind.Solid, mark = who(p.suggestionModel)) {
              edit { editor.setCategory(p.productId, suggestion); done() }
            }
            Pill(if (suggestion != null) "Outra" else "Escolher categoria", if (suggestion != null) ButtonKind.Ghost else ButtonKind.Solid) {
              picking = p.productId to null
            }
          }
        }
        is Decision.PickPart -> {
          val p = d.product
          DecisionCard(title = p.title, amount = Format.brl(p.spentCents), why = "${label(p.categoryId)} foi dividida. Qual parte?",
            onOpen = { onOpenProduct(p.productId) }) {
            for (part in d.parts) Pill(part.label, ButtonKind.Outline) { edit { editor.setCategory(p.productId, part.id); done() } }
            Pill("Outra", ButtonKind.Ghost) { picking = p.productId to p.categoryId }
          }
        }
        is Decision.Dispute -> {
          val p = d.product
          DecisionCard(title = p.title, amount = Format.brl(p.spentCents), why = "${who(p.categorySource)} e o LLM discordam.",
            onOpen = { onOpenProduct(p.productId) }) {
            Pill(label(p.categoryId), ButtonKind.Solid, mark = who(p.categorySource)) { edit { editor.confirm(p.productId); done() } }
            Pill(label(p.suggestionCategoryId), ButtonKind.Outline, mark = "LLM") {
              edit { editor.setCategory(p.productId, p.suggestionCategoryId); done() }
            }
            Pill("Outra", ButtonKind.Ghost) { picking = p.productId to p.categoryId }
          }
        }
        is Decision.SameProduct -> {
          val l = d.link
          DecisionCard(title = "Mesmo produto?", amount = null, why = l.reason?.let { "O LLM: $it" } ?: "O LLM acha que são o mesmo item.",
            extra = {
              Column {
                Row(Modifier.fillMaxWidth()) { Text(l.itemDescription, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Calmo.fg); Sub(l.itemStore ?: "") }
                Row(Modifier.fillMaxWidth()) { Text(l.productName ?: l.productDescription ?: "?", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Calmo.fg); Sub(l.productStore ?: "") }
              }
            }) {
            Pill("É o mesmo", ButtonKind.Solid) { edit { editor.acceptLink(l.id); done() } }
            Pill("Não é", ButtonKind.Ghost) { edit { editor.refuseLink(l.id); done() } }
          }
        }
        is Decision.Size -> {
          val z = d.size
          val size = sizeText(z.sizeE6, z.unit)
          DecisionCard(title = z.name ?: z.description ?: "?", amount = null,
            why = "Tamanho lido pelo LLM: $size. Com ele, o preço por kg entra no índice.", onOpen = { onOpenProduct(z.productId) }) {
            Pill(size, ButtonKind.Solid) { edit { editor.acceptSize(z.productId); done() } }
            Pill("Outro", ButtonKind.Ghost) { onOpenProduct(z.productId) }
          }
        }
        is Decision.CheckPhotoDiscounts -> {
          val c = d.check
          DecisionCard(title = "Descontos lidos das fotos", amount = Format.brl(c.cents),
            why = "${c.storeName ?: "Nota"}, ${c.issuedAt?.let(Format::date) ?: ""}: ${c.lines} desconto(s). Confira com a nota.") {
            Pill("Conferir", ButtonKind.Solid) { onOpenReceipt(c.chave) }
          }
        }
        is Decision.Description -> {
          val c = d.category
          DecisionCard(title = "Descrição de ${c.label}", amount = null, why = "Sugerida pelo LLM: ${c.descriptionSuggestion}") {
            Pill("Usar", ButtonKind.Solid) { edit { editor.acceptDescription(c.id); done() } }
            Pill("Editar", ButtonKind.Ghost, onClick = onCategories)
          }
        }
      }
    }
  }

  picking?.let { (productId, current) ->
    CategoryPickerDialog(
      title = products.firstOrNull { it.productId == productId }?.title ?: "Categoria", categories = categories, current = current,
      onPick = { id -> picking = null; edit { editor.setCategory(productId, id); done() } }, onDismiss = { picking = null },
    )
  }
  budgetCheck.Dialog()
}

private fun who(source: String?) = when {
  source == null -> "?"
  source.startsWith("jev") -> "Jev"
  source.startsWith("llm") -> "LLM"
  source == "import" -> "Antes"
  source == "user" -> "Você"
  else -> source
}

private fun percent(x: Double?) = x?.let { "${Math.round(it * 100)}%" } ?: "pouca"

/** 300000 kg-millionths → "300 g"; 1250000 → "1,25 kg". */
internal fun sizeText(e6: Long, unit: String): String = when {
  e6 < 1_000_000 && unit == "kg" -> "${e6 / 1000} g"
  e6 < 1_000_000 && unit == "l" -> "${e6 / 1000} ml"
  else -> "${Format.e4(e6 / 100)} $unit"
}
