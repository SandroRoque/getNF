package io.github.sandroroque.getnf.ui.receipt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.Chave
import io.github.sandroroque.getnf.data.LineDetail
import io.github.sandroroque.getnf.data.ParsedHeader
import io.github.sandroroque.getnf.data.PhotoDiscountReader
import io.github.sandroroque.getnf.discounts.PhotoDiscounts
import io.github.sandroroque.getnf.data.ReceiptParsedRow
import io.github.sandroroque.getnf.data.ReceiptParser
import io.github.sandroroque.getnf.theme.GetNFTheme
import io.github.sandroroque.getnf.ui.Format
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** What was read from one receipt's saved page: header, totals and lines with their categories (D50). */
@Composable
fun ReceiptScreen(chave: String) {
  val app = LocalContext.current.applicationContext as GetNFApp
  val header by remember(chave) { app.database.catalog().header(chave) }.collectAsStateWithLifecycle(null)
  val lines by remember(chave) { app.database.catalog().lineDetails(chave) }.collectAsStateWithLifecycle(emptyList())
  val receipts by app.receiptStore.receipts.collectAsStateWithLifecycle()
  val photos = receipts.firstOrNull { it.chave == chave }?.photos?.size ?: 0
  val scope = rememberCoroutineScope()
  var status by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  ReceiptScreen(
    chave = chave,
    header = header,
    lines = lines,
    status = status,
    busy = busy,
    photos = photos,
    onReadDiscounts = {
      busy = true; status = "Reading $photos photo(s)…"
      scope.launch {
        status = runCatching { app.receiptStore.get(chave)?.let { app.photoDiscounts.read(it) } }.fold(
          onSuccess = { it?.let(::discountMessage) ?: "No receipt" },
          onFailure = { "Couldn't read the photos: ${it.message}" },
        )
        busy = false
      }
    },
    onReadAgain = {
      busy = true
      scope.launch {
        // Only the saved page is read: SEFAZ isn't contacted.
        status = when (val r = app.receiptStore.get(chave)?.let { app.receiptParser.parse(it) }) {
          is ReceiptParser.Outcome.Parsed -> "Read again: ${r.lines} line(s)" + if (r.warnings.isEmpty()) "" else ", ${r.warnings.size} warning(s)"
          is ReceiptParser.Outcome.Failed -> "Couldn't read the page: ${r.error}"
          null -> "No saved page"
        }
        busy = false
      }
    },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReceiptScreen(
  chave: String,
  header: ParsedHeader?,
  lines: List<LineDetail>,
  status: String?,
  busy: Boolean,
  photos: Int,
  onReadDiscounts: () -> Unit,
  onReadAgain: () -> Unit,
) {
  Scaffold(
    topBar = { TopAppBar(title = { Text(header?.issuerName ?: "Receipt", maxLines = 1, overflow = TextOverflow.Ellipsis) }) },
  ) { padding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item(key = "header") { HeaderCard(chave, header, lines.size, status, busy, photos, onReadDiscounts, onReadAgain) }
      items(lines, key = { it.lineNo }) { line ->
        LineRow(line)
        HorizontalDivider()
      }
    }
  }
}

@Composable
private fun HeaderCard(
  chave: String, h: ParsedHeader?, lineCount: Int, status: String?, busy: Boolean, photos: Int,
  onReadDiscounts: () -> Unit, onReadAgain: () -> Unit,
) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      h?.issuedAt?.let {
        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)), style = MaterialTheme.typography.titleSmall)
      }
      Text(Chave.pretty(chave), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
      if (h == null) {
        Text("Not read yet.", style = MaterialTheme.typography.bodyMedium)
      } else {
        h.grossCents?.let { Amount("Items", it) }
        h.discountCents?.takeIf { it != 0L }?.let { Amount("Discount", it, minus = true) }
        h.paidCents?.let { Amount("Paid", it, strong = true) }
        val by = if (h.source == ReceiptParsedRow.SOURCE_PHONE) "on this phone" else "by the server"
        Text("$lineCount line(s), read $by", style = MaterialTheme.typography.bodySmall)
        h.parseError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
      }
      status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Only worth it when there's a discount to split and photos to read it from (D51).
        if ((h?.discountCents ?: 0) > 0 && photos > 0)
          OutlinedButton(onClick = onReadDiscounts, enabled = !busy) { Text("Discounts from photos") }
        OutlinedButton(onClick = onReadAgain, enabled = !busy) { Text("Read page again") }
      }
    }
  }
}

/** What came of reading the photos, in a sentence. */
private fun discountMessage(o: PhotoDiscountReader.Outcome): String {
  val r = o.result
  val found = "${r.discounts.size} discount(s), ${Format.brl(r.foundCents)} of ${Format.brl(r.totalCents)}"
  return when (r.verdict) {
    PhotoDiscounts.Verdict.COMPLETE -> "From ${o.photos} photo(s): $found. All found."
    PhotoDiscounts.Verdict.PARTIAL -> "From ${o.photos} photo(s): $found. The rest stays prorated."
    PhotoDiscounts.Verdict.NONE -> "No discount found in ${o.photos} photo(s). Nothing changed."
    PhotoDiscounts.Verdict.CONFLICT -> "The photos don't add up (${r.problems.first()}). Nothing changed."
  }
}

@Composable
private fun Amount(label: String, cents: Long, strong: Boolean = false, minus: Boolean = false) {
  val style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
  Row(Modifier.fillMaxWidth()) {
    Text(label, style = style, modifier = Modifier.weight(1f))
    Text((if (minus) "−" else "") + Format.brl(cents), style = style)
  }
}

@Composable
private fun LineRow(l: LineDetail) {
  Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(verticalAlignment = Alignment.Top) {
      Text(l.productName ?: l.description, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Text(Format.brl(l.grossCents - l.discountCents), style = MaterialTheme.typography.bodyLarge)
    }
    val qty = "${Format.e4(l.qtyE4)} ${l.unit.orEmpty()} × ${Format.brl(Math.round(l.unitPriceE4 / 100.0))}".trim()
    val discount = if (l.discountCents != 0L) " · −${Format.brl(l.discountCents)} (${l.discountSource})" else ""
    Text(qty + discount, style = MaterialTheme.typography.bodySmall)
    val category = l.categoryLabel?.let { c -> l.parentLabel?.let { "$it › $c" } ?: c }
    Text(
      category ?: "Uncategorized",
      style = MaterialTheme.typography.bodySmall,
      color = if (category == null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun ReceiptScreenPreview() {
  GetNFTheme {
    ReceiptScreen(
      chave = "42260111222333000181651090000000011123456784",
      header = ParsedHeader("k", "SUPERMERCADO EXEMPLO LTDA", "11222333000181", 1768482000000, 3114, 50, 3064, 1, null, "phone"),
      lines = listOf(
        LineDetail(1, "MACA NAC KG", "243", 6400, "KG", 87800, 562, 9, "prorated", null, "Frutas", "Hortifrúti"),
        LineDetail(2, "POLPA BRASFRUT 100G", "7896014400287", 10000, "UN", 29800, 298, 5, "prorated", null, null, null),
      ),
      status = null,
      busy = false,
      photos = 2,
      onReadDiscounts = {},
      onReadAgain = {},
    )
  }
}
