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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.ProjectConfig
import io.github.sandroroque.getnf.ui.Format
import io.github.sandroroque.getnf.ui.calmo.BackHeader
import io.github.sandroroque.getnf.ui.calmo.ButtonKind
import io.github.sandroroque.getnf.ui.calmo.FactRow
import io.github.sandroroque.getnf.ui.calmo.Field
import io.github.sandroroque.getnf.ui.calmo.Notice
import io.github.sandroroque.getnf.ui.calmo.Pill
import io.github.sandroroque.getnf.ui.calmo.ScreenTitle
import io.github.sandroroque.getnf.ui.calmo.SectionLabel
import io.github.sandroroque.getnf.ui.calmo.Sub
import io.github.sandroroque.getnf.ui.calmo.SwitchRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Debug builds only (D99, D123): the Supabase project to talk to (say, a local one), capture testing, and the LLM
 * reviewer's last health report (D65, D69), which is for whoever runs the worker, not for the person using the app.
 */
@Composable
fun DeveloperScreen(onBack: () -> Unit) {
  val app = LocalContext.current.applicationContext as GetNFApp
  val initial = remember { app.accountSettings.project() }
  var url by remember { mutableStateOf(initial.url) }
  var key by remember { mutableStateOf(initial.key) }
  var saved by remember { mutableStateOf<String?>(null) }
  var testing by remember { mutableStateOf(app.devSettings.testingCapture) }

  Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    BackHeader("Ajustes", onBack)
    ScreenTitle("Desenvolvimento")

    SectionLabel("Captura")
    SwitchRow("Testando a captura", "Ao terminar, não oferece a página da SEFAZ nem lembra dela depois: as consultas não se " +
      "gastam em notas já conhecidas.", testing) { testing = it; app.devSettings.testingCapture = it }

    Spacer(Modifier.height(8.dp))
    SectionLabel("Servidor")
    Field(url, { url = it; saved = null }, "Endereço do projeto", type = KeyboardType.Uri)
    Field(key, { key = it; saved = null }, "Chave pública (publishable)", last = true)
    if (app.accountSettings.account() != null) Sub("Trocar de servidor desconecta a conta.")
    Pill("Salvar servidor") { app.accountSettings.setProject(ProjectConfig(url, key)); saved = "Servidor salvo." }
    saved?.let { Notice(it) }

    Spacer(Modifier.height(8.dp))
    SectionLabel("Saúde dos dados")
    HealthReport(app)
  }
}

/** The LLM worker's last health report (D65, D69, stage 8), in plain words. */
@Composable
private fun HealthReport(app: GetNFApp) {
  val row by remember { app.database.edit().latestReport() }.collectAsStateWithLifecycle(null)
  val r = row?.let { runCatching { Json.parseToJsonElement(it.report) as JsonObject }.getOrNull() }
  if (row == null || r == null) {
    Sub("O revisor ainda não rodou para esta conta.")
    return
  }
  fun n(key: String) = (r[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
  Column {
    FactRow("Revisão", Format.dateTime(row!!.at))
    n("audited")?.let { FactRow("Conferidas", "$it, discordou de ${n("disagreed") ?: "0"}") }
    n("automatic_unconfirmed")?.let { FactRow("Automáticas", "$it não confirmadas (nunca conferidas: ${n("never_checked") ?: "?"})") }
    n("sample_error_rate")?.let { FactRow("Erro", "${Math.round(it.toDouble() * 100)}% de ${n("sample_size")} conferências") }
    n("abbreviations_added")?.takeIf { it != "0" }?.let { FactRow("Abreviações", it) }
    n("links_proposed")?.takeIf { it != "0" }?.let { FactRow("Mesmo produto", it) }
    n("sizes_proposed")?.takeIf { it != "0" }?.let { FactRow("Tamanhos", it) }
    n("backup_age_hours")?.let { FactRow("Backup", "há ${it.toDouble().toInt()} h") }
  }
}
