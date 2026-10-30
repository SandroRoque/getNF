package io.github.sandroroque.getnf.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.calmo.ButtonKind
import io.github.sandroroque.getnf.ui.calmo.ScreenTitle

/** Before the first scans (D115): how the capture goes, once, before the camera opens. */
@Composable
fun ScanIntro(onStart: () -> Unit, onCancel: () -> Unit) {
  Column(Modifier.fillMaxSize().background(Calmo.bg).safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
      ReceiptSketch(Modifier.size(width = 120.dp, height = 150.dp).align(Alignment.CenterHorizontally))
      ScreenTitle("Como escanear")
      Step("A nota sobre a mesa, com luz", "Esticada, se der. A lanterna já começa acesa; o raio no alto a apaga.")
      Step("Percorra a nota devagar", "O app fotografa sozinho. O mapa ao lado mostra o que já foi lido e o que falta.")
      Step("Mostre o QR Code", "Ele fica no fim da nota. Lidos o QR e a nota inteira, a captura termina sozinha.")
      Step("Depois, a página da SEFAZ", "Os itens e os preços vêm de lá. Às vezes a SEFAZ pede para confirmar que você é uma pessoa.")
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Wide("Abrir a câmera", ButtonKind.Solid, onStart)
      Wide("Agora não", ButtonKind.Ghost, onCancel)
    }
  }
}

/** A receipt: lines down the paper, the QR Code at its foot, and an arrow along it. */
@Composable
private fun ReceiptSketch(modifier: Modifier) {
  val paper = Calmo.sheet; val edge = Calmo.line; val ink = Calmo.mute; val fg = Calmo.fg; val acc = Calmo.acc
  Canvas(modifier) {
    val w = size.width * .62f; val h = size.height; val x = 0f
    drawRoundRect(paper, Offset(x, 0f), Size(w, h), CornerRadius(6.dp.toPx()))
    drawRoundRect(edge, Offset(x, 0f), Size(w, h), CornerRadius(6.dp.toPx()), style = Stroke(1.dp.toPx()))
    val pad = w * .14f
    for (i in 0 until 6) {
      val y = h * (.1f + i * .085f)
      drawLine(ink.copy(alpha = .5f), Offset(x + pad, y), Offset(x + w - pad - (i % 3) * w * .12f, y), 3.dp.toPx(), StrokeCap.Round)
    }
    val q = w * .38f; val qx = x + (w - q) / 2; val qy = h - q - h * .08f
    drawRect(fg, Offset(qx, qy), Size(q, q), style = Stroke(3.dp.toPx()))
    drawRect(fg, Offset(qx + q * .3f, qy + q * .3f), Size(q * .4f, q * .4f))
    val ax = w + size.width * .18f
    drawLine(acc, Offset(ax, h * .12f), Offset(ax, h * .8f), 2.5.dp.toPx(), StrokeCap.Round)
    drawPath(Path().apply {
      moveTo(ax - 8.dp.toPx(), h * .8f - 9.dp.toPx()); lineTo(ax, h * .8f); lineTo(ax + 8.dp.toPx(), h * .8f - 9.dp.toPx())
    }, acc, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
  }
}
