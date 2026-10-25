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
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onDone: () -> Unit) {
  val app = LocalContext.current.applicationContext as GetNFApp
  Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
      io.github.sandroroque.getnf.ui.calmo.BackHeader("Voltar", onDone)
      io.github.sandroroque.getnf.ui.calmo.ScreenTitle("Ajustes")
      AccountSection(app, onDone)
      HorizontalDivider()
      HealthSection(app)
      HorizontalDivider()
      BackupSection(app)
  }
}

/**
 * Sign in to the Supabase project (D70). Only the refresh token is kept, never the password. The project's address
 * and publishable key come with the build; they can be changed here (say, to a local Supabase for development).
 */
@Composable
private fun AccountSection(app: GetNFApp, onDone: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val initial = remember { app.accountSettings.project() }
  var account by remember { mutableStateOf(app.accountSettings.account()) }
  var url by remember { mutableStateOf(initial.url) }
  var key by remember { mutableStateOf(initial.key) }
  var email by remember { mutableStateOf(account?.email ?: "") }
  var password by remember { mutableStateOf("") }
  var showProject by remember { mutableStateOf(!initial.isComplete) }
  var status by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }

  Text("Conta", style = MaterialTheme.typography.titleMedium)
  val current = account
  if (current != null) {
    Text("Conectado como ${current.email}. As notas e o catálogo sincronizam com o servidor.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Button(onClick = { SyncWorker.enqueue(context); onDone() }) { Text("Sincronizar agora") }
      OutlinedButton(onClick = { app.accountSettings.signOut(); account = null; status = "Desconectado. Nada foi apagado do celular." }) { Text("Sair") }
    }
  } else {
    Text("Sem conta, tudo fica só neste celular.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(email, { email = it }, label = { Text("E-mail") }, singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(password, { password = it }, label = { Text("Senha") }, singleLine = true,
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
    Button(enabled = !busy && email.isNotBlank() && password.isNotEmpty(), onClick = {
      busy = true; status = "Entrando…"
      scope.launch {
        val project = ProjectConfig(url, key)
        status = withContext(Dispatchers.IO) {
          runCatching { SupabaseClient(project.url.trim(), project.key.trim()).signIn(email.trim(), password) }
        }.fold(
          { session ->
            app.accountSettings.setProject(project)
            app.accountSettings.signedIn(session)
            account = app.accountSettings.account(); password = ""
            SyncWorker.enqueue(context)
            "Conectado ✓ Sincronizando…"
          },
          { "Não deu certo: ${it.message}" },
        )
        busy = false
      }
    }) { Text("Entrar") }
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
}

/** The LLM worker's last health report (D65, D69, stage 8), in plain words. */
@Composable
private fun HealthSection(app: GetNFApp) {
  val row by remember { app.database.edit().latestReport() }.collectAsStateWithLifecycle(null)
  Text("Saúde dos dados", style = MaterialTheme.typography.titleMedium)
  val r = row?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it.report) as kotlinx.serialization.json.JsonObject }.getOrNull() }
  if (row == null || r == null) {
    Text("O revisor (LLM no PC) ainda não rodou para esta conta.", style = MaterialTheme.typography.bodySmall)
    return
  }
  fun n(key: String) = (r[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content
  val lines = listOfNotNull(
    "Última revisão: ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(row!!.at)}",
    n("audited")?.let { "Conferidas pelo LLM: $it, discordou de ${n("disagreed") ?: "0"}" },
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
          "Cópia de ${java.text.DateFormat.getDateTimeInstance().format(m.createdAt)}: ${m.receipts} notas, ${m.photos} fotos.\n" +
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
