package io.github.sandroroque.getnf.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.LocalCadernetas
import io.github.sandroroque.getnf.data.ReceiptListItem
import io.github.sandroroque.getnf.sync.LedgerService
import io.github.sandroroque.getnf.sync.SyncWorker
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.Format
import io.github.sandroroque.getnf.ui.calmo.BackHeader
import io.github.sandroroque.getnf.ui.calmo.ButtonKind
import io.github.sandroroque.getnf.ui.calmo.EmptyState
import io.github.sandroroque.getnf.ui.calmo.Hairline
import io.github.sandroroque.getnf.ui.calmo.Notice
import io.github.sandroroque.getnf.ui.calmo.ScreenTitle
import io.github.sandroroque.getnf.ui.calmo.SectionLabel
import io.github.sandroroque.getnf.ui.calmo.Sub
import io.github.sandroroque.getnf.ui.calmo.TextLink
import io.github.sandroroque.getnf.ui.calmo.Wide
import kotlinx.coroutines.launch

/**
 * "Trazer notas" (D126): copies receipts scanned on this phone from its other cadernetas into the open one. The first
 * thing a newly created caderneta shows; also reachable from Início while the caderneta is empty and from its screen.
 * Everything starts selected; the sources keep their receipts.
 */
@Composable
fun ImportScreen(onDone: () -> Unit) {
  val app = LocalContext.current.applicationContext as GetNFApp
  val scope = rememberCoroutineScope()
  val local = remember { LocalCadernetas(app, app.accountSettings) }
  val open = remember { app.accountSettings.ledgerId() }
  var sources by remember { mutableStateOf<List<LocalCadernetas.Source>?>(null) }
  var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
  // Per caderneta, the chaves left out (so everything starts selected).
  val excluded = remember { mutableStateMapOf<String, Set<String>>() }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    sources = runCatching { local.sources(open) }.getOrElse { error = "Não deu para ler as outras cadernetas: ${it.message}"; emptyList() }
    names = runCatching { LedgerService(app.accountSettings).mine().associate { it.id to it.name } }.getOrDefault(emptyMap())
  }
  val list = sources
  val chosen = list.orEmpty().sumOf { s -> s.receipts.count { it.chave !in excluded[s.ledger].orEmpty() } }
  val here = names[open]

  Column(Modifier.fillMaxSize().statusBarsPadding()) {
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)) {
      item { BackHeader("Voltar", onDone) }
      item { ScreenTitle("Trazer notas") }
      item {
        Text("Copie para ${here ?: "esta caderneta"} as notas que você escaneou em outra caderneta deste celular. A outra " +
          "continua com elas.", style = MaterialTheme.typography.bodyMedium, color = Calmo.fg)
      }
      when {
        list == null -> item { Sub("Procurando notas neste celular…") }
        list.isEmpty() -> item {
          EmptyState("Nenhuma outra caderneta deste celular tem notas escaneadas por você. As que você escanear daqui " +
            "em diante entram nesta.")
        }
        else -> for (s in list) {
          val out = excluded[s.ledger].orEmpty()
          item(key = "h-${s.ledger}") {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
              SectionLabel("${names[s.ledger] ?: "Outra caderneta"} · ${Format.count(s.receipts.size, "nota", "notas")}", Modifier.weight(1f))
              if (out.isEmpty()) TextLink("Nenhuma") { excluded[s.ledger] = s.receipts.map { it.chave }.toSet() }
              else TextLink("Todas") { excluded[s.ledger] = emptySet() }
            }
          }
          items(s.receipts, key = { "${s.ledger}-${it.chave}" }) { r ->
            CheckRow(r, checked = r.chave !in out) { on ->
              excluded[s.ledger] = if (on) out - r.chave else out + r.chave
            }
          }
        }
      }
      error?.let { item { Notice(it, error = true) } }
    }
    Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (list.isNullOrEmpty()) Wide("Continuar", ButtonKind.Solid, onClick = onDone)
      else {
        Wide(
          when { busy -> "Trazendo…"; chosen == 0 -> "Escolha as notas"; else -> "Trazer ${Format.count(chosen, "nota", "notas")}" },
          ButtonKind.Solid, enabled = !busy && chosen > 0,
        ) {
          busy = true; error = null
          scope.launch {
            runCatching {
              for (s in list) {
                val out = excluded[s.ledger].orEmpty()
                local.copyInto(app.receiptStore, s.ledger, s.receipts.map { it.chave }.filter { it !in out }.toSet())
              }
              // They come with their pages: read them here, and send them up.
              app.receiptParser.parsePending(app.receiptStore.receipts.value)
              SyncWorker.enqueue(app)
            }.onSuccess { onDone() }.onFailure { error = "A cópia parou no meio: ${it.message}. O que já veio fica; tente de novo." }
            busy = false
          }
        }
        Wide("Agora não", ButtonKind.Ghost, onClick = onDone)
      }
    }
  }
}

@Composable
private fun CheckRow(r: ReceiptListItem, checked: Boolean, onChange: (Boolean) -> Unit) {
  Column {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = Calmo.fg, uncheckedColor = Calmo.mute, checkmarkColor = Calmo.bg))
      Column(Modifier.weight(1f)) {
        Text(r.title, style = MaterialTheme.typography.bodyMedium, color = Calmo.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Sub(Format.date(r.issuedAt ?: r.capturedAt))
      }
      r.paidCents?.let { Text(Format.brl(it), style = MaterialTheme.typography.bodyMedium, color = Calmo.fg) }
    }
    Hairline()
  }
}
