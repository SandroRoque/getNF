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
import io.github.sandroroque.getnf.data.SpendingLine
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.calmo.Icons

/** One step of the first ones: done or not, and where tapping it goes. */
class FirstStep(val title: String, val text: String, val done: Boolean, val onClick: (() -> Unit)?)

/**
 * The way from an empty app to a full one (D115), each step what the next receipt unlocks. Done steps stay ticked;
 * the card goes once all are done or the user hides it.
 */
fun firstSteps(
  receipts: List<ReceiptListItem>, lines: List<SpendingLine>, hasInflation: Boolean, signedIn: Boolean,
  onScan: () -> Unit, onFetch: () -> Unit, onAccount: () -> Unit,
): List<FirstStep> = listOf(
  FirstStep("Escaneie uma nota", "O QR Code da nota fiscal, com o botão do meio.", receipts.isNotEmpty(), onScan),
  FirstStep("Busque a página da SEFAZ", "Os itens e os preços vêm de lá; aí aparecem seus gastos.", receipts.any { it.hasPage },
    onFetch.takeIf { receipts.isNotEmpty() }),
  FirstStep("Compre algo de novo", "O produto ganha um histórico de preço.",
    lines.groupBy { it.productId }.values.any { l -> l.map { it.chave }.distinct().size > 1 }, null),
  FirstStep("Dois meses de notas", "Aparece sua inflação pessoal.", hasInflation, null),
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
