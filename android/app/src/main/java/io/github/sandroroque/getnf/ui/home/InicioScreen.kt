package io.github.sandroroque.getnf.ui.home

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import io.github.sandroroque.getnf.ui.calmo.ButtonKind
import io.github.sandroroque.getnf.ui.calmo.Wide
import io.github.sandroroque.getnf.ui.calmo.LeadCard
import io.github.sandroroque.getnf.data.LocalCadernetas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.CategoryNode
import io.github.sandroroque.getnf.data.Decision
import io.github.sandroroque.getnf.data.Ipci
import io.github.sandroroque.getnf.data.Spending
import io.github.sandroroque.getnf.data.decisions
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.Format
import io.github.sandroroque.getnf.ui.calmo.AccountBadge
import io.github.sandroroque.getnf.ui.calmo.AmountRow
import io.github.sandroroque.getnf.ui.calmo.BigNumber
import io.github.sandroroque.getnf.ui.calmo.DecisionCard
import io.github.sandroroque.getnf.ui.calmo.EmptyState
import io.github.sandroroque.getnf.ui.calmo.MidNumber
import io.github.sandroroque.getnf.ui.calmo.SectionLabel
import io.github.sandroroque.getnf.ui.calmo.Sub
import io.github.sandroroque.getnf.ui.catalog.flatten
import io.github.sandroroque.getnf.ui.onboarding.FirstStepsCard
import io.github.sandroroque.getnf.ui.onboarding.finished
import io.github.sandroroque.getnf.ui.onboarding.firstSteps
import io.github.sandroroque.getnf.ui.spending.label

/**
 * Início (D81): how this month is going. The month's total, its biggest categories, what's waiting for the user,
 * personal inflation, and the last receipts; each opens its full view.
 */
