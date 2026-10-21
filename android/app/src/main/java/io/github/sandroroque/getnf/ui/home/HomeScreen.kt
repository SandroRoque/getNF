package io.github.sandroroque.getnf.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.sandroroque.getnf.data.CurrentNetwork
import io.github.sandroroque.getnf.ui.page.budgetText
import io.github.sandroroque.getnf.ui.page.rememberBudgetCheck
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.Chave
import io.github.sandroroque.getnf.data.ParsedSummary
import io.github.sandroroque.getnf.data.Receipt
import io.github.sandroroque.getnf.theme.GetNFTheme
import io.github.sandroroque.getnf.ui.Format
import io.github.sandroroque.getnf.upload.UploadWorker
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(onScan: () -> Unit, onOpenPage: (String) -> Unit, onOpenReceipt: (String) -> Unit, onSettings: () -> Unit) {
  val context = LocalContext.current
  val app = context.applicationContext as GetNFApp
  val receipts by app.receiptStore.receipts.collectAsStateWithLifecycle()
  val parsed by remember { app.database.catalog().summaries() }.collectAsStateWithLifecycle(emptyList())
  val budgetCheck = rememberBudgetCheck()
  // Re-read the budget every 30 s (slots free up over time; the network may change).
  var budgetLine by remember { mutableStateOf("") }
  LaunchedEffect(Unit) {
    while (true) {
      val network = CurrentNetwork.of(context)
      budgetLine = budgetText(network, app.lookupBudget.status(network.key))
      delay(30_000)
    }
  }
  HomeScreen(
    receipts = receipts,
    parsed = parsed.associateBy { it.chave },
    budgetLine = budgetLine,
    onScan = onScan,
    onOpenPage = { chave -> budgetCheck.request(onFetch = { onOpenPage(chave) }, onLater = {}) },
    onOpenReceipt = onOpenReceipt,
    onSettings = onSettings,
    onUpload = { UploadWorker.enqueue(context) },
  )
  budgetCheck.Dialog()
}

/** Receipts still missing their SEFAZ page, oldest first: the order "Fetch next" works through. */
fun waitingForPage(receipts: List<Receipt>): List<Receipt> = receipts.filter { it.pageFile == null }.sortedBy { it.capturedAt }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
  receipts: List<Receipt>,
  parsed: Map<String, ParsedSummary>,
  budgetLine: String,
  onScan: () -> Unit,
  onOpenPage: (String) -> Unit,
  onOpenReceipt: (String) -> Unit,
  onSettings: () -> Unit,
  onUpload: () -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("getNF") },
        actions = {
          if (receipts.any { it.needsUpload }) TextButton(onClick = onUpload) { Text("Upload") }
          TextButton(onClick = onSettings) { Text("Settings") }
        },
      )
    },
    floatingActionButton = { ExtendedFloatingActionButton(onClick = onScan) { Text("Scan receipt") } },
  ) { padding ->
    if (receipts.isEmpty()) {
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Text("No receipts yet.\nTap “Scan receipt” and point at the QR code.", style = MaterialTheme.typography.bodyLarge)
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        val waiting = waitingForPage(receipts)
        if (waiting.isNotEmpty()) {
          item(key = "waiting") { WaitingBar(waiting.size, budgetLine, onFetchNext = { onOpenPage(waiting.first().chave) }) }
        }
        items(receipts, key = { it.chave }) { receipt ->
          // Without a page, a tap fetches it; with one, it shows what was read. The portal is never hit again.
          ReceiptCard(receipt, parsed[receipt.chave], onClick = {
            if (receipt.pageFile == null) onOpenPage(receipt.chave) else onOpenReceipt(receipt.chave)
          })
        }
      }
    }
  }
}

@Composable
private fun WaitingBar(count: Int, budgetLine: String, onFetchNext: () -> Unit) {
  Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$count receipt(s) waiting for their SEFAZ page", style = MaterialTheme.typography.titleSmall)
        Text(budgetLine, style = MaterialTheme.typography.bodySmall)
      }
      Button(onClick = onFetchNext) { Text("Fetch next") }
    }
  }
}

@Composable
private fun ReceiptCard(receipt: Receipt, parsed: ParsedSummary?, onClick: (() -> Unit)?) {
  Card(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(receipt.capturedAt)),
        style = MaterialTheme.typography.titleSmall,
      )
      Text(Chave.pretty(receipt.chave), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
      val page = if (receipt.pageFile != null) "page saved" else "no page yet: tap to open"
      val upload =
        when {
          !receipt.needsUpload -> "uploaded"
          receipt.uploadError != null -> receipt.uploadError
          else -> "upload pending"
        }
      Text("${receipt.photos.size} photo(s) · $page · $upload", style = MaterialTheme.typography.bodyMedium)
      parsed?.let { ParsedLine(it) }
    }
  }
}

/** What was read from the page: items and amount paid, or why it couldn't be read (D49). */
@Composable
private fun ParsedLine(p: ParsedSummary) {
  // Warnings come with lines; an error without lines means the page couldn't be read at all.
  if (p.lines == 0 && p.parseError != null) {
    Text("Couldn't read the page: ${p.parseError}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    return
  }
  val paid = p.paidCents?.let { " · " + Format.brl(it) } ?: ""
  Text("${p.lines} item(s)$paid", style = MaterialTheme.typography.bodyMedium)
  p.parseError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
  GetNFTheme {
    HomeScreen(
      receipts =
        listOf(
          Receipt(chave = "42260611222333000181651090000012341123456789", capturedAt = 0, photos = listOf("a.jpg"), version = 2)
        ),
      parsed = mapOf(
        "42260611222333000181651090000012341123456789" to
          ParsedSummary("42260611222333000181651090000012341123456789", 8, 3064, null, "phone"),
      ),
      budgetLine = "4 of 5 SEFAZ lookups left this hour on Wi-Fi.",
      onScan = {},
      onOpenPage = {},
      onOpenReceipt = {},
      onSettings = {},
      onUpload = {},
    )
  }
}
