package io.github.sandroroque.getnf.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.RestartActivity
import io.github.sandroroque.getnf.data.ReceiptCopier
import io.github.sandroroque.getnf.sync.LedgerInfo
import io.github.sandroroque.getnf.sync.LedgerService
import io.github.sandroroque.getnf.sync.Member
import io.github.sandroroque.getnf.ui.calmo.AmountRow
import io.github.sandroroque.getnf.ui.calmo.BackHeader
import io.github.sandroroque.getnf.ui.calmo.ButtonKind
import io.github.sandroroque.getnf.ui.calmo.EmptyState
import io.github.sandroroque.getnf.ui.calmo.MidNumber
import io.github.sandroroque.getnf.ui.calmo.Pill
import io.github.sandroroque.getnf.ui.calmo.ScreenTitle
import io.github.sandroroque.getnf.ui.calmo.SectionLabel
import io.github.sandroroque.getnf.ui.calmo.Segments
import io.github.sandroroque.getnf.ui.calmo.Sub
import io.github.sandroroque.getnf.ui.calmo.Field
import io.github.sandroroque.getnf.ui.calmo.Notice
import io.github.sandroroque.getnf.ui.calmo.Wide
import io.github.sandroroque.getnf.ui.catalog.CalmoSheet
import io.github.sandroroque.getnf.theme.Calmo
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.launch

/** Roles as people read them (D104), without gendered nouns. */
internal fun roleLabel(role: String) = when (role) {
  "owner" -> "responsável"
  "manager" -> "administra"
  else -> "participa"
}

private fun pessoas(n: Int) = if (n == 1) "só você" else "$n pessoas"

/** The error a person can act on, not the server's wording. */
internal fun reason(e: Throwable): String = e.message.orEmpty().let { m ->
  when {
    "invite not valid" in m -> "Esse código não vale: já foi usado, venceu ou foi digitado errado."
    "hand the caderneta over" in m || "hand over your cadernetas" in m -> "Passe a caderneta para outra pessoa antes (torne alguém responsável)."
    "needs an owner" in m -> "A caderneta precisa de alguém responsável."
    "not allowed" in m || "only" in m || "42501" in m -> "Você não tem permissão para isso nesta caderneta."
    e is java.io.IOException -> "Sem conexão com o servidor. Tente de novo."
    else -> "Não deu certo: $m"
  }
}

/** Opens [ledger] on this phone: its own data, so the app restarts (D104). */
private fun openOnPhone(app: GetNFApp, ledger: String): Nothing {
  app.accountSettings.openLedger(ledger)
  RestartActivity.restart(app)
}

