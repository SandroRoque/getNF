package io.github.sandroroque.getnf.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.sandroroque.getnf.BuildConfig
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.Account
import io.github.sandroroque.getnf.sync.LedgerInfo
import io.github.sandroroque.getnf.sync.LedgerService
import io.github.sandroroque.getnf.sync.SyncWorker
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.Format
import io.github.sandroroque.getnf.ui.calmo.AmountRow
import io.github.sandroroque.getnf.ui.calmo.Avatar
import io.github.sandroroque.getnf.ui.calmo.BackHeader
import io.github.sandroroque.getnf.ui.calmo.ButtonKind
import io.github.sandroroque.getnf.ui.calmo.Hairline
import io.github.sandroroque.getnf.ui.calmo.Icons
import io.github.sandroroque.getnf.ui.calmo.LeadCard
import io.github.sandroroque.getnf.ui.calmo.ScreenTitle
import io.github.sandroroque.getnf.ui.calmo.SectionLabel
import io.github.sandroroque.getnf.ui.calmo.Sub
import io.github.sandroroque.getnf.ui.calmo.SwitchRow
import io.github.sandroroque.getnf.ui.calmo.TextLink
import io.github.sandroroque.getnf.ui.calmo.Wide

/** Where Ajustes' rows lead (D123): each its own screen. */
class SettingsRoutes(
  val back: () -> Unit,
  val signIn: () -> Unit,
  val account: () -> Unit,
  val cadernetas: () -> Unit,
  val ledger: (String) -> Unit,
  val backup: () -> Unit,
  val developer: () -> Unit,
  val leaveDemo: () -> Unit,
)

/**
 * Ajustes as an index (D123): the account in a card at the top (what an account gives, or who is signed in and when
 * it last synced), then short rows grouped by what they're about, each opening its own screen.
 */
@Composable
fun SettingsScreen(go: SettingsRoutes) {
  val context = LocalContext.current
  val app = context.applicationContext as GetNFApp
  // Read again on every return here (plain remember): signing in and out happen on the screens this one opens.
  val account = remember { app.accountSettings.account() }
  Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    BackHeader("Voltar", go.back)
    ScreenTitle("Ajustes")
    // In the demo (D115) nothing here applies to made-up receipts: no account, no backups, no photos to send.
    if (app.accountSettings.demo) {
      LeadCard {
        Text("Você está vendo o exemplo", style = MaterialTheme.typography.titleSmall, color = Calmo.fg)
        Sub("Notas inventadas de uma casa em Florianópolis, de maio a outubro. Mexa à vontade; nada daqui é guardado nem " +
          "enviado. Conta, cópias de segurança e o resto dos ajustes ficam nas suas notas.")
        Wide("Sair do exemplo", ButtonKind.Solid, onClick = go.leaveDemo)
      }
      // Testers outside Santa Catarina see only the demo: they can still say what they think (D146).
      Column { FeedbackRow(app) }
      return@Column
    }

    if (account == null) SignedOutCard(go.signIn)
    else {
      val sync = syncState(app)
      AccountCard(account, sync, go.account)
      RefusedRows(app, sync)
      Group("Caderneta")
      // The first sync picks the caderneta to open: read again after each.
      LedgerRows(app, remember(sync.last) { app.accountSettings.ledgerId() }, go)
    }

    Group("Este celular")
    Column {
      AmountRow("Cópia de segurança", null, subtitle = "Guardar tudo num arquivo, ou restaurar de um", onClick = go.backup)
      ContributionRow(app, signedIn = account != null)
    }

    Group("Sobre")
    Column {
      FeedbackRow(app)
      AmountRow("Política de privacidade", null, subtitle = "caderneta.roqlab.com.br", onClick = {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://caderneta.roqlab.com.br/privacidade/")))
      })
      if (BuildConfig.DEBUG) AmountRow("Desenvolvimento", null, subtitle = "Servidor, revisor, testes de captura", onClick = go.developer)
    }
    Sub("Versão ${BuildConfig.VERSION_NAME}")
  }
}

@Composable
private fun Group(label: String) {
  Spacer(Modifier.height(4.dp))
  SectionLabel(label)
}

/** Signed out: what an account adds, and the one way in. */
@Composable
private fun SignedOutCard(onSignIn: () -> Unit) {
  LeadCard {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Avatar(null)
      Column {
        Text("Sem conta", style = MaterialTheme.typography.titleSmall, color = Calmo.fg)
        Sub("Suas notas ficam só neste celular.")
      }
    }
    Text("Com uma conta, os itens ganham categoria sozinhos, as notas ficam guardadas online e dá para dividir uma " +
      "caderneta com quem mora com você.", style = MaterialTheme.typography.bodyMedium, color = Calmo.fg)
    Wide("Entrar ou criar conta", ButtonKind.Solid, onClick = onSignIn)
  }
}