@Composable
fun InicioScreen(
  onSpending: () -> Unit, onDecisions: () -> Unit, onIpci: () -> Unit, onReceipt: (String) -> Unit, onSettings: () -> Unit,
  onScan: () -> Unit = {}, onDemo: () -> Unit = {}, onSignIn: () -> Unit = onSettings, onImport: () -> Unit = {},
) {
  val app = LocalContext.current.applicationContext as GetNFApp
  val lines by remember { app.database.spending().lines() }.collectAsStateWithLifecycle(null)
  val categories by remember { app.database.edit().categories() }.collectAsStateWithLifecycle(emptyList())
  val products by remember { app.database.edit().products() }.collectAsStateWithLifecycle(emptyList())
  val receipts by remember { app.database.lists().receipts() }.collectAsStateWithLifecycle(emptyList())
  val links by remember { app.database.edit().linkSuggestions() }.collectAsStateWithLifecycle(emptyList())
  val sizes by remember { app.database.edit().sizeSuggestions() }.collectAsStateWithLifecycle(emptyList())
  val checks by remember { app.database.lists().photoDiscountChecks() }.collectAsStateWithLifecycle(emptyList())
  val soldBy by remember { app.database.edit().soldBySuggestions() }.collectAsStateWithLifecycle(emptyList())
  val account = remember { app.accountSettings.account() }
  val parents = remember(categories) { categories.associate { it.id to it.parentId } }
  val spending = remember(lines, parents) { lines?.let { Spending(it, parents) } }
  val queue = remember(receipts, products, categories, links, sizes, checks, soldBy) {
    decisions(receipts, products, categories, links, sizes, checks, soldBy)
  }
  val ipci = remember(lines) { lines?.let { Ipci.compute(it) }?.lastOrNull { it.reading != null } }
  val colorOf = rememberCategoryColor(categories)
  // The first steps (D115): not in the demo, and gone once done or hidden; once done they stay gone, also in a new
  // caderneta (D126).
  var stepsHidden by remember {
    mutableStateOf(app.accountSettings.demo || app.onboarding.firstStepsHidden || app.onboarding.firstStepsDone)
  }
  val steps = remember(receipts, account) {
    firstSteps(receipts, account != null, onScan = onScan, onFetch = onDecisions, onAccount = onSignIn)
  }
  LaunchedEffect(steps) { if (steps.finished()) app.onboarding.firstStepsDone = true }
  // An empty caderneta while others on this phone hold receipts scanned here: offer to bring them (D126).
  var elsewhere by remember { mutableIntStateOf(0) }
  LaunchedEffect(receipts.isEmpty()) {
    elsewhere = if (receipts.isNotEmpty() || app.accountSettings.demo) 0 else runCatching {
      LocalCadernetas(app, app.accountSettings).sources(app.accountSettings.ledgerId()).sumOf { it.receipts.size }
    }.getOrDefault(0)
  }

  // The app closed on its own last time (D140): offered once, sent only by the person.
  var crash by remember { mutableStateOf<io.github.sandroroque.getnf.data.CrashLog.Report?>(null) }
  LaunchedEffect(Unit) { crash = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.crashLog.pending() } }

  // The closed test (D161): asked once, in a build made for it.
  var askPhotos by remember { mutableStateOf(app.testContribution.shouldAsk && !app.accountSettings.demo) }
  if (askPhotos) TestPhotosDialog { answer ->
    app.testContribution.enabled = answer
    askPhotos = false
  }

  val month = spending?.periods?.lastOrNull()
  LazyColumn(
    Modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    item {
      Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Sub(month?.let { label(it, long = true).replaceFirstChar { c -> c.uppercase() } } ?: "Caderneta", Modifier.weight(1f))
        AccountBadge(account?.let { io.github.sandroroque.getnf.ui.settings.displayName(it) }?.take(1), onSettings)
      }
    }
    crash?.let { report ->
      item { CrashCard(report) { app.crashLog.dismiss(report); crash = null } }
    }
    if (spending == null) return@LazyColumn
    val showSteps = !stepsHidden && !steps.finished() && elsewhere == 0
    if (month == null) {
      if (elsewhere > 0) item {
        LeadCard {
          Text("Esta caderneta está vazia", style = MaterialTheme.typography.titleSmall, color = Calmo.fg)
          Sub("Você tem ${Format.count(elsewhere, "nota escaneada", "notas escaneadas")} em outra caderneta deste celular. " +
            "Traga as que quiser para esta; a outra continua com elas.")
          Wide("Trazer notas", ButtonKind.Solid, onClick = onImport)
        }
      }
      else if (!showSteps) item { EmptyState("Nenhuma nota ainda. Toque no botão do meio para escanear o QR Code de uma nota fiscal.") }
    } else {
      val previous = spending.periods.dropLast(1).lastOrNull()
      item {
        Column(Modifier.clickable(onClick = onSpending)) {
          BigNumber(Format.brl(spending.total(month)))
          Sub("em ${Format.count(spending.linesIn(month).map { it.chave }.distinct().size, "nota", "notas")}" +
            (previous?.let { " · ${label(it, long = true)} foi ${Format.brl(spending.total(it))}" } ?: ""))
        }
      }
      item {
        Column {
          for (s in spending.slices(month, null).take(4)) {
            AmountRow(
              title = categories.firstOrNull { it.id == s.categoryId }?.label ?: "Sem categoria",
              amount = Format.brl(s.netCents), dot = colorOf(s.categoryId), onClick = onSpending,
            )
          }
        }
      }
    }
    if (showSteps) item {
      FirstStepsCard(steps, onDemo = onDemo.takeIf { receipts.isEmpty() }) { app.onboarding.firstStepsHidden = true; stepsHidden = true }
    }
    if (receipts.isNotEmpty() || queue.isNotEmpty()) item { QueueCard(queue, onDecisions) }
    if (ipci != null) item {
      Row(Modifier.fillMaxWidth().clickable(onClick = onIpci).padding(vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
        Sub("Inflação pessoal, ${label(ipci.period, long = true)}", Modifier.weight(1f))
        MidNumber("%+.1f%%".format(ipci.reading).replace('.', ','), color = if (ipci.reading!! > 0) Calmo.up else Calmo.down)
      }
    }
    // Before its first reading, where it will appear says what it needs (D116).
    else if (month != null) item {
      Column(Modifier.fillMaxWidth().clickable(onClick = onIpci).padding(vertical = 4.dp)) {
        Text("Inflação pessoal", style = MaterialTheme.typography.bodyMedium, color = Calmo.fg)
        Sub(IPCI_HINT)
      }
    }
    if (receipts.isNotEmpty()) {
      item { SectionLabel("Últimas notas") }
      items(receipts.take(3), key = { it.chave }) { r ->
        AmountRow(
          title = r.title,
          subtitle = if (r.hasPage) Format.date(r.issuedAt ?: r.capturedAt) + " · ${r.lines} itens"
            else listOfNotNull(r.storeName, "busque a página").joinToString(" · "),
          amount = r.paidCents?.let(Format::brl), onClick = { onReceipt(r.chave) },
        )
      }
    }
  }
}

