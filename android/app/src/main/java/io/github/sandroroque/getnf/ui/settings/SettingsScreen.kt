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
import io.github.sandroroque.getnf.data.ServerConfig
import java.time.LocalDate
import io.github.sandroroque.getnf.upload.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onDone: () -> Unit) {
  val context = LocalContext.current
  val app = context.applicationContext as GetNFApp
  val scope = rememberCoroutineScope()
  val initial = remember { app.serverSettings.get() }
  var url by remember { mutableStateOf(initial.url) }
  var email by remember { mutableStateOf(initial.email) }
  var password by remember { mutableStateOf(initial.password) }
  var result by remember { mutableStateOf<String?>(null) }

  Scaffold(topBar = { TopAppBar(title = { Text("PocketBase server") }) }) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      OutlinedTextField(
        url,
        { url = it },
        label = { Text("Server URL") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        email,
        { email = it },
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        password,
        { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
          onClick = {
            result = "Testing…"
            scope.launch {
              result =
                withContext(Dispatchers.IO) {
                  runCatching { app.pocketBase.login(ServerConfig(url.trim(), email.trim(), password)) }
                    .fold({ "Login OK ✓" }, { "Failed: ${it.message}" })
                }
            }
          }
        ) {
          Text("Test")
        }
        Button(
          onClick = {
            app.serverSettings.set(ServerConfig(url, email, password))
            UploadWorker.enqueue(context)
            onDone()
          }
        ) {
          Text("Save")
        }
      }
      result?.let { Text(it) }
      HorizontalDivider()
      BackupSection(app)
    }
  }
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
      status = runCatching { app.backup.export(uri) { done, total -> status = "Exporting… $done of $total files" } }
        .fold({ "Exported ${it.receipts} receipts and ${it.photos} photos ✓" }, { "Export failed: ${it.message}" })
      busy = false
    }
  }
  val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    busy = true
    status = "Checking the backup…"
    scope.launch {
      runCatching { app.backup.inspect(uri) }
        .onSuccess { candidate = it; status = null }
        .onFailure { status = "Can't restore this file: ${it.message}" }
      busy = false
    }
  }

  Text("Backup", style = MaterialTheme.typography.titleMedium)
  Text("Receipts, photos, pages and the receipt database. Not the server login.", style = MaterialTheme.typography.bodySmall)
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    OutlinedButton(enabled = !busy, onClick = { export.launch("getnf-backup-${LocalDate.now()}.zip") }) { Text("Export data") }
    OutlinedButton(enabled = !busy, onClick = { restore.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Restore data") }
  }
  status?.let { Text(it) }

  candidate?.let { m ->
    AlertDialog(
      onDismissRequest = { candidate = null; app.backup.discardInspected() },
      title = { Text("Replace all data?") },
      text = {
        Text(
          "Backup from ${java.text.DateFormat.getDateTimeInstance().format(m.createdAt)}: ${m.receipts} receipts, ${m.photos} photos.\n" +
            "On this phone now: ${receipts.size} receipts, ${receipts.sumOf { it.photos.size }} photos.\n\n" +
            "Everything on this phone is replaced by the backup, and the app restarts.",
        )
      },
      confirmButton = {
        TextButton(onClick = {
          candidate = null; busy = true; status = "Restoring…"
          scope.launch {
            runCatching { app.backup.restoreAndRestart() }.onFailure { status = "Restore failed: ${it.message}"; busy = false }
          }
        }) { Text("Replace") }
      },
      dismissButton = { TextButton(onClick = { candidate = null; app.backup.discardInspected() }) { Text("Cancel") } },
    )
  }
}
