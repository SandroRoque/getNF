package io.github.sandroroque.getnf.ui.onboarding

import io.github.sandroroque.getnf.ui.calmo.Wide
import io.github.sandroroque.getnf.ui.calmo.TextLink
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.sandroroque.getnf.R
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.calmo.ButtonKind
import io.github.sandroroque.getnf.ui.calmo.Pill
import io.github.sandroroque.getnf.ui.calmo.ScreenTitle

/**
 * The first start (D115): three short pages, what the app does, what it gives back and when, what to know before
 * the first scan; then the first scan, the demo, or the app as it is.
 */
@Composable
fun WelcomeScreen(onScan: () -> Unit, onDemo: () -> Unit, onExplore: () -> Unit, onSignIn: () -> Unit) {
  var page by rememberSaveable { mutableIntStateOf(0) }
  BackHandler(enabled = page > 0) { page-- }
  Column(Modifier.fillMaxSize().background(Calmo.bg).safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
      Dots(page, 3)
      Spacer(Modifier.weight(1f))
      if (page < 2) Text("Pular", style = MaterialTheme.typography.labelLarge, color = Calmo.mute,
        modifier = Modifier.clip(CircleShape).clickable { page = 2 }.padding(horizontal = 10.dp, vertical = 6.dp))
    }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)) {
      when (page) {
        0 -> {
          Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(150.dp).align(Alignment.CenterHorizontally))
          ScreenTitle("Suas notas fiscais, seus preços.")
          Body("Escaneie o QR Code da nota fiscal e o app guarda a nota inteira: cada item, o preço, a loja e os descontos.")
          Body("Com o tempo, você vê para onde vai o dinheiro e como os preços do que você compra mudam.")
        }
        1 -> {
          ScreenTitle("Cada nota acrescenta um pouco.")
          Step("Na primeira nota", "Seus gastos por categoria e por loja.")
          Step("Quando comprar algo de novo", "O histórico de preço daquele produto, loja a loja.")
          Step("Quando comprar de novo em outro mês", "Sua inflação pessoal, calculada com o que você realmente compra.")
          Body("Notas antigas que ainda estão na carteira também valem.")
        }
        else -> {
          ScreenTitle("Antes de começar")
          Step("Notas de Santa Catarina", "Por enquanto o app lê a NFC-e de SC, a nota de consumidor com QR Code.")
          Step("Sem conta", "Tudo funciona no celular. Com uma conta, os itens ganham categoria sozinhos e as notas ficam salvas online.")
          Step("As fotos ficam com você", "As fotos da nota ficam no celular. O CPF e o nome do comprador não são guardados.")
        }
      }
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (page < 2) Wide("Continuar", ButtonKind.Solid) { page++ }
      else {
        Wide("Escanear minha primeira nota", ButtonKind.Solid, onScan)
        Wide("Ver um exemplo", ButtonKind.Outline, onDemo)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          TextLink("Explorar o app", onExplore)
          TextLink("Já tenho conta", onSignIn)
        }
      }
    }
  }
}

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge, color = Calmo.fg)

@Composable
internal fun Step(title: String, text: String) {
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Box(Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(Calmo.acc))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall, color = Calmo.fg)
      Text(text, style = MaterialTheme.typography.bodyMedium, color = Calmo.mute)
    }
  }
}

@Composable
private fun Dots(selected: Int, count: Int) {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    repeat(count) { i ->
      Box(Modifier.height(6.dp).width(if (i == selected) 18.dp else 6.dp).clip(CircleShape)
        .background(if (i == selected) Calmo.fg else Calmo.line))
    }
  }
}
