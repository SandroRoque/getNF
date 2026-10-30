package io.github.sandroroque.getnf.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.BackupManifest
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.Format
import io.github.sandroroque.getnf.ui.calmo.BackHeader
import io.github.sandroroque.getnf.ui.calmo.ButtonKind
import io.github.sandroroque.getnf.ui.calmo.Notice
import io.github.sandroroque.getnf.ui.calmo.ScreenTitle
import io.github.sandroroque.getnf.ui.calmo.Sub
import io.github.sandroroque.getnf.ui.calmo.Wide
import io.github.sandroroque.getnf.ui.catalog.CalmoSheet
import java.time.LocalDate
import kotlinx.coroutines.launch

/** Export everything to a file the user picks, or replace everything from one (D47); its own screen since D123. */
@Composable
fun BackupScreen(onBack: () -> Unit) {
  val app = LocalContext.current.applicationContext as GetNFApp
  val scope = rememberCoroutineScope()
  var status by remember { mutableStateOf<String?>(null) }
  var failed by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  var candidate by remember { mutableStateOf<BackupManifest?>(null) }
  val receipts by app.receiptStore.receipts.collectAsStateWithLifecycle()

  val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    busy = true; failed = false
    scope.launch {
      runCatching { app.backup.export(uri) { done, total -> status = "Exportando… $done de $total arquivos" } }
        .onSuccess { status = "Pronto: ${it.receipts} notas e ${it.photos} fotos no arquivo." }
        .onFailure { status = "A exportação falhou: ${it.message}"; failed = true }
      busy = false
    }
  }
  val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    busy = true; failed = false
    status = "Conferindo a cópia…"
    scope.launch {
      runCatching { app.backup.inspect(uri) }
        .onSuccess { candidate = it; status = null }
        .onFailure { status = "Não dá para restaurar este arquivo: ${it.message}"; failed = true }
      busy = false
    }
  }

  Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    BackHeader("Ajustes", onBack)
    ScreenTitle("Cópia de segurança")
    Text("Notas, fotos, páginas e o banco de dados deste celular, num arquivo que você escolhe: no celular, num pendrive " +
      "ou no Drive.", style = MaterialTheme.typography.bodyMedium, color = Calmo.fg)
    Sub("A conta não vai junto. Neste celular agora: ${Format.count(receipts.size, "nota", "notas")}, " +
      "${Format.count(receipts.sumOf { it.photos.size }, "foto", "fotos")}.")
    Spacer(Modifier.height(4.dp))
    Wide("Exportar para um arquivo", ButtonKind.Solid, enabled = !busy) { export.launch("caderneta-${LocalDate.now()}.zip") }
    Wide("Restaurar de um arquivo", ButtonKind.Outline, enabled = !busy) { restore.launch(arrayOf("application/zip", "application/octet-stream")) }
    status?.let { Notice(it, error = failed) }
  }

  candidate?.let { m ->
    CalmoSheet("Substituir tudo?", onDismiss = { candidate = null; app.backup.discardInspected() }) {
      Text("Cópia de ${Format.dateTime(m.createdAt)}: ${Format.count(m.receipts, "nota", "notas")}, " +
        "${Format.count(m.photos, "foto", "fotos")}.", style = MaterialTheme.typography.bodyMedium, color = Calmo.fg)
      Sub("Tudo neste celular (${Format.count(receipts.size, "nota", "notas")}) é substituído pela cópia, e o app reinicia.")
      Wide("Substituir", ButtonKind.Solid) {
        candidate = null; busy = true; status = "Restaurando…"
        scope.launch {
          runCatching { app.backup.restoreAndRestart() }.onFailure { status = "A restauração falhou: ${it.message}"; failed = true; busy = false }
        }
      }
      Wide("Cancelar", ButtonKind.Ghost) { candidate = null; app.backup.discardInspected() }
    }
  }
}
