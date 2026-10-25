package io.github.sandroroque.getnf.ui.receipt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.LineDetail
import io.github.sandroroque.getnf.data.PhotoDiscountReader
import io.github.sandroroque.getnf.data.ReceiptParser
import io.github.sandroroque.getnf.discounts.PhotoDiscounts
import io.github.sandroroque.getnf.sync.SyncWorker
import io.github.sandroroque.getnf.theme.Calmo
import io.github.sandroroque.getnf.ui.Format
import io.github.sandroroque.getnf.ui.calmo.AmountRow
import io.github.sandroroque.getnf.ui.calmo.BackHeader
import io.github.sandroroque.getnf.ui.calmo.ButtonKind
import io.github.sandroroque.getnf.ui.calmo.EmptyState
import io.github.sandroroque.getnf.ui.calmo.FactRow
import io.github.sandroroque.getnf.ui.calmo.Mark
import io.github.sandroroque.getnf.ui.calmo.MidNumber
import io.github.sandroroque.getnf.ui.calmo.Pill
import io.github.sandroroque.getnf.ui.calmo.ScreenTitle
import io.github.sandroroque.getnf.ui.calmo.Sub
import io.github.sandroroque.getnf.ui.catalog.CalmoSheet
import io.github.sandroroque.getnf.ui.catalog.ProductPickerDialog
import io.github.sandroroque.getnf.ui.catalog.rememberEdit
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * A receipt as you'd read it (D81): the store, when, the amount paid, and its lines. Tapping a line opens a sheet to
 * correct its discount or which product it is. "Mais" holds reading the photos' discounts and re-reading the page.
 */
@Composable
fun ReceiptScreen(chave: String, onOpenProduct: (String) -> Unit, onBack: () -> Unit = {}) {
  val app = LocalContext.current.applicationContext as GetNFApp
  val header by remember(chave) { app.database.catalog().header(chave) }.collectAsStateWithLifecycle(null)
  val lines by remember(chave) { app.database.catalog().lineDetails(chave) }.collectAsStateWithLifecycle(emptyList())
  val receipts by app.receiptStore.receipts.collectAsStateWithLifecycle()
  val photos = receipts.firstOrNull { it.chave == chave }?.photos?.size ?: 0
  val scope = rememberCoroutineScope()
  var status by remember { mutableStateOf<String?>(null) }
  var editing by remember { mutableStateOf<LineDetail?>(null) }
  var more by remember { mutableStateOf(false) }
  val h = header

  LazyColumn(
    Modifier.fillMaxSize().statusBarsPadding(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      BackHeader("Voltar", onBack) {
        Text("Mais", style = MaterialTheme.typography.bodyMedium, color = Calmo.acc, modifier = Modifier.clickable { more = true }.padding(8.dp))
      }
    }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ScreenTitle(h?.issuerName ?: "Nota")
        Sub(listOfNotNull(
          h?.issuedAt?.let { DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT, java.util.Locale.forLanguageTag("pt-BR")).format(Date(it)) },
          Format.count(lines.size, "item", "itens"),
          photos.takeIf { it > 0 }?.let { Format.count(it, "foto", "fotos") },
        ).joinToString(" · "))
      }
    }
    if (h == null) item { EmptyState("A página desta nota ainda não foi lida.") } else item {
      Row(verticalAlignment = Alignment.Bottom) {
        MidNumber(h.paidCents?.let(Format::brl) ?: "?", Modifier.weight(1f))
        if ((h.discountCents ?: 0) != 0L) Sub("${Format.brl(h.grossCents ?: 0)} − ${Format.brl(h.discountCents ?: 0)}")
      }
    }
    status?.let { item { Sub(it) } }
    h?.parseError?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = Calmo.up) } }
    items(lines, key = { it.lineNo }) { l ->
      AmountRow(
        title = l.productName ?: l.description,
        subtitle = "${Format.e4(l.qtyE4)} ${l.unit.orEmpty().lowercase()} × ${Format.brl(Math.round(l.unitPriceE4 / 100.0))}" +
          (if (l.discountCents != 0L) " · −${Format.brl(l.discountCents)} ${discountSource(l.discountSource)}" else "") +
          (if (l.override) " · outro produto" else ""),
        amount = Format.brl(l.grossCents - l.discountCents),
        onClick = { editing = l },
      )
    }
  }

  if (more) CalmoSheet("Esta nota", onDismiss = { more = false }) {
    Column {
      if ((h?.discountCents ?: 0) > 0 && photos > 0) FactRow("Descontos", "ler das $photos fotos", onClick = {
        more = false; status = "Lendo $photos foto(s)…"
        scope.launch {
          status = runCatching { app.receiptStore.get(chave)?.let { app.photoDiscounts.read(it) } }.fold(
            onSuccess = { it?.let(::discountMessage) ?: "Nota não encontrada" },
            onFailure = { "Não deu para ler as fotos: ${it.message}" },
          )
        }
      })
      FactRow("Página", "ler de novo (sem consultar a SEFAZ)", onClick = {
        more = false
        scope.launch {
          status = when (val r = app.receiptStore.get(chave)?.let { app.receiptParser.parse(it) }) {
            is ReceiptParser.Outcome.Parsed -> "Lida de novo: ${r.lines} linha(s)" + if (r.warnings.isEmpty()) "" else ", ${r.warnings.size} aviso(s)"
            is ReceiptParser.Outcome.Failed -> "Não deu para ler a página: ${r.error}"
            null -> "Sem página salva"
          }
        }
      })
      FactRow("Chave", io.github.sandroroque.getnf.data.Chave.pretty(chave))
    }
  }
  editing?.let { line -> LineSheet(chave, line, onOpenProduct = { editing = null; onOpenProduct(it) }, onDismiss = { editing = null }) }
}

