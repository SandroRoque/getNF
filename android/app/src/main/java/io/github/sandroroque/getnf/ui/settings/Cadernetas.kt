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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.launch

/** Roles as people read them (D104), without gendered nouns. */
internal fun roleLabel(role: String) = when (role) {
  "owner" -> "responsável"
  "manager" -> "administra"
  else -> "participa"
}

private fun pessoas(n: Int) = if (n == 1) "só você" else "$n pessoas"

/** The error a person can act on, not the server's wording. */
private fun reason(e: Throwable): String = e.message.orEmpty().let { m ->
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
 * Ajustes › Cadernetas (D104): the ones this person is in, which is open on this phone, and how to make or join one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CadernetasSection(app: GetNFApp, onOpen: (String) -> Unit) {
  val service = remember { LedgerService(app.accountSettings) }
  val scope = rememberCoroutineScope()
  var ledgers by remember { mutableStateOf<List<LedgerInfo>?>(null) }
  var status by remember { mutableStateOf<String?>(null) }
  var asking by remember { mutableStateOf<String?>(null) } // "new" or "join"
  var text by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  val open = app.accountSettings.ledgerId()

  LaunchedEffect(Unit) { ledgers = runCatching { service.mine() }.onFailure { status = reason(it) }.getOrNull() }

  SectionLabel("Cadernetas")
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
    Sub("A caderneta aberta neste celular não é mais sua. Abra outra para voltar a sincronizar; nada daqui foi apagado.")
  }
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Pill("Nova caderneta") { asking = "new"; text = "" }
    Pill("Entrar com um código") { asking = "join"; text = "" }
  }
  status?.let { Sub(it) }

  asking?.let { kind ->
    AlertDialog(
      onDismissRequest = { asking = null },
      title = { Text(if (kind == "new") "Nova caderneta" else "Entrar numa caderneta") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(if (kind == "new") "Um nome que todos reconheçam: Casa, Escritório…" else "O código que você recebeu de quem está nela.")
          OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (kind == "new") "Nome" else "Código") })
        }
      },
      confirmButton = {
        TextButton(enabled = !busy && text.isNotBlank(), onClick = {
          busy = true
          scope.launch {
            runCatching { if (kind == "new") service.create(text.trim()) else service.join(text.trim()) }
              .onSuccess { id -> asking = null; status = null; ledgers = runCatching { service.mine() }.getOrNull(); onOpen(id) }
              .onFailure { status = reason(it); asking = null }
            busy = false
          }
        }) { Text(if (kind == "new") "Criar" else "Entrar") }
      },
      dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancelar") } },
    )
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
    BackHeader("Ajustes", onBack) { if (manages) Pill("Renomear", ButtonKind.Ghost) { renaming = info?.name } }
    ScreenTitle(info?.name ?: "Caderneta")
    Sub(listOfNotNull(role?.let { "Você ${roleLabel(it)}" }, if (isOpen) "aberta neste celular" else null).joinToString(" · "))
    if (!isOpen && info != null) Pill("Abrir neste celular", ButtonKind.Solid) { openOnPhone(app, ledgerId) }

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
        val text = "Entre na caderneta \"${info?.name}\" do getNF com o código $code (Ajustes › Cadernetas › Entrar com um código)."
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
      }
    }

    SectionLabel("Suas notas")
    if (isOpen && others.isNotEmpty()) {
      Sub("Copie as notas que você escaneou (${scanned.size}) para outra caderneta sua. Esta continua com elas.")
      Pill("Copiar para outra caderneta", enabled = !busy && scanned.isNotEmpty()) { copying = true }
    } else if (!isOpen) {
      Sub("Para copiar notas desta caderneta, abra-a neste celular.")
    }

    SectionLabel("Sair")
    Sub("Ao sair, as fotos que você enviou saem com você. As notas, as páginas e o catálogo ficam com a caderneta.")
    Pill("Sair da caderneta", enabled = !busy && info != null) { leaving = true }
    status?.let { Sub(it) }
  }

  person?.let { m ->
    AlertDialog(
      onDismissRequest = { person = null },
      title = { Text(m.name.ifBlank { "Sem nome" }) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Hoje: ${roleLabel(m.role)}.")
          if (role == "owner") FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (r in listOf("member", "manager", "owner").filter { it != m.role }) {
              Pill(roleLabel(r).replaceFirstChar { it.uppercase() }) { person = null; act { service.setRole(ledgerId, m.userId, r) } }
            }
          }
          Text("Tirar da caderneta: as fotos que essa pessoa enviou saem; as notas ficam.")
        }
      },
      confirmButton = { TextButton(onClick = { person = null; act("Removida.") { service.remove(ledgerId, m.userId) } }) { Text("Tirar da caderneta") } },
      dismissButton = { TextButton(onClick = { person = null }) { Text("Cancelar") } },
    )
  }

  renaming?.let { current ->
    var name by remember { mutableStateOf(current) }
    AlertDialog(
      onDismissRequest = { renaming = null },
      title = { Text("Renomear") },
      text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
      confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { renaming = null; act { service.rename(ledgerId, name.trim()) } }) { Text("Salvar") } },
      dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancelar") } },
    )
  }

  if (copying) CopyDialog(others, onDismiss = { copying = false }) { target ->
    copying = false
    act("Copiadas para ${target.name}. Aparecem lá quando você abrir essa caderneta.") {
      ReceiptCopier(app, app.accountSettings).copy(app.receiptStore, scanned, target.id)
    }
  }

  if (leaving) {
    AlertDialog(
      onDismissRequest = { leaving = false },
      title = { Text("Sair de ${info?.name}?") },
      text = {
        Text(
          "As fotos que você enviou saem com você; as notas, as páginas e o catálogo ficam com quem continua." +
            (if (isOpen && others.isNotEmpty() && scanned.isNotEmpty()) "\n\nQuer levar uma cópia das ${scanned.size} notas que você escaneou? Use \"Copiar para outra caderneta\" antes de sair." else "") +
            (if (isOpen) "\n\nNeste celular, as notas desta caderneta continuam guardadas; o app abre outra caderneta sua." else ""),
        )
      },
      confirmButton = {
        TextButton(onClick = {
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
        }) { Text("Sair") }
      },
      dismissButton = { TextButton(onClick = { leaving = false }) { Text("Ficar") } },
    )
  }
}

@Composable
private fun CopyDialog(targets: List<LedgerInfo>, onDismiss: () -> Unit, onPick: (LedgerInfo) -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Copiar para…") },
    text = { Column { for (t in targets) AmountRow(t.name, null, subtitle = roleLabel(t.role), onClick = { onPick(t) }) } },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
  )
}
