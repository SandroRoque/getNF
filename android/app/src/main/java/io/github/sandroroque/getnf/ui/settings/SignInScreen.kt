package io.github.sandroroque.getnf.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.sync.SupabaseClient
import io.github.sandroroque.getnf.sync.SupabaseException
import io.github.sandroroque.getnf.sync.SyncWorker
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.calmo.BackHeader
import io.github.sandroroque.getnf.ui.calmo.ButtonKind
import io.github.sandroroque.getnf.ui.calmo.Field
import io.github.sandroroque.getnf.ui.calmo.Icons
import io.github.sandroroque.getnf.ui.calmo.Notice
import io.github.sandroroque.getnf.ui.calmo.ScreenTitle
import io.github.sandroroque.getnf.ui.calmo.Segments
import io.github.sandroroque.getnf.ui.calmo.Sub
import io.github.sandroroque.getnf.ui.calmo.TextLink
import io.github.sandroroque.getnf.ui.calmo.Wide
import io.github.sandroroque.getnf.ui.catalog.CalmoSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The project's minimum (supabase/config.toml, and the online project's Auth settings). */
private const val MIN_PASSWORD = 6

/** What went wrong signing up or in, in words the person can act on; the server's own text when it's something else. */
internal fun authError(t: Throwable): String {
  val code = (t as? SupabaseException)?.code
  val text = t.message.orEmpty()
  return when {
    code == "invalid_credentials" -> "E-mail ou senha errados."
    code == "email_not_confirmed" -> "Falta confirmar o e-mail: toque no link que mandamos (olhe também o spam)."
    code == "user_already_exists" -> "Já existe uma conta com esse e-mail. Entre com a senha dela."
    code == "weak_password" -> "Senha fraca: use pelo menos $MIN_PASSWORD caracteres."
    code == "email_address_invalid" || code == "validation_failed" -> "Esse e-mail não parece certo."
    code == "over_email_send_rate_limit" || "rate limit" in text -> "Muitos e-mails mandados agora. Tente de novo daqui a uma hora."
    t is java.io.IOException -> "Sem conexão com o servidor. Tente de novo."
    else -> "Não deu certo: $text"
  }
}

/**
 * Entrar or Criar conta (D123), a screen of its own: a switch between the two at the top, the fields each needs, one
 * wide button, and errors under the field they're about. After a sign-up that needs the e-mail confirmed, the screen
 * becomes "Confira seu e-mail". Only the refresh token is kept, never the password (D70).
 */
