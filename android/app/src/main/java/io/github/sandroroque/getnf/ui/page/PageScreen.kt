package io.github.sandroroque.getnf.ui.page

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.CurrentNetwork
import io.github.sandroroque.getnf.data.Receipt
import io.github.sandroroque.getnf.sync.SyncWorker
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch

private enum class Status(val text: String) {
  Loading("Opening the SEFAZ page…"),
  Challenge("SEFAZ wants a human check: solve it below."),
  RateLimited("SEFAZ is blocking this network for a few minutes (too many requests). The photos are safe: close this and reopen the receipt from the home screen later."),
  Saved("Page saved ✓ It will upload in the background."),
  Failed("Couldn't read the page. Pull it up again from the home screen."),
}

/**
 * Step 3 of the capture flow: open the QR URL in a WebView, let the user pass a Turnstile if SEFAZ
 * shows one, and save the details page's DOM as soon as it loads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScreen(chave: String, onDone: () -> Unit, onNext: (String) -> Unit) {
  val context = LocalContext.current
  val app = context.applicationContext as GetNFApp
  val store = app.receiptStore
  // The network this visit goes out on: its lookups count against that network's budget (D17).
  val network = remember { CurrentNetwork.of(context) }
  val budgetCheck = rememberBudgetCheck()
  val receipts by store.receipts.collectAsStateWithLifecycle()
  val next = waitingForPage(receipts).firstOrNull { it.chave != chave }
  val scope = rememberCoroutineScope()
  var receipt by remember { mutableStateOf<Receipt?>(null) }
  var status by remember { mutableStateOf(Status.Loading) }
  LaunchedEffect(chave) { receipt = store.get(chave) }
  // Leaving this screen ends the capture, page or not: push whatever we have.
  DisposableEffect(Unit) { onDispose { SyncWorker.enqueue(context) } }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Receipt page") },
        actions = {
          if (status == Status.Saved && next != null) {
            TextButton(onClick = { budgetCheck.request(onFetch = { onNext(next.chave) }, onLater = onDone) }) { Text("Next") }
          }
          TextButton(onClick = onDone) { Text(if (status == Status.Saved) "Done" else "Skip") }
        },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      Surface(color = if (status == Status.Challenge || status == Status.RateLimited) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Text(status.text, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
      }
      if (status == Status.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())

      val url = receipt?.qrUrl
      if (url == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          if (receipt != null) Text("This receipt has no QR URL.")
        }
      } else {
        SefazWebView(
          url = url,
          onLookup = { app.lookupBudget.record(network.key) },
          onKind = { kind ->
            when (kind) {
              SefazPage.Kind.Challenge -> status = Status.Challenge
              SefazPage.Kind.RateLimited -> {
                status = Status.RateLimited
                app.lookupBudget.markBlocked(network.key)
              }
              SefazPage.Kind.Other -> if (status != Status.Saved) status = Status.Loading
              SefazPage.Kind.Details -> {}
            }
          },
          onDetailsHtml = { finalUrl, html ->
            if (html == null) {
              status = Status.Failed
            } else {
              scope.launch {
                store.savePage(chave, finalUrl, html)
                // Items on the phone right away, server or not (D49).
                store.get(chave)?.let { app.receiptParser.parse(it) }
                status = Status.Saved
              }
            }
          },
        )
      }
    }
  }
  budgetCheck.Dialog()
}

@SuppressLint("SetJavaScriptEnabled") // Turnstile and the portal need JS.
@Composable
private fun SefazWebView(url: String, onLookup: () -> Unit, onKind: (SefazPage.Kind) -> Unit, onDetailsHtml: (String, String?) -> Unit) {
  // Requests per host for this visit (fetched vs answered empty by us), logged after each page, so we can see what one
  // visit costs against SEFAZ's per-IP rate limit (docs/sefaz-sc.md).
  val fetched = remember { ConcurrentHashMap<String, AtomicInteger>() }
  val blocked = remember { ConcurrentHashMap<String, AtomicInteger>() }
  fun summary() =
    "fetched: " + fetched.entries.joinToString { "${it.key}=${it.value}" } + "; blocked: " + blocked.entries.joinToString { "${it.key}=${it.value}" }
  var webView by remember { mutableStateOf<WebView?>(null) }
  // Stop the page's JS (timers, Turnstile) while the app isn't visible, instead of relying on Android freezing us.
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, webView) {
    val view = webView
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_STOP -> view?.run { onPause(); pauseTimers() }
        Lifecycle.Event.ON_START -> view?.run { resumeTimers(); onResume() }
        else -> {}
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  AndroidView(
    modifier = Modifier.fillMaxSize(),
    factory = { ctx ->
      WebView(ctx).apply {
        // AndroidView defaults to WRAP_CONTENT, which puts WebView in "size to content" mode: `html { height: 100% }`
        // then resolves to 0 and the SecurityVerify page (Turnstile included) is clipped to nothing.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        webViewClient =
          object : WebViewClient() {
            // WebView often reports the same page as finished twice; save it once.
            var savedUrl: String? = null

            // Set when a page commits (after redirects); read on WebView's network thread.
            @Volatile var pageKind = SefazPage.Kind.Other

            override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
              pageKind = SefazPage.classify(pageUrl)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
              val host = request.url.host ?: "?"
              // Main-frame requests to SEFAZ are what its per-IP limit seems to count: the QR URL and Validar. Redirects
              // don't pass through here, so a 3-hop lookup counts once. (Called on a background thread.)
              if (request.isForMainFrame && SefazPage.isSefaz(request.url.toString())) onLookup()
              if (!request.isForMainFrame && SefazPage.shouldBlock(pageKind, request.url.toString(), request.requestHeaders["Accept"])) {
                blocked.getOrPut(host) { AtomicInteger() }.incrementAndGet()
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
              }
              fetched.getOrPut(host) { AtomicInteger() }.incrementAndGet()
              return null // WebView fetches it as usual
            }

            override fun onPageFinished(view: WebView, pageUrl: String) {
              val kind = SefazPage.classify(pageUrl)
              Log.i("PageScreen", "finished $kind $pageUrl")
              Log.i("PageScreen", "requests this visit so far: " + summary())
              onKind(kind)
              if (kind == SefazPage.Kind.Details && pageUrl != savedUrl) {
                savedUrl = pageUrl
                view.evaluateJavascript(SefazPage.GET_HTML) { result -> onDetailsHtml(pageUrl, SefazPage.decodeJsResult(result)) }
              }
            }
          }
        loadUrl(url)
        webView = this
      }
    },
    onRelease = {
      Log.i("PageScreen", "visit closed; requests " + summary())
      // pauseTimers() is process-wide: undo it so a later WebView isn't born frozen.
      it.resumeTimers()
      it.destroy()
    },
  )
}

/** Receipts still missing their SEFAZ page, oldest first: the order "Next" works through. */
fun waitingForPage(receipts: List<io.github.sandroroque.getnf.data.Receipt>) =
  receipts.filter { it.pageFile == null }.sortedBy { it.capturedAt }
