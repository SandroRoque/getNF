package io.github.sandroroque.getnf.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.BackupManifest
import io.github.sandroroque.getnf.data.ProjectConfig
import io.github.sandroroque.getnf.sync.SupabaseClient
import io.github.sandroroque.getnf.sync.SyncWorker
import io.github.sandroroque.getnf.ui.Format
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onDone: () -> Unit, onOpenLedger: (String) -> Unit = {}) {
  val app = LocalContext.current.applicationContext as GetNFApp
  var signedIn by remember { mutableStateOf(app.accountSettings.account() != null) }
  Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
      io.github.sandroroque.getnf.ui.calmo.BackHeader("Voltar", onDone)
      io.github.sandroroque.getnf.ui.calmo.ScreenTitle("Ajustes")
      AccountSection(app, onDone) { signedIn = it }
      HorizontalDivider()
      if (signedIn) {
        CadernetasSection(app, onOpenLedger)
        HorizontalDivider()
      }
      HealthSection(app)
      HorizontalDivider()
      BackupSection(app)
      HorizontalDivider()
      ContributionSection(app)
      if (io.github.sandroroque.getnf.BuildConfig.DEBUG) {
        HorizontalDivider()
        DeveloperSection(app)
      }
  }
}

/** Photos for improving the app (D103): off until the user turns it on; turning it off takes them back (D105). */
@Composable
private fun ContributionSection(app: GetNFApp) {
  val context = LocalContext.current
  var enabled by remember { mutableStateOf(app.photoContribution.enabled) }
  var withdrawing by remember { mutableStateOf(app.photoContribution.withdrawPending) }
  io.github.sandroroque.getnf.ui.calmo.SectionLabel("Ajudar a melhorar o app")
  Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text("Enviar fotos das notas que o app não leu bem", style = MaterialTheme.typography.bodyLarge)
      io.github.sandroroque.getnf.ui.calmo.Sub("Só dessas notas, com os dados do cartão e o CPF cobertos.")
    }
    androidx.compose.material3.Switch(checked = enabled, onCheckedChange = {
      enabled = it
      app.photoContribution.enabled = it
      withdrawing = app.photoContribution.withdrawPending
      if (withdrawing) SyncWorker.enqueue(context)
    })
  }
  if (withdrawing) io.github.sandroroque.getnf.ui.calmo.Sub(
    if (app.accountSettings.account() != null) "As fotos já enviadas serão apagadas do servidor na próxima sincronização."
    else "As fotos já enviadas serão apagadas do servidor quando você entrar na conta de novo."
  )
}

/** Debug builds only (D99). */
@Composable
private fun DeveloperSection(app: GetNFApp) {
  var testing by remember { mutableStateOf(app.devSettings.testingCapture) }
  io.github.sandroroque.getnf.ui.calmo.SectionLabel("Desenvolvimento")
  Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text("Testando a captura", style = MaterialTheme.typography.bodyLarge)
      io.github.sandroroque.getnf.ui.calmo.Sub("Ao terminar, não oferece a página da SEFAZ nem lembra dela depois: as consultas não se gastam em notas já conhecidas.")
    }
    androidx.compose.material3.Switch(checked = testing, onCheckedChange = { testing = it; app.devSettings.testingCapture = it })
  }
}

/**
 * Sign in to the Supabase project (D70). Only the refresh token is kept, never the password. The project's address
 * and publishable key come with the build; they can be changed here (say, to a local Supabase for development).
 */
