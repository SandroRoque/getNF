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
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.ProjectConfig
import io.github.sandroroque.getnf.ui.calmo.BackHeader
import io.github.sandroroque.getnf.ui.calmo.ButtonKind
import io.github.sandroroque.getnf.ui.calmo.Field
import io.github.sandroroque.getnf.ui.calmo.Notice
import io.github.sandroroque.getnf.ui.calmo.Pill
import io.github.sandroroque.getnf.ui.calmo.ScreenTitle
import io.github.sandroroque.getnf.ui.calmo.SectionLabel
import io.github.sandroroque.getnf.ui.calmo.Sub
import io.github.sandroroque.getnf.ui.calmo.SwitchRow

/**
 * Debug builds only (D99, D123): the Supabase project to talk to (say, a local one) and capture testing. The LLM
 * reviewer's health report was here until the worker was suspended (D168).
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
  }
}