/**
 * Ajustes › Cadernetas (D104), a screen of its own since D123: the ones this person is in, which is open on this
 * phone, and how to make or join one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CadernetasScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
  val app = LocalContext.current.applicationContext as GetNFApp
  val service = remember { LedgerService(app.accountSettings) }
  val scope = rememberCoroutineScope()
  var ledgers by remember { mutableStateOf<List<LedgerInfo>?>(null) }
  var status by remember { mutableStateOf<String?>(null) }
  var asking by remember { mutableStateOf<String?>(null) } // "new" or "join"
  var busy by remember { mutableStateOf(false) }
  val open = app.accountSettings.ledgerId()

  LaunchedEffect(Unit) { ledgers = runCatching { service.mine() }.onFailure { status = reason(it) }.getOrNull() }

  Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    BackHeader("Ajustes", onBack)
    ScreenTitle("Cadernetas")
    Sub("Cada caderneta tem suas notas e seu catálogo. Todos que estão nela veem tudo e podem escanear.")
    val list = ledgers
    when {
      list == null && status == null -> Sub("Carregando…")
      list != null && list.isEmpty() -> EmptyState("Você não está em nenhuma caderneta. Crie uma ou entre com um código.")
      list != null -> Column {
        for (l in list.sortedByDescending { it.id == open }) {
          AmountRow(l.name, null, subtitle = "${roleLabel(l.role)} · ${pessoas(l.members)}",
            trailingMark = if (l.id == open) "aberta" else null, onClick = { onOpen(l.id) })
        }
      }
    }
    if (list != null && open != null && list.none { it.id == open }) {
      Notice("A caderneta aberta neste celular não é mais sua. Abra outra para voltar a sincronizar; nada daqui foi apagado.")
    }
    Spacer(Modifier.height(4.dp))
    Wide("Nova caderneta", ButtonKind.Outline) { asking = "new" }
    Wide("Entrar com um código", ButtonKind.Outline) { asking = "join" }
    status?.let { Notice(it, error = true) }
  }

  asking?.let { kind ->
    var text by remember(kind) { mutableStateOf("") }
    CalmoSheet(if (kind == "new") "Nova caderneta" else "Entrar numa caderneta", onDismiss = { asking = null }) {
      Sub(if (kind == "new") "Um nome que todos reconheçam: Casa, Escritório…" else "O código que você recebeu de quem está nela.")
      Field(text, { text = it }, if (kind == "new") "Nome" else "Código", last = true)
      Wide(if (kind == "new") "Criar" else "Entrar", ButtonKind.Solid, enabled = !busy && text.isNotBlank()) {
        busy = true
        scope.launch {
          runCatching { if (kind == "new") service.create(text.trim()) else service.join(text.trim()) }
            .onSuccess { id -> asking = null; status = null; ledgers = runCatching { service.mine() }.getOrNull(); onOpen(id) }
            .onFailure { status = reason(it); asking = null }
          busy = false
        }
      }
    }
  }
}

/** One caderneta: who is in it, inviting, roles, copying receipts out, and leaving (D104). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CadernetaScreen(ledgerId: String, onBack: () -> Unit) {
  val context = LocalContext.current
  val app = context.applicationContext as GetNFApp
  val service = remember { LedgerService(app.accountSettings) }
  val scope = rememberCoroutineScope()
  var info by remember { mutableStateOf<LedgerInfo?>(null) }
  var others by remember { mutableStateOf<List<LedgerInfo>>(emptyList()) }
  var members by remember { mutableStateOf<List<Member>?>(null) }
  var status by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  var invite by remember { mutableStateOf<String?>(null) }
  var inviteRole by remember { mutableIntStateOf(0) }
  var person by remember { mutableStateOf<Member?>(null) }
  var renaming by remember { mutableStateOf<String?>(null) }
  var copying by remember { mutableStateOf(false) }
  var leaving by remember { mutableStateOf(false) }
  val isOpen = app.accountSettings.ledgerId() == ledgerId
  val receipts by app.receiptStore.receipts.collectAsStateWithLifecycle()
  val scanned = receipts.filter { it.scannedHere }

  suspend fun reload() {
    runCatching {
      val all = service.mine()
      info = all.find { it.id == ledgerId }; others = all.filter { it.id != ledgerId }
      members = service.members(ledgerId)
    }.onFailure { status = reason(it) }
  }
  fun act(done: String? = null, block: suspend () -> Unit) {
    busy = true
    scope.launch { runCatching { block() }.onSuccess { status = done; reload() }.onFailure { status = reason(it) }; busy = false }
  }
  LaunchedEffect(ledgerId) { reload() }

  val role = info?.role
  val manages = role == "owner" || role == "manager"
  Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    BackHeader("Voltar", onBack) { if (manages) Pill("Renomear", ButtonKind.Ghost) { renaming = info?.name } }
    ScreenTitle(info?.name ?: "Caderneta")
    Sub(listOfNotNull(role?.let { if (it == "owner") "Você é responsável" else "Você ${roleLabel(it)}" },
      if (isOpen) "aberta neste celular" else null).joinToString(" · "))
    if (!isOpen && info != null) Wide("Abrir neste celular", ButtonKind.Solid) { openOnPhone(app, ledgerId) }

    Spacer(Modifier.height(4.dp))
    SectionLabel("Pessoas")
    val list = members
    if (list == null) Sub(if (status == null) "Carregando…" else "")
    else Column {
      for (m in list) {
        val canChange = !m.you && (role == "owner" || (role == "manager" && m.role == "member"))
        AmountRow(m.name.ifBlank { "Sem nome" }, null, subtitle = roleLabel(m.role), trailingMark = if (m.you) "você" else null,
          onClick = if (canChange) ({ person = m }) else null)
      }
    }
    if (manages) {
      if (role == "owner") Segments(listOf("Convidar para participar", "Para administrar"), inviteRole) { inviteRole = it }
      Pill("Convidar alguém", enabled = !busy) {
        act { invite = service.invite(ledgerId, if (inviteRole == 1 && role == "owner") "manager" else "member") }
      }
    }
    invite?.let { code ->
      MidNumber(code)
      Sub("Vale uma vez, por 7 dias. Quem receber entra em Ajustes › Cadernetas › Entrar com um código.")
      Pill("Enviar o código") {
        val text = "Entre na caderneta \"${info?.name}\" do app Caderneta com o código $code (Ajustes › Cadernetas › Entrar com um código)."
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
      }
    }

    if (isOpen && others.isNotEmpty()) {
      Spacer(Modifier.height(4.dp))
      SectionLabel("Suas notas")
      Sub("Copie as notas que você escaneou (${scanned.size}) para outra caderneta sua. Esta continua com elas.")
      Pill("Copiar para outra caderneta", enabled = !busy && scanned.isNotEmpty()) { copying = true }
    } else if (!isOpen) {
      Spacer(Modifier.height(4.dp))
      SectionLabel("Suas notas")
      Sub("Para copiar notas desta caderneta, abra-a neste celular.")
    }

    Spacer(Modifier.height(4.dp))
    SectionLabel("Sair")
    Sub("Ao sair, as fotos que você enviou saem com você. As notas, as páginas e o catálogo ficam com a caderneta.")
    Pill("Sair da caderneta", ButtonKind.Ghost, enabled = !busy && info != null) { leaving = true }
    status?.let { Notice(it) }
  }

  person?.let { m ->
    CalmoSheet(m.name.ifBlank { "Sem nome" }, onDismiss = { person = null }) {
      Sub("Hoje: ${roleLabel(m.role)}.")
      if (role == "owner") for (r in listOf("member", "manager", "owner").filter { it != m.role }) {
        Wide("Tornar: ${roleLabel(r)}", ButtonKind.Outline) { person = null; act { service.setRole(ledgerId, m.userId, r) } }
      }
      Spacer(Modifier.height(4.dp))
      Sub("Tirar da caderneta: as fotos que essa pessoa enviou saem; as notas ficam.")
      Wide("Tirar da caderneta", ButtonKind.Ghost) { person = null; act("Removida.") { service.remove(ledgerId, m.userId) } }
    }
  }

  renaming?.let { current ->
    var name by remember { mutableStateOf(current) }
    CalmoSheet("Renomear", onDismiss = { renaming = null }) {
      Field(name, { name = it }, "Nome", last = true)
      Wide("Salvar", ButtonKind.Solid, enabled = name.isNotBlank()) { renaming = null; act { service.rename(ledgerId, name.trim()) } }
    }
  }

  if (copying) CopySheet(others, onDismiss = { copying = false }) { target ->
    copying = false
    act("Copiadas para ${target.name}. Sobem para lá na próxima sincronização.") {
      ReceiptCopier(app, app.accountSettings).copy(app.receiptStore, scanned, target.id)
      io.github.sandroroque.getnf.sync.SyncWorker.enqueue(app)
    }
  }

  if (leaving) {
    CalmoSheet("Sair de ${info?.name}?", onDismiss = { leaving = false }) {
      val alone = (members?.size ?: 0) <= 1
      Text(
        if (alone) "Você é a única pessoa nela: a caderneta é apagada do servidor, com as notas, as páginas e o catálogo."
        else "As fotos que você enviou saem com você; as notas, as páginas e o catálogo ficam com quem continua.",
        style = MaterialTheme.typography.bodyMedium, color = Calmo.fg,
      )
      if (isOpen && others.isNotEmpty() && scanned.isNotEmpty())
        Sub("Quer levar uma cópia das ${scanned.size} notas que você escaneou? Use \"Copiar para outra caderneta\" antes de sair.")
      if (isOpen) Sub("Neste celular, as notas desta caderneta continuam guardadas; o app abre outra caderneta sua.")
      Wide("Sair", ButtonKind.Solid) {
        leaving = false; busy = true
        scope.launch {
          runCatching { service.leave(ledgerId) }
            .onSuccess {
              val next = others.firstOrNull()
              if (isOpen && next != null) openOnPhone(app, next.id) else onBack()
            }
            .onFailure { status = reason(it) }
          busy = false
        }
      }
      Wide("Ficar", ButtonKind.Ghost) { leaving = false }
    }
  }
}

@Composable
private fun CopySheet(targets: List<LedgerInfo>, onDismiss: () -> Unit, onPick: (LedgerInfo) -> Unit) {
  CalmoSheet("Copiar para…", onDismiss = onDismiss) {
    Column { for (t in targets) AmountRow(t.name, null, subtitle = roleLabel(t.role), onClick = { onPick(t) }) }
  }
}
