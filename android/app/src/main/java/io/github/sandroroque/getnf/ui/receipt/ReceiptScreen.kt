package io.github.sandroroque.getnf.ui.receipt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
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
 * Before its page is fetched, it says what's missing and offers to fetch it, and shows the photos the scan took (D100).
 */
@Composable
fun ReceiptScreen(chave: String, onOpenProduct: (String) -> Unit, onBack: () -> Unit = {}, onOpenPage: (String) -> Unit = {}) {
  val app = LocalContext.current.applicationContext as GetNFApp
  val header by remember(chave) { app.database.catalog().header(chave) }.collectAsStateWithLifecycle(null)
  val lines by remember(chave) { app.database.catalog().lineDetails(chave) }.collectAsStateWithLifecycle(emptyList())
  val scannedBy by remember(chave) { app.database.lists().scannedBy(chave) }.collectAsStateWithLifecycle(null)
  val receipts by app.receiptStore.receipts.collectAsStateWithLifecycle()
  val receipt = receipts.firstOrNull { it.chave == chave }
  val photos = receipt?.photos?.size ?: 0
  if (receipt != null && receipt.pageFile == null) {
    WaitingForPage(chave, receipt.photos, onBack, onOpenPage)
    return
  }
  val scope = rememberCoroutineScope()
  var status by remember { mutableStateOf<String?>(null) }
  var editing by remember { mutableStateOf<LineDetail?>(null) }
  var more by remember { mutableStateOf(false) }
  val h = header
  // The app couldn't read this receipt's discounts from its photos: once, ask whether they may help improve it (D103).
  var offerPhotos by remember { mutableStateOf(false) }
  val failed = io.github.sandroroque.getnf.data.PhotoContribution.isFailure(h?.discountCents ?: 0, photos, lines.map { it.discountSource })
  androidx.compose.runtime.LaunchedEffect(failed) {
    val c = app.photoContribution
    if (failed && !c.enabled && !c.asked) { c.asked = true; offerPhotos = true }
  }
  if (offerPhotos) androidx.compose.material3.AlertDialog(
    onDismissRequest = { offerPhotos = false },
    title = { Text("Não consegui ler os descontos") },
    text = { Text("Posso enviar as fotos desta nota para melhorar o app? O número do cartão e o CPF vão cobertos. Dá para mudar depois em Ajustes.") },
    confirmButton = { androidx.compose.material3.TextButton(onClick = { app.photoContribution.enabled = true; offerPhotos = false }) { Text("Enviar") } },
    dismissButton = { androidx.compose.material3.TextButton(onClick = { offerPhotos = false }) { Text("Agora não") } },
  )

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
          scannedBy?.let { "escaneada por $it" },
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

/**
 * A receipt whose page hasn't been fetched (D100): named as the lists name it ("Nota de 16/10/2026", the store), what's
 * missing and why, the button that fetches it (through the lookup-budget check, D17), and the photos the scan took.
 */
@Composable
private fun WaitingForPage(chave: String, photos: List<String>, onBack: () -> Unit, onOpenPage: (String) -> Unit) {
  val context = LocalContext.current
  val app = context.applicationContext as GetNFApp
  val item by remember(chave) { app.database.lists().receipts() }.collectAsStateWithLifecycle(emptyList())
  val listed = item.firstOrNull { it.chave == chave }
  val budgetCheck = io.github.sandroroque.getnf.ui.page.rememberBudgetCheck()
  val budget = remember {
    val network = io.github.sandroroque.getnf.data.CurrentNetwork.of(context)
    io.github.sandroroque.getnf.ui.page.budgetText(network, app.lookupBudget.status(network.key))
  }
  var more by remember { mutableStateOf(false) }
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
        ScreenTitle(listed?.title ?: "Nota")
        Sub(listOfNotNull(listed?.storeName, Format.count(photos.size, "foto", "fotos")).joinToString(" · "))
      }
    }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Falta a página da SEFAZ", style = MaterialTheme.typography.titleMedium)
        Sub("É ela que traz os itens, os valores, a forma de pagamento e os impostos. As fotos já estão salvas; às vezes a SEFAZ pede uma verificação antes de mostrar a página.")
        Sub(budget)
        Pill("Buscar a página", ButtonKind.Solid) { budgetCheck.request(onFetch = { onOpenPage(chave) }, onLater = {}) }
      }
    }
    if (photos.isNotEmpty()) item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Fotos", style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          val segments = app.receiptStore.receipts.value.firstOrNull { it.chave == chave }?.captureSegments.orEmpty()
          items(photos) { name -> PhotoThumb(java.io.File(app.receiptStore.dir(chave), name), segments.find { it.photo == name }?.polygon) }
        }
      }
    }
  }
  if (more) CalmoSheet("Esta nota", onDismiss = { more = false }) {
    Column {
      FactRow("Página", "buscar na SEFAZ", onClick = { more = false; budgetCheck.request(onFetch = { onOpenPage(chave) }, onLater = {}) })
      FactRow("Chave", io.github.sandroroque.getnf.data.Chave.pretty(chave))
    }
  }
  budgetCheck.Dialog()
}

/**
 * A photo, small and readable at a glance: cropped to the receipt's outline when the scan recorded one, turned so the
 * print runs upright (its direction from the photo's kept OCR, D99), decoded at a fraction of its size off the main
 * thread.
 */
@Composable
private fun PhotoThumb(file: java.io.File, polygon: List<List<Double>>?) {
  val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, file) {
    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { thumbnail(file, polygon) }.getOrNull() }
  }
  val b = bitmap
  if (b != null) androidx.compose.foundation.Image(b.asImageBitmap(), contentDescription = "Foto da nota",
    modifier = Modifier.height(280.dp).aspectRatio(b.width.toFloat() / b.height).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
  else androidx.compose.foundation.layout.Box(Modifier.height(280.dp).width(120.dp))
}

private fun thumbnail(file: java.io.File, polygon: List<List<Double>>?): android.graphics.Bitmap? {
  val small = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }) ?: return null
  val crop = polygon?.takeIf { it.size >= 3 }?.let { p ->
    val xs = p.map { it[0] }; val ys = p.map { it[1] }; val m = .02
    android.graphics.Rect(((xs.min() - m).coerceAtLeast(0.0) * small.width).toInt(), ((ys.min() - m).coerceAtLeast(0.0) * small.height).toInt(),
      ((xs.max() + m).coerceAtMost(1.0) * small.width).toInt(), ((ys.max() + m).coerceAtMost(1.0) * small.height).toInt())
  }?.takeIf { it.width() > 8 && it.height() > 8 } ?: android.graphics.Rect(0, 0, small.width, small.height)
  // The print's direction, from the lines' corners in the kept OCR: turned by the nearest quarter so it reads upright.
  val turn = runCatching {
    val text = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
      .decodeFromString<io.github.sandroroque.getnf.discounts.PhotoText>(java.io.File(file.path + PhotoDiscountReader.CACHE_SUFFIX).readText())
    text.printAngle()?.let { -(Math.round(Math.toDegrees(it) / 90.0) * 90).toFloat() } ?: 0f
  }.getOrDefault(0f)
  val out = android.graphics.Bitmap.createBitmap(small, crop.left, crop.top, crop.width(), crop.height(), android.graphics.Matrix().apply { postRotate(turn) }, true)
  if (out !== small) small.recycle()
  return out
}
