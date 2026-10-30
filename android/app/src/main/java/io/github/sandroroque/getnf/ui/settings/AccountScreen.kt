package io.github.sandroroque.getnf.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.sync.LedgerService
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.calmo.BackHeader
import io.github.sandroroque.getnf.ui.calmo.ButtonKind
import io.github.sandroroque.getnf.ui.calmo.FactRow
import io.github.sandroroque.getnf.ui.calmo.Notice
import io.github.sandroroque.getnf.ui.calmo.Pill
import io.github.sandroroque.getnf.ui.calmo.ScreenTitle
import io.github.sandroroque.getnf.ui.calmo.SectionLabel
import io.github.sandroroque.getnf.ui.calmo.Sub
import io.github.sandroroque.getnf.ui.calmo.Wide
import io.github.sandroroque.getnf.ui.catalog.CalmoSheet
import io.github.sandroroque.getnf.sync.SupabaseClient
import io.github.sandroroque.getnf.sync.SupabaseException
import io.github.sandroroque.getnf.ui.calmo.Field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The signed-in account (D123): who, signing out, and, apart at the bottom, deleting the account. Both ask first in a
 * sheet; both leave everything on this phone.
 */
@Composable
fun AccountScreen(onBack: () -> Unit) {
  val app = LocalContext.current.applicationContext as GetNFApp
  val scope = rememberCoroutineScope()
  val account = remember { app.accountSettings.account() }
  var signingOut by remember { mutableStateOf(false) }
  var deleting by remember { mutableStateOf<List<String>?>(null) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    BackHeader("Ajustes", onBack)
    if (account == null) { LaunchedEffect(Unit) { onBack() }; return@Column }
    ScreenTitle(displayName(account))
    Column { FactRow("E-mail", account.email) }
    Sub("As notas e o catálogo da caderneta aberta sincronizam com o servidor sempre que algo muda.")
    Spacer(Modifier.height(4.dp))
    Wide("Sair da conta", ButtonKind.Outline, enabled = !busy) { signingOut = true }
    error?.let { Notice(it, error = true) }

    Spacer(Modifier.height(32.dp))
    SectionLabel("Apagar a conta")
    Sub("Sua conta sai do servidor e você sai de todas as cadernetas. Neste celular nada é apagado.")
    Pill(if (busy) "Conferindo…" else "Apagar a conta", ButtonKind.Ghost, enabled = !busy) {
      busy = true; error = null
      scope.launch {
        runCatching { LedgerService(app.accountSettings).toHandOver() }.onSuccess { deleting = it }.onFailure { error = reason(it) }
        busy = false
      }
    }
  }

  if (signingOut) CalmoSheet("Sair da conta?", onDismiss = { signingOut = false }) {
    Text("As notas continuam neste celular, e o app passa a funcionar só aqui. Para sincronizar de novo, é só entrar.",
      style = MaterialTheme.typography.bodyMedium, color = Calmo.fg)
    Wide("Sair", ButtonKind.Solid) { app.accountSettings.signOut(); signingOut = false; onBack() }
    Wide("Continuar na conta", ButtonKind.Ghost) { signingOut = false }
  }

  deleting?.let { blocked ->
    CalmoSheet(if (blocked.isEmpty()) "Apagar sua conta?" else "Antes, passe suas cadernetas", onDismiss = { deleting = null }) {
      if (blocked.isNotEmpty()) {
        Text("Você é a única pessoa responsável por ${blocked.joinToString(", ")}, que têm outras pessoas. Torne alguém " +
          "responsável (Ajustes › a caderneta › Pessoas) e volte aqui.", style = MaterialTheme.typography.bodyMedium, color = Calmo.fg)
        Wide("Entendi", ButtonKind.Solid) { deleting = null }
        return@CalmoSheet
      }
      Text("Você sai de todas as cadernetas: as fotos que você enviou saem com você; as notas e o catálogo ficam com quem " +
        "continua. Cadernetas só suas são apagadas do servidor. Não dá para desfazer.",
        style = MaterialTheme.typography.bodyMedium, color = Calmo.fg)
      Sub("Neste celular nada é apagado. Para guardar tudo num arquivo antes, use Ajustes › Cópia de segurança.")
      // The password again (D125): two taps on an unlocked phone shouldn't be enough to delete an account.
      var password by remember { mutableStateOf("") }
      var wrong by remember { mutableStateOf<String?>(null) }
      Field(password, { password = it; wrong = null }, "Sua senha", password = true, last = true, error = wrong,
        hint = "Para confirmar que é você.")
      Wide(if (busy) "Apagando…" else "Apagar a conta", ButtonKind.Solid, enabled = !busy) {
        if (password.isEmpty()) { wrong = "Digite a senha."; return@Wide }
        busy = true; error = null
        scope.launch {
          val project = app.accountSettings.project()
          val checked = withContext(Dispatchers.IO) { runCatching { SupabaseClient(project.url, project.key).signIn(account?.email.orEmpty(), password) } }
          checked.onFailure {
            wrong = if ((it as? SupabaseException)?.code == "invalid_credentials") "Senha errada." else authError(it)
            busy = false
            return@launch
          }
          runCatching { LedgerService(app.accountSettings).deleteMe() }
            .onSuccess { app.accountSettings.signOut(); app.photoContribution.withdrawPending = false; deleting = null; onBack() }
            .onFailure { wrong = reason(it) }
          busy = false
        }
      }
      Wide("Cancelar", ButtonKind.Ghost) { deleting = null }
    }
  }
}