private class SyncState(val running: Boolean, val retrying: Boolean, val last: Long)

@Composable
private fun syncState(app: GetNFApp): SyncState {
  val infos by remember { WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(SyncWorker.NAME) }
    .collectAsStateWithLifecycle(emptyList())
  val last = remember(infos) { app.accountSettings.lastSync }
  return SyncState(
    running = infos.any { it.state == WorkInfo.State.RUNNING },
    retrying = infos.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 },
    last = last,
  )
}

/** Signed in: who, and how the sync is going; the card opens the account. */
@Composable
private fun AccountCard(account: Account, sync: SyncState, onOpen: () -> Unit) {
  val context = LocalContext.current
  val running = sync.running
  val retrying = sync.retrying
  val last = sync.last
  LeadCard(onClick = onOpen) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Avatar(displayName(account))
      Column(Modifier.weight(1f)) {
        Text(displayName(account), style = MaterialTheme.typography.titleSmall, color = Calmo.fg)
        Sub(account.email)
      }
      Icon(Icons.Chevron, null, Modifier.size(14.dp), tint = Calmo.mute)
    }
    Hairline()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(if (running || retrying) Icons.Sync else Icons.Check, null, Modifier.size(16.dp), tint = Calmo.mute)
      Sub(
        when {
          running -> "Sincronizando…"
          retrying -> "Sem conexão com o servidor; tenta de novo sozinho"
          last > 0 -> "Sincronizado ${ago(last)}"
          else -> "Ainda não sincronizou"
        },
        Modifier.weight(1f),
      )
      if (!running) TextLink("Sincronizar") { SyncWorker.enqueue(context) }
    }
  }
}

/** The name given at sign-up, else the e-mail's first part. */
internal fun displayName(account: Account) = account.name.ifBlank { account.email.substringBefore('@') }

private fun ago(millis: Long): String {
  val minutes = (System.currentTimeMillis() - millis) / 60_000
  return when {
    minutes < 1 -> "agora há pouco"
    minutes < 60 -> "há $minutes min"
    minutes < 24 * 60 -> "há ${minutes / 60} h"
    else -> "em ${Format.dateTime(millis)}"
  }
}

/** The caderneta open on this phone, and the way to the others (D104). */
@Composable
private fun LedgerRows(app: GetNFApp, open: String?, go: SettingsRoutes) {
  var mine by remember { mutableStateOf<List<LedgerInfo>?>(null) }
  LaunchedEffect(open) { if (open != null) mine = runCatching { LedgerService(app.accountSettings).mine() }.getOrNull() }
  val current = mine?.find { it.id == open }
  Column {
    when {
      open == null -> {}
      current != null -> AmountRow(current.name, null,
        subtitle = "aberta neste celular · ${if (current.members == 1) "só você" else "${current.members} pessoas"}",
        onClick = { go.ledger(open) })
      // Someone else's (another account signed in here, or this one left it): nothing syncs until another is opened.
      mine != null -> AmountRow("Nenhuma caderneta sua aberta", null,
        subtitle = "A deste celular não é desta conta. Abra uma em Cadernetas.", onClick = go.cadernetas)
      else -> AmountRow("Caderneta aberta", null, subtitle = "aberta neste celular", onClick = { go.ledger(open) })
    }
    AmountRow("Cadernetas", null, subtitle = "Todas as suas: criar, entrar com um código, trocar", onClick = go.cadernetas)
  }
}

/** Photos for improving the app (D103): off until the user turns it on; turning it off takes them back (D105). */
@Composable
private fun ContributionRow(app: GetNFApp, signedIn: Boolean) {
  val context = LocalContext.current
  var enabled by remember { mutableStateOf(app.photoContribution.enabled) }
  var withdrawing by remember { mutableStateOf(app.photoContribution.withdrawPending) }
  SwitchRow("Ajudar a melhorar o app", "Enviar fotos das notas que o app não leu bem, com os dados do cartão e o CPF cobertos.", enabled) {
    enabled = it
    app.photoContribution.enabled = it
    withdrawing = app.photoContribution.withdrawPending
    if (withdrawing) SyncWorker.enqueue(context)
  }
  if (withdrawing) Sub(
    if (signedIn) "As fotos já enviadas serão apagadas do servidor na próxima sincronização."
    else "As fotos já enviadas serão apagadas do servidor quando você entrar na conta de novo.",
    Modifier.padding(top = 8.dp),
  )
}

