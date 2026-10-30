package io.github.sandroroque.getnf.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
      return@Column
    }

    if (account == null) SignedOutCard(go.signIn)
    else {
      val sync = syncState(app)
      AccountCard(account, sync, go.account)
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
