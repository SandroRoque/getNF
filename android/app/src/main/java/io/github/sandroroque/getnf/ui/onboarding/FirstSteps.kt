package io.github.sandroroque.getnf.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.sandroroque.getnf.data.ReceiptListItem
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.calmo.Icons

/** The first steps are done once a receipt has its page: the account is optional. */
fun List<FirstStep>.finished(): Boolean = take(2).all { it.done }

/** One step of the first ones: done or not, and where tapping it goes. */
class FirstStep(val title: String, val text: String, val done: Boolean, val onClick: (() -> Unit)?)

/**
 * The first sitting (D115, D116): a receipt scanned and its page fetched, and the account, which is optional. What
 * comes with time (price history, the IPCI) isn't a step: it's said where it will appear. The card goes once the page
 * is fetched, or when the user hides it.
 */
fun firstSteps(
  receipts: List<ReceiptListItem>, signedIn: Boolean, onScan: () -> Unit, onFetch: () -> Unit, onAccount: () -> Unit,
): List<FirstStep> = listOf(
  FirstStep("Escaneie uma nota", "O QR Code da nota fiscal, com o botão do meio.", receipts.isNotEmpty(), onScan),
  FirstStep("Busque a página da SEFAZ", "Os itens e os preços vêm de lá; aí aparecem seus gastos.", receipts.any { it.hasPage },
    onFetch.takeIf { receipts.isNotEmpty() }),
  FirstStep("Crie uma conta (opcional)", "Os itens ganham categoria sozinhos e as notas ficam salvas online.", signedIn, onAccount),
)

@Composable
fun FirstStepsCard(steps: List<FirstStep>, onDemo: (() -> Unit)?, onHide: () -> Unit) {
  val shape = RoundedCornerShape(14.dp)
  Column(
    Modifier.fillMaxWidth().clip(shape).background(Calmo.sheet).border(1.dp, Calmo.line, shape).padding(horizontal = 14.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Primeiros passos", style = MaterialTheme.typography.titleSmall, color = Calmo.fg, modifier = Modifier.weight(1f))
      Text("${steps.count { it.done }} de ${steps.size}", style = MaterialTheme.typography.labelMedium, color = Calmo.mute)
    }
    // The next step to take is the first not done; it's the one shown strong.
    val next = steps.indexOfFirst { !it.done }
    steps.forEachIndexed { i, s -> StepRow(s, strong = i == next) }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      if (onDemo != null) TextLink("Ver um exemplo", onDemo)
      Spacer(Modifier.weight(1f))
      Text("Esconder", style = MaterialTheme.typography.labelLarge, color = Calmo.mute,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onHide).padding(horizontal = 6.dp, vertical = 8.dp))
    }
  }
}

@Composable
private fun StepRow(step: FirstStep, strong: Boolean) {
  val click = step.onClick.takeIf { !step.done }
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).then(if (click != null) Modifier.clickable(onClick = click) else Modifier)
      .padding(vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier.size(22.dp).clip(CircleShape)
        .then(if (step.done) Modifier.background(Calmo.acc) else Modifier.border(1.5.dp, if (strong) Calmo.fg else Calmo.line, CircleShape)),
      contentAlignment = Alignment.Center,
    ) {
      if (step.done) Icon(Icons.Check, null, Modifier.size(14.dp), tint = Calmo.bg)
    }
    Column(Modifier.weight(1f)) {
      Text(step.title, style = MaterialTheme.typography.bodyMedium, color = if (step.done) Calmo.mute else Calmo.fg)
      if (!step.done) Text(step.text, style = MaterialTheme.typography.bodySmall, color = Calmo.mute)
    }
    if (click != null) Icon(Icons.Chevron, null, Modifier.size(14.dp), tint = Calmo.mute)
  }
}