/**
 * Rows the server refused (D104, D145): they stay on this phone, unsynced, so the person sees it and can send them
 * again or send the developer what happened. Read again after every sync.
 */
@Composable
private fun RefusedRows(app: GetNFApp, sync: SyncState) {
  val context = LocalContext.current
  var rows by remember { mutableStateOf(emptyList<io.github.sandroroque.getnf.data.SyncParkedRow>()) }
  LaunchedEffect(sync.last, sync.running) { rows = app.database.sync().parked() }
  if (rows.isEmpty()) return
  LeadCard {
    Text("Não sincronizado", style = MaterialTheme.typography.titleSmall, color = Calmo.fg)
    Sub("O servidor recusou ${Format.count(rows.size, "alteração", "alterações")}. Elas continuam neste celular; o resto " +
      "sincroniza normalmente.")
    Column {
      for (r in rows.take(5).map(io.github.sandroroque.getnf.data.SyncProblems::describe))
        AmountRow(r.title, null, subtitle = "${Format.dateTime(r.at)} · ${r.error}")
      if (rows.size > 5) Sub("e mais ${rows.size - 5}")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      io.github.sandroroque.getnf.ui.calmo.Pill("Tentar de novo", ButtonKind.Solid) {
        kotlinx.coroutines.MainScope().launch { app.database.sync().unparkAll(); SyncWorker.enqueue(context) }
      }
      io.github.sandroroque.getnf.ui.calmo.Pill("Enviar relatório", ButtonKind.Outline) {
        app.sendFeedback(io.github.sandroroque.getnf.data.FeedbackOutbox.Kind.Refused,
          io.github.sandroroque.getnf.data.SyncProblems.report(app.crashLog.about, rows))
        android.widget.Toast.makeText(context, "Relatório enviado. Obrigado!", android.widget.Toast.LENGTH_SHORT).show()
      }
    }
  }
}

/**
 * "Enviar comentário" (D146): a sheet to write it in, sent to the developer's inbox with the version and phone (D152),
 * once there's a network. Another way stays one tap away: e-mail to [FEEDBACK_EMAIL] once there's a public address,
 * until then the share sheet, the person picks how.
 */
@Composable
private fun FeedbackRow(app: GetNFApp) {
  val context = LocalContext.current
  var writing by remember { mutableStateOf(false) }
  AmountRow("Enviar comentário", null, subtitle = "Um problema, uma ideia, uma dúvida", onClick = { writing = true })
  if (!writing) return
  var text by remember { mutableStateOf("") }
  io.github.sandroroque.getnf.ui.catalog.CalmoSheet("Enviar comentário", onDismiss = { writing = false }) {
    Sub("Um problema, uma ideia, uma dúvida. Vão junto a versão do app e o modelo do celular; seu nome e seu e-mail não.")
    androidx.compose.material3.OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 4, maxLines = 10,
      placeholder = { Text("O que aconteceu, ou o que você gostaria") })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      io.github.sandroroque.getnf.ui.calmo.Pill("Enviar", ButtonKind.Solid, enabled = text.isNotBlank()) {
        app.sendFeedback(io.github.sandroroque.getnf.data.FeedbackOutbox.Kind.Comment, text.trim())
        android.widget.Toast.makeText(context, "Enviado. Obrigado!", android.widget.Toast.LENGTH_SHORT).show()
        writing = false
      }
      io.github.sandroroque.getnf.ui.calmo.Pill("Cancelar", ButtonKind.Ghost) { writing = false }
    }
    TextLink(if (FEEDBACK_EMAIL != null) "Prefiro mandar por e-mail" else "Prefiro mandar por outro app") {
      context.startActivity(feedbackIntent(app.crashLog.about, text.trim()))
      writing = false
    }
  }
}

/** The public contact address for feedback, once chosen (privacy policy, Play listing); null: the share sheet. */
private val FEEDBACK_EMAIL: String? = null

internal fun feedbackIntent(about: String, text: String = ""): Intent {
  val subject = "Caderneta: comentário"
  val body = "$text\n\n\n---\n$about"
  return FEEDBACK_EMAIL?.let { to ->
    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
      .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body)
  } ?: Intent.createChooser(
    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body),
    "Enviar comentário",
  )
}