@Composable
private fun AccountSection(app: GetNFApp, onDone: () -> Unit, onSignedIn: (Boolean) -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val initial = remember { app.accountSettings.project() }
  var account by remember { mutableStateOf(app.accountSettings.account()) }
  var url by remember { mutableStateOf(initial.url) }
  var key by remember { mutableStateOf(initial.key) }
  var email by remember { mutableStateOf(account?.email ?: "") }
  var password by remember { mutableStateOf("") }
  var showProject by remember { mutableStateOf(!initial.isComplete) }
  var creating by remember { mutableStateOf(false) }
  var name by remember { mutableStateOf("") }
  var deleting by remember { mutableStateOf<List<String>?>(null) }
  var status by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }

  Text("Conta", style = MaterialTheme.typography.titleMedium)
  val current = account
  if (current != null) {
    Text("Conectado como ${current.email}. As notas e o catálogo sincronizam com o servidor.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Button(onClick = { SyncWorker.enqueue(context); onDone() }) { Text("Sincronizar agora") }
      OutlinedButton(onClick = { app.accountSettings.signOut(); account = null; onSignedIn(false); status = "Desconectado. Nada foi apagado do celular." }) { Text("Sair") }
    }
    TextButton(enabled = !busy, onClick = {
      busy = true
      scope.launch {
        runCatching { io.github.sandroroque.getnf.sync.LedgerService(app.accountSettings).toHandOver() }
          .onSuccess { deleting = it }.onFailure { status = "Não deu certo: ${it.message}" }
        busy = false
      }
    }) { Text("Apagar minha conta") }
  } else {
    Text("Sem conta, tudo fica só neste celular. Com uma, você pode dividir uma caderneta com outras pessoas.", style = MaterialTheme.typography.bodySmall)
    if (creating) OutlinedTextField(name, { name = it }, label = { Text("Seu nome (como os outros vão te ver)") }, singleLine = true,
      modifier = Modifier.fillMaxWidth())
    OutlinedTextField(email, { email = it }, label = { Text("E-mail") }, singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(password, { password = it }, label = { Text("Senha") }, singleLine = true,
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Button(enabled = !busy && email.isNotBlank() && password.isNotEmpty() && (!creating || name.isNotBlank()), onClick = {
        busy = true; status = if (creating) "Criando a conta…" else "Entrando…"
        scope.launch {
          val project = ProjectConfig(url, key)
          status = withContext(Dispatchers.IO) {
            runCatching {
              val client = SupabaseClient(project.url.trim(), project.key.trim())
              if (creating) client.signUp(email.trim(), password, name.trim()) else client.signIn(email.trim(), password)
            }
          }.fold(
            { session ->
              if (session == null) "Conta criada. Confirme pelo link no seu e-mail e depois entre aqui."
              else {
                app.accountSettings.setProject(project)
                app.accountSettings.signedIn(session)
                account = app.accountSettings.account(); password = ""; onSignedIn(true)
                SyncWorker.enqueue(context)
                "Conectado ✓ Sincronizando…"
              }
            },
            { "Não deu certo: ${it.message}" },
          )
          busy = false
        }
      }) { Text(if (creating) "Criar conta" else "Entrar") }
      TextButton(onClick = { creating = !creating }) { Text(if (creating) "Já tenho conta" else "Criar conta") }
    }
  }
  TextButton(onClick = { showProject = !showProject }) { Text(if (showProject) "Ocultar servidor" else "Servidor…") }
  if (showProject) {
    OutlinedTextField(url, { url = it }, label = { Text("Endereço do projeto") }, singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(key, { key = it }, label = { Text("Chave pública (publishable)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (current != null) Text("Trocar de servidor desconecta a conta.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = {
      app.accountSettings.setProject(ProjectConfig(url, key)); account = app.accountSettings.account(); status = "Servidor salvo."
    }) { Text("Salvar servidor") }
  }
  status?.let { Text(it) }

  deleting?.let { blocked ->
    AlertDialog(
      onDismissRequest = { deleting = null },
      title = { Text(if (blocked.isEmpty()) "Apagar sua conta?" else "Antes, passe suas cadernetas") },
      text = {
        Text(
          if (blocked.isNotEmpty()) "Você é a única pessoa responsável por ${blocked.joinToString(", ")}, que têm outras pessoas. " +
            "Torne alguém responsável (Ajustes › Cadernetas) e volte aqui."
          else "Sua conta sai do servidor, e você sai de todas as cadernetas: as fotos que você enviou saem com você; as notas e o " +
            "catálogo ficam com quem continua. Cadernetas só suas são apagadas do servidor.\n\nNeste celular nada é apagado. " +
            "Se quiser guardar tudo num arquivo, use \"Exportar\" em Cópia de segurança antes.",
        )
      },
      confirmButton = {
        if (blocked.isEmpty()) TextButton(onClick = {
          deleting = null; busy = true; status = "Apagando a conta…"
          scope.launch {
            runCatching { io.github.sandroroque.getnf.sync.LedgerService(app.accountSettings).deleteMe() }
              .onSuccess { app.accountSettings.signOut(); app.photoContribution.withdrawPending = false; account = null; onSignedIn(false); status = "Conta apagada. O que está neste celular continua aqui." }
              .onFailure { status = "Não deu certo: ${it.message}" }
            busy = false
          }
        }) { Text("Apagar a conta") }
      },
      dismissButton = { TextButton(onClick = { deleting = null }) { Text(if (blocked.isEmpty()) "Cancelar" else "Entendi") } },
    )
  }
}

/** The LLM worker's last health report (D65, D69, stage 8), in plain words. */
@Composable
private fun HealthSection(app: GetNFApp) {
  val row by remember { app.database.edit().latestReport() }.collectAsStateWithLifecycle(null)
  Text("Saúde dos dados", style = MaterialTheme.typography.titleMedium)
  val r = row?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it.report) as kotlinx.serialization.json.JsonObject }.getOrNull() }
  if (row == null || r == null) {
    Text("O revisor ainda não rodou para esta conta.", style = MaterialTheme.typography.bodySmall)
    return
  }
  fun n(key: String) = (r[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content
  val lines = listOfNotNull(
    "Última revisão: ${Format.dateTime(row!!.at)}",
    n("audited")?.let { "Conferidas pelo revisor: $it, discordou de ${n("disagreed") ?: "0"}" },
    n("automatic_unconfirmed")?.let { "Categorias automáticas ainda não confirmadas: $it (nunca conferidas: ${n("never_checked") ?: "?"})" },
    n("sample_error_rate")?.let { "Erro medido na amostra: ${Math.round(it.toDouble() * 100)}% de ${n("sample_size")} conferências" },
    n("abbreviations_added")?.takeIf { it != "0" }?.let { "Abreviações aprendidas: $it" },
    n("links_proposed")?.takeIf { it != "0" }?.let { "Mesmos produtos sugeridos: $it" },
    n("sizes_proposed")?.takeIf { it != "0" }?.let { "Tamanhos sugeridos: $it" },
    n("backup_age_hours")?.let { "Último backup: há ${it.toDouble().toInt()} h" },
  )
  lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** Export everything to a file the user picks, or replace everything from one (D47). */
@Composable
private fun BackupSection(app: GetNFApp) {
  val scope = rememberCoroutineScope()
  var status by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  var candidate by remember { mutableStateOf<BackupManifest?>(null) }
  val receipts by app.receiptStore.receipts.collectAsStateWithLifecycle()

  val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    busy = true
    scope.launch {
      status = runCatching { app.backup.export(uri) { done, total -> status = "Exportando… $done de $total arquivos" } }
        .fold({ "Exportadas ${it.receipts} notas e ${it.photos} fotos ✓" }, { "A exportação falhou: ${it.message}" })
      busy = false
    }
  }
  val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    busy = true
    status = "Conferindo a cópia…"
    scope.launch {
      runCatching { app.backup.inspect(uri) }
        .onSuccess { candidate = it; status = null }
        .onFailure { status = "Não dá para restaurar este arquivo: ${it.message}" }
      busy = false
    }
  }

  Text("Cópia de segurança", style = MaterialTheme.typography.titleMedium)
  Text("Notas, fotos, páginas e o banco de dados do celular, num arquivo que você escolhe. A conta não vai junto.", style = MaterialTheme.typography.bodySmall)
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    OutlinedButton(enabled = !busy, onClick = { export.launch("getnf-backup-${LocalDate.now()}.zip") }) { Text("Exportar") }
    OutlinedButton(enabled = !busy, onClick = { restore.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Restaurar") }
  }
  status?.let { Text(it) }

  candidate?.let { m ->
    AlertDialog(
      onDismissRequest = { candidate = null; app.backup.discardInspected() },
      title = { Text("Substituir tudo?") },
      text = {
        Text(
          "Cópia de ${Format.dateTime(m.createdAt)}: ${m.receipts} notas, ${m.photos} fotos.\n" +
            "Neste celular agora: ${receipts.size} notas, ${receipts.sumOf { it.photos.size }} fotos.\n\n" +
            "Tudo neste celular é substituído pela cópia, e o app reinicia.",
        )
      },
      confirmButton = {
        TextButton(onClick = {
          candidate = null; busy = true; status = "Restaurando…"
          scope.launch {
            runCatching { app.backup.restoreAndRestart() }.onFailure { status = "A restauração falhou: ${it.message}"; busy = false }
          }
        }) { Text("Substituir") }
      },
      dismissButton = { TextButton(onClick = { candidate = null; app.backup.discardInspected() }) { Text("Cancelar") } },
    )
  }
}