@Composable
fun SignInScreen(onBack: () -> Unit, onSignedIn: () -> Unit) {
  val context = LocalContext.current
  val app = context.applicationContext as GetNFApp
  val scope = rememberCoroutineScope()
  var mode by rememberSaveable { mutableIntStateOf(0) } // 0 Entrar, 1 Criar conta
  var name by rememberSaveable { mutableStateOf("") }
  var email by rememberSaveable { mutableStateOf("") }
  // The password stays in memory only, for "Já confirmei, entrar".
  var password by remember { mutableStateOf("") }
  var repeat by remember { mutableStateOf("") }
  var tried by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var notice by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  // An e-mail waiting for its confirmation link: the screen turns into "Confira seu e-mail".
  var awaiting by rememberSaveable { mutableStateOf<String?>(null) }
  var forgot by remember { mutableStateOf(false) }
  val creating = mode == 1

  fun client() = app.accountSettings.project().let { SupabaseClient(it.url, it.key) }

  /** Signs in or up; [SupabaseClient.SignUp.Done] means a session, whichever it was. */
  fun submit(create: Boolean) {
    tried = true; error = null; notice = null
    val address = email.trim()
    val ok = address.isNotEmpty() && password.isNotEmpty() &&
      (!create || (name.isNotBlank() && password.length >= MIN_PASSWORD && repeat == password))
    if (!ok) return
    busy = true
    scope.launch {
      val result = withContext(Dispatchers.IO) {
        runCatching { if (create) client().signUp(address, password, name.trim()) else SupabaseClient.SignUp.Done(client().signIn(address, password)) }
      }
      result.onSuccess { r ->
        when (r) {
          is SupabaseClient.SignUp.Done -> {
            app.accountSettings.signedIn(r.session)
            SyncWorker.enqueue(context)
            onSignedIn()
          }
          SupabaseClient.SignUp.ConfirmEmail -> { awaiting = address; repeat = "" }
          SupabaseClient.SignUp.Exists -> { mode = 0; repeat = ""; error = "Já existe uma conta com $address. Entre com a senha dela." }
        }
      }.onFailure {
        when ((it as? SupabaseException)?.code) {
          // Signing in before confirming: back to "Confira seu e-mail", where the link can be sent again.
          "email_not_confirmed" -> { awaiting = address; notice = "Ainda falta confirmar: toque no link do e-mail." }
          // Where confirmations are off, Supabase says so outright: on to signing in, as for SignUp.Exists.
          "user_already_exists" -> { mode = 0; repeat = ""; error = authError(it) }
          else -> error = authError(it)
        }
      }
      busy = false
    }
  }

  Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
    .padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    BackHeader("Ajustes", onBack)
    val waiting = awaiting
    if (waiting != null) {
      Spacer(Modifier.height(24.dp))
      Icon(Icons.Mail, null, Modifier.size(32.dp), tint = Calmo.fg)
      ScreenTitle("Confira seu e-mail")
      Text("Mandamos um link para $waiting. Toque nele e volte aqui para entrar. Se não chegar em alguns minutos, olhe o spam.",
        style = MaterialTheme.typography.bodyLarge, color = Calmo.fg)
      notice?.let { Notice(it) }
      error?.let { Notice(it, error = true) }
      Spacer(Modifier.height(8.dp))
      if (password.isNotEmpty()) Wide("Já confirmei, entrar", ButtonKind.Solid, enabled = !busy) { email = waiting; submit(create = false) }
      else Wide("Já confirmei, entrar", ButtonKind.Solid) { awaiting = null; mode = 0; email = waiting; notice = null; error = null }
      Wide("Mandar o link de novo", ButtonKind.Outline, enabled = !busy) {
        busy = true; error = null; notice = null
        scope.launch {
          withContext(Dispatchers.IO) { runCatching { client().resendConfirmation(waiting) } }
            .onSuccess { notice = "Link mandado de novo para $waiting." }.onFailure { error = authError(it) }
          busy = false
        }
      }
      TextLink("Usar outro e-mail", Modifier.padding(top = 4.dp)) {
        awaiting = null; mode = 1; email = ""; password = ""; repeat = ""; tried = false; notice = null; error = null
      }
      return@Column
    }

    ScreenTitle("Sua conta")
    Segments(listOf("Entrar", "Criar conta"), mode) { mode = it; error = null; notice = null; tried = false }
    Spacer(Modifier.height(4.dp))
    if (creating) Field(name, { name = it }, "Seu nome", hint = "Como as pessoas da sua caderneta vão te ver.",
      error = if (tried && name.isBlank()) "Diga um nome." else null)
    Field(email, { email = it.trim() }, "E-mail", type = KeyboardType.Email,
      error = if (tried && email.isBlank()) "Digite seu e-mail." else null)
    Field(password, { password = it }, "Senha", password = true, last = !creating, onDone = { submit(creating) },
      hint = if (creating) "Pelo menos $MIN_PASSWORD caracteres." else null,
      error = when {
        tried && password.isEmpty() -> "Digite a senha."
        creating && tried && password.length < MIN_PASSWORD -> "Use pelo menos $MIN_PASSWORD caracteres."
        else -> null
      })
    // A new password is typed twice: a typo there would lock the person out of the account they just made.
    if (creating) Field(repeat, { repeat = it }, "Repita a senha", password = true, last = true, onDone = { submit(true) },
      error = if (repeat.isNotEmpty() && repeat != password || tried && repeat != password) "As senhas não são iguais." else null)
    error?.let { Notice(it, error = true) }
    notice?.let { Notice(it) }
    Wide(
      when { busy && creating -> "Criando a conta…"; busy -> "Entrando…"; creating -> "Criar conta"; else -> "Entrar" },
      ButtonKind.Solid, enabled = !busy,
    ) { submit(creating) }
    if (!creating) TextLink("Esqueci a senha") { forgot = true }
    Spacer(Modifier.height(8.dp))
    Sub(
      if (creating) "Mandamos um link para confirmar o e-mail. Sem conta o app funciona do mesmo jeito, só neste celular."
      else "Nada do que está neste celular é apagado ao entrar: as notas daqui sobem para a sua caderneta."
    )
  }

  if (forgot) ForgotSheet(initial = email, onDismiss = { forgot = false }) { address -> client().recover(address) }
}

/** "Esqueci a senha": a link by e-mail to choose a new one on the site (D123). */
@Composable
private fun ForgotSheet(initial: String, onDismiss: () -> Unit, send: (String) -> Unit) {
  val scope = rememberCoroutineScope()
  var email by remember { mutableStateOf(initial) }
  var sent by remember { mutableStateOf<String?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  CalmoSheet("Nova senha", onDismiss = onDismiss) {
    val done = sent
    if (done != null) {
      Text("Se existe uma conta com $done, mandamos para lá um link para escolher uma nova senha. Abra no celular ou no " +
        "computador, escolha a senha e volte aqui para entrar.", style = MaterialTheme.typography.bodyMedium, color = Calmo.fg)
      Sub("Não chegou em alguns minutos? Olhe o spam.")
      Wide("Entendi", ButtonKind.Solid, onClick = onDismiss)
      return@CalmoSheet
    }
    Sub("Mandamos um link para o seu e-mail; nele você escolhe a senha nova.")
    Field(email, { email = it.trim(); error = null }, "E-mail", type = KeyboardType.Email, last = true, error = error)
    Wide(if (busy) "Mandando…" else "Mandar o link", ButtonKind.Solid, enabled = !busy) {
      val address = email.trim()
      if (address.isEmpty()) { error = "Digite seu e-mail."; return@Wide }
      busy = true
      scope.launch {
        withContext(Dispatchers.IO) { runCatching { send(address) } }.onSuccess { sent = address }.onFailure { error = authError(it) }
        busy = false
      }
    }
  }
}