private fun discountSource(source: String) = when (source) {
  "prorated" -> "rateado"; "photo" -> "da foto"; "manual" -> "à mão"; "html" -> "da página"; else -> source
}

/** What came of reading the photos, in a sentence. */
private fun discountMessage(o: PhotoDiscountReader.Outcome): String {
  val r = o.result
  val found = "${r.discounts.size} desconto(s), ${Format.brl(r.foundCents)} de ${Format.brl(r.totalCents)}"
  return when (r.verdict) {
    PhotoDiscounts.Verdict.COMPLETE -> "De ${o.photos} foto(s): $found. Todos encontrados."
    PhotoDiscounts.Verdict.PARTIAL -> "De ${o.photos} foto(s): $found. O resto continua rateado."
    PhotoDiscounts.Verdict.NONE -> "Nenhum desconto nas ${o.photos} foto(s). Nada mudou."
    PhotoDiscounts.Verdict.CONFLICT -> "As fotos não fecham (${r.problems.first()}). Nada mudou."
  }
}

/**
 * One line's corrections, in a sheet (D81): its discount by hand, back to a share of the receipt's discount, a
 * photo-read discount confirmed; or, for this line only, another product (a code read wrong, say).
 */
@Composable
private fun LineSheet(chave: String, l: LineDetail, onOpenProduct: (String) -> Unit, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val app = context.applicationContext as GetNFApp
  val edit = rememberEdit()
  val products by remember { app.database.edit().products() }.collectAsStateWithLifecycle(emptyList())
  var amount by remember { mutableStateOf(Format.brl(l.discountCents).replace("R$", "").trim()) }
  var pickProduct by remember { mutableStateOf(false) }
  val cents = amount.replace(".", "").replace(",", ".").trim().toBigDecimalOrNull()?.movePointRight(2)?.toLong()
  val done = { SyncWorker.enqueue(context); onDismiss() }
  CalmoSheet(l.productName ?: l.description, onDismiss) {
    Sub("${Format.e4(l.qtyE4)} ${l.unit.orEmpty().lowercase()} × ${Format.brl(Math.round(l.unitPriceE4 / 100.0))} na nota · ${Format.brl(l.grossCents)}")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      OutlinedTextField(amount, { amount = it }, label = { Text("Desconto (R$)") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
      Mark(discountSource(l.discountSource))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (l.discountSource == "photo" && l.discountReview != "confirmed" && cents == l.discountCents)
        Pill("Confere", ButtonKind.Solid) { edit { app.catalogEditor.confirmDiscount(chave, l.lineNo); done() } }
      if (cents != null && cents != l.discountCents)
        Pill("Salvar", ButtonKind.Solid, enabled = cents in 0..l.grossCents) { edit { app.catalogEditor.setDiscount(chave, l.lineNo, cents); done() } }
      if (l.discountSource == "manual" || l.discountSource == "photo")
        Pill("Usar rateio", ButtonKind.Ghost) { edit { app.catalogEditor.prorateAgain(chave, l.lineNo); done() } }
    }
    Column {
      FactRow("Produto", "abrir", onClick = { onOpenProduct(l.productId) })
      FactRow("Esta linha", "é outro produto", onClick = { pickProduct = true })
      if (l.override) FactRow("Esta linha", "voltar ao produto do código", onClick = { edit { app.catalogEditor.overrideProduct(chave, l.lineNo, null); done() } })
    }
  }
  if (pickProduct) ProductPickerDialog("Esta linha é…", products.filter { it.productId != l.productId },
    onPick = { id -> pickProduct = false; edit { app.catalogEditor.overrideProduct(chave, l.lineNo, id); done() } },
    onDismiss = { pickProduct = false })
}