/** What the IPCI needs before its first reading (D116). */
const val IPCI_HINT = "Aparece no mês em que você comprar de novo produtos de meses anteriores. " +
  "Notas antigas que ainda estão na carteira também contam."

/** "5 decisões para você", with what kinds; or that there's nothing to decide. */
@Composable
private fun QueueCard(queue: List<Decision>, onOpen: () -> Unit) {
  if (queue.isEmpty()) {
    Sub("Nada para decidir agora. ✓")
    return
  }
  val kinds = listOfNotNull(
    queue.count { it is Decision.FetchPage }.takeIf { it > 0 }?.let { "$it ${if (it == 1) "página" else "páginas"} para buscar" },
    queue.count { it is Decision.PickCategory || it is Decision.PickPart }.takeIf { it > 0 }?.let { "$it para escolher categoria" },
    queue.count { it is Decision.Dispute }.takeIf { it > 0 }?.let { "$it em dúvida" },
    queue.count { it is Decision.SameProduct || it is Decision.Size || it is Decision.SoldBy || it is Decision.Description || it is Decision.CheckPhotoDiscounts }
      .takeIf { it > 0 }?.let { "$it ${if (it == 1) "sugestão" else "sugestões"}" },
  )
  DecisionCard(
    title = "${queue.size} ${if (queue.size == 1) "decisão" else "decisões"} para você", amount = null,
    why = kinds.joinToString(" · "), onOpen = onOpen,
  ) {}
}

/** A category's colour: its top-level ancestor's, by tree order (the same everywhere in the app). */
@Composable
fun rememberCategoryColor(categories: List<CategoryNode>): (String?) -> Color {
  val c = Calmo
  return remember(categories, c) {
    val byId = categories.associateBy { it.id }
    val tops = flatten(categories).filter { it.second == 0 }.map { it.first.id }
    val f: (String?) -> Color = { id ->
      var top = id?.let(byId::get)
      while (top?.parentId != null && byId[top.parentId] != null) top = byId[top.parentId]
      tops.indexOf(top?.id).let { i -> if (i < 0) c.none else c.categories[i % c.categories.size] }
    }
    f
  }
}

/** The app's last crash (D140): what happened, and a way to send it to the developer's inbox (D152). */
@Composable
private fun CrashCard(report: io.github.sandroroque.getnf.data.CrashLog.Report, onDone: () -> Unit) {
  val context = LocalContext.current
  val app = context.applicationContext as GetNFApp
  LeadCard {
    Text("O app fechou sozinho", style = MaterialTheme.typography.titleSmall, color = Calmo.fg)
    Sub("Foi em ${Format.dateTime(report.at)}. O relatório mostra em que parte do app o erro aconteceu; mandar para " +
      "quem faz o app ajuda a corrigir.")
    Wide("Enviar o relatório", ButtonKind.Solid) {
      app.sendFeedback(io.github.sandroroque.getnf.data.FeedbackOutbox.Kind.Crash, report.text)
      android.widget.Toast.makeText(context, "Relatório enviado. Obrigado!", android.widget.Toast.LENGTH_SHORT).show()
      onDone()
    }
    Wide("Agora não", ButtonKind.Ghost, onClick = onDone)
  }
}

/** The closed test's question (D161): every capture's photos and pages for the developer, until the test ends. */
@Composable
private fun TestPhotosDialog(onAnswer: (Boolean) -> Unit) = androidx.compose.material3.AlertDialog(
  onDismissRequest = {},
  title = { Text("Ajude a melhorar a leitura das notas") },
  text = {
    Text("Só durante o teste fechado, a Caderneta pode enviar ao desenvolvedor as fotos de cada captura e a página da " +
      "SEFAZ de cada nota. As fotos vão em preto e branco, com os dados do cartão, o CPF e o operador cobertos; a " +
      "página, sem quem comprou. Vão só pelo Wi-Fi, com um código aleatório deste celular, sem a sua conta.\n\n" +
      "É temporário: na versão pública, as fotos voltam a ficar só no seu celular. Dá para desligar em Ajustes quando " +
      "quiser, e o que já foi enviado é apagado.")
  },
  confirmButton = { androidx.compose.material3.TextButton(onClick = { onAnswer(true) }) { Text("Sim, enviar") } },
  dismissButton = { androidx.compose.material3.TextButton(onClick = { onAnswer(false) }) { Text("Agora não") } },
)
