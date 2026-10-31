package io.github.sandroroque.getnf.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import io.github.sandroroque.getnf.BuildConfig
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.util.Size
import java.util.concurrent.TimeUnit

/**
 * Continuous local capture: quality, coverage and QR run independently. The same camera looks products up by their
 * barcode (camera plan, steps 2 and 3); [productOnly] opens it for that alone (the Catálogo's shortcut).
 */
@Composable
fun ScanScreen(onCaptured: () -> Unit, onCancel: () -> Unit, onOpenPage: (String) -> Unit, productOnly: Boolean = false,
               onOpenProduct: (String) -> Unit = {}) {
  val context = LocalContext.current
  val onboarding = (context.applicationContext as io.github.sandroroque.getnf.GetNFApp).onboarding
  var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
  // The first time, how the capture goes comes before the camera and its permission (D115).
  var intro by remember { mutableStateOf(!onboarding.scanIntroSeen && !productOnly) }
  val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
  LaunchedEffect(intro) { if (!intro && !granted) permission.launch(Manifest.permission.CAMERA) }
  if (intro) io.github.sandroroque.getnf.ui.onboarding.ScanIntro(onStart = { onboarding.scanIntroSeen = true; intro = false }, onCancel = onCancel)
  else if (granted) ContinuousCapture(onCaptured, onCancel, onOpenPage, productOnly, onOpenProduct)
  else Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
    io.github.sandroroque.getnf.ui.calmo.ScreenTitle("A câmera lê a nota")
    io.github.sandroroque.getnf.ui.calmo.Sub("O getNF usa a câmera para ler o QR Code e fotografar a nota. As fotos ficam no celular e, se você entrar numa conta, numa cópia pequena no servidor.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      io.github.sandroroque.getnf.ui.calmo.Pill("Permitir a câmera", io.github.sandroroque.getnf.ui.calmo.ButtonKind.Solid) { permission.launch(Manifest.permission.CAMERA) }
      io.github.sandroroque.getnf.ui.calmo.Pill("Agora não", io.github.sandroroque.getnf.ui.calmo.ButtonKind.Ghost, onClick = onCancel)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContinuousCapture(onCaptured: () -> Unit, onCancel: () -> Unit, onOpenPage: (String) -> Unit, productOnly: Boolean,
                              onOpenProduct: (String) -> Unit) {
  val context = LocalContext.current
  val model = viewModel { CaptureViewModel(context.applicationContext as android.app.Application, productOnly) }
  val lifecycle = LocalLifecycleOwner.current
  val state by model.state.collectAsStateWithLifecycle()
  val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  var showExit by remember { mutableStateOf(false) }
  var torch by remember { mutableStateOf(false) }
  // Once the person touches the torch, it is theirs: the capture no longer turns it on by itself.
  var torchTouched by remember { mutableStateOf(false) }
  var showMore by remember { mutableStateOf(false) }
  val currentState by rememberUpdatedState(state)
  val exitDialogVisible by rememberUpdatedState(showExit)
  val controller = remember {
    LifecycleCameraController(context).apply {
      cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
      setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
      imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
      isTapToFocusEnabled = true
      isPinchToZoomEnabled = true
      setTapToFocusAutoCancelDuration(2, TimeUnit.SECONDS)
      imageAnalysisResolutionSelector = ResolutionSelector.Builder().setResolutionStrategy(
        ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
      ).build()
    }
  }
  val preview = remember {
    PreviewView(context).apply {
      this.controller = controller
      scaleType = PreviewView.ScaleType.FIT_CENTER
      implementationMode = PreviewView.ImplementationMode.COMPATIBLE
      keepScreenOn = true
    }
  }
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> model.setAttached(!exitDialogVisible)
        Lifecycle.Event.ON_PAUSE -> model.setAttached(false)
        else -> Unit
      }
    }
    lifecycle.lifecycle.addObserver(observer)
    model.setAttached(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    controller.setImageAnalysisAnalyzer(model.executor, model.analyzer)
    controller.bindToLifecycle(lifecycle)
    onDispose {
      model.setAttached(false)
      lifecycle.lifecycle.removeObserver(observer)
      controller.clearImageAnalysisAnalyzer()
      controller.unbind()
      preview.keepScreenOn = false
    }
  }
  // The torch is on from the start (D117): the paper reads better lit, and the button turns it off. The camera's info
  // comes once it's bound, so this waits for it a moment; a camera without a flash is left as it is.
  LaunchedEffect(Unit) {
    repeat(40) {
      if (torch || torchTouched) return@LaunchedEffect
      val info = controller.cameraInfo
      if (info != null) {
        if (info.hasFlashUnit()) {
          val result = controller.enableTorch(true)
          result.addListener({ if (runCatching { result.get() }.isSuccess && !torchTouched) torch = true }, ContextCompat.getMainExecutor(context))
        }
        return@LaunchedEffect
      }
      kotlinx.coroutines.delay(100)
    }
  }
  // Too dark to read the print: the torch comes on by itself, once, unless the person has used it (D93). It isn't
  // turned off by itself: the frames are bright once it is on.
  LaunchedEffect(state.dark) {
    if (state.dark && !torch && !torchTouched && controller.cameraInfo?.hasFlashUnit() == true) {
      val result = controller.enableTorch(true)
      result.addListener({ if (runCatching { result.get() }.isSuccess) torch = true }, ContextCompat.getMainExecutor(context))
    }
  }
  val app = context.applicationContext as io.github.sandroroque.getnf.GetNFApp
  // A product's barcode (camera plan, step 3): looked up in every caderneta on the phone, its answer in a sheet over
  // the camera, which keeps looking. A new code opens it again; the same one, while it stays in view, doesn't.
  val lookup = remember { io.github.sandroroque.getnf.data.ProductLookup.onPhone(app, app.accountSettings, app.database) }
  val openLedger = remember { app.accountSettings.ledgerId() }
  var ledgerNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
  var sheet by remember { mutableStateOf(false) }
  var found by remember { mutableStateOf<io.github.sandroroque.getnf.data.LookupResult?>(null) }
  LaunchedEffect(Unit) {
    ledgerNames = runCatching { io.github.sandroroque.getnf.sync.LedgerService(app.accountSettings).mine().associate { it.id to it.name } }.getOrDefault(emptyMap())
  }
  LaunchedEffect(state.productShown) {
    val code = state.product ?: return@LaunchedEffect
    if (state.productShown == 0) return@LaunchedEffect
    sheet = true; found = null
    found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      runCatching { lookup.find(code) }.getOrElse { io.github.sandroroque.getnf.data.LookupResult.NotAProduct(code) }
    }
  }
  LaunchedEffect(state.completed, state.exited) {
    // Testing the capture (D99): straight home, the page isn't offered.
    if (state.completed && (state.chave == null || app.devSettings.testingCapture)) onCaptured() else if (state.exited) onCancel()
  }
  if (state.completed && state.chave != null && !app.devSettings.testingCapture) {
    Saved(state.chave!!, onFetch = { onOpenPage(state.chave!!) }, onLater = onCaptured)
    return
  }
  fun focusOnReceipt() {
    val points = currentState.outline.orEmpty()
    val x = if (points.isEmpty()) preview.width / 2f else points.map { it.x }.average().toFloat()
    val y = if (points.isEmpty()) preview.height / 2f else points.map { it.y }.average().toFloat()
    if (preview.width > 0 && preview.height > 0) {
      val point = preview.meteringPointFactory.createPoint(x, y)
      controller.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point)
        .setAutoCancelDuration(2, TimeUnit.SECONDS).build())
    }
  }
  fun exit() {
    if (state.saving || state.completed || state.exited) return
    model.setAttached(false)
    if (state.qr) showExit = true else model.exit(ExitChoice.DISCARD)
  }
  BackHandler { exit() }

  // The capture screen in the Calmo look (D81), planned live (D85): the camera full-bleed with the paper found
  // outlined, one hint at a time with an arrow along the receipt, and the receipt's map at the side: what is captured,
  // what is left, where the camera is, and whether its ends have been seen.
  val onCamera = Color.White
  Box(Modifier.fillMaxSize().background(Color.Black)) {
    Box(Modifier.fillMaxSize().then(if (landscape) Modifier.padding(horizontal = 196.dp) else Modifier)) {
      AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
      Canvas(Modifier.fillMaxSize()) {
        state.outline?.let { corners ->
          val path = Path().apply {
            corners.forEachIndexed { i, p -> if (i == 0) moveTo(p.x.toFloat(), p.y.toFloat()) else lineTo(p.x.toFloat(), p.y.toFloat()) }
            close()
          }
          drawPath(path, if (state.hint == CaptureHint.CLOSER || state.hint == CaptureHint.HOLD_STILL) PENDING else GOOD,
            style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
      }
    }

    // Top: close, the title, the flashlight.
    Column(
      (if (landscape) Modifier.width(196.dp).fillMaxHeight().align(Alignment.TopStart) else Modifier.fillMaxWidth().align(Alignment.TopCenter))
        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = .65f), Color.Transparent)))
        .safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        RoundButton(io.github.sandroroque.getnf.ui.calmo.Icons.Close, "Sair da captura", enabled = !state.saving) { exit() }
        Spacer(Modifier.weight(1f))
        if (!landscape) Text(when { productOnly -> "Buscar produto"; state.mode == CameraMode.PRODUCT -> "Produto"; else -> "Escaneando nota" },
          color = onCamera, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        RoundButton(io.github.sandroroque.getnf.ui.calmo.Icons.Torch, if (torch) "Apagar a lanterna" else "Acender a lanterna", selected = torch) {
          if (controller.cameraInfo?.hasFlashUnit() == true) {
            torchTouched = true
            val next = !torch
            val result = controller.enableTorch(next)
            result.addListener({ if (runCatching { result.get() }.isSuccess) torch = next }, ContextCompat.getMainExecutor(context))
          }
        }
      }
    }

    // The receipt's map, at the side.
    ReceiptMap(state, Modifier.align(if (landscape) Alignment.CenterStart else Alignment.CenterEnd)
      .padding(horizontal = if (landscape) 24.dp else 12.dp).width(56.dp).fillMaxHeight(if (landscape) .62f else .46f))

    // Bottom: the one thing to do now, then the controls.
    Column(
      (if (landscape) Modifier.width(196.dp).fillMaxHeight().align(Alignment.CenterEnd) else Modifier.fillMaxWidth().align(Alignment.BottomCenter))
        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .7f))))
        .safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
    ) {
      val message = when {
        state.saving -> "Salvando a captura…"
        state.error != null -> state.error!!
        else -> state.hint.label
      }
      Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        val down = state.down
        // The arrow points along the receipt, the way to go: down it, or back up it.
        if (!state.saving && state.error == null && down != null && (state.hint == CaptureHint.MOVE_DOWN || state.hint == CaptureHint.MOVE_UP)) Icon(
          io.github.sandroroque.getnf.ui.calmo.Icons.Arrow, null, tint = onCamera,
          modifier = Modifier.size(22.dp).rotate(down + if (state.hint == CaptureHint.MOVE_DOWN) 180f else 0f),
        )
        Text(message, color = if (state.error != null) PENDING else onCamera,
          style = if (landscape) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium)
      }
      if (!state.saving && state.rows.isNotEmpty()) Text(
        "${state.rows.count { it.captured }} de ${state.rows.size} linhas lidas · ${state.saved} ${if (state.saved == 1) "foto" else "fotos"}" +
          if (state.qr) " · QR Code lido" else "",
        color = onCamera.copy(alpha = .75f), style = MaterialTheme.typography.labelMedium)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = if (landscape) 2 else 4) {
        RoundButton(io.github.sandroroque.getnf.ui.calmo.Icons.Focus, "Focar na nota") { focusOnReceipt() }
        for (zoom in listOf(1f, 2f)) RoundLabel("${zoom.toInt()}×") {
          controller.zoomState.value?.let { range -> controller.setZoomRatio(zoom.coerceIn(range.minZoomRatio, range.maxZoomRatio)) }
        }
        RoundButton(io.github.sandroroque.getnf.ui.calmo.Icons.More, "Mais opções") { showMore = true }
      }
    }
  }
  if (showMore) io.github.sandroroque.getnf.ui.catalog.CalmoSheet("Câmera", onDismiss = { showMore = false }) {
    io.github.sandroroque.getnf.ui.calmo.Sub("A captura lê a nota enquanto você a percorre e guarda só as fotos que acrescentam linhas. " +
      "O mapa ao lado mostra o que já foi lido; as pontas ficam firmes quando o topo e o fim da nota aparecem.")
    io.github.sandroroque.getnf.ui.calmo.Sub("Aponte para o código de barras de um produto para ver quanto você pagou por ele, em cada loja, " +
      "nas notas de todas as cadernetas deste celular.")
    io.github.sandroroque.getnf.ui.calmo.Sub("Toque na tela para focar; pinça para zoom.")
  }
  if (sheet) ProductSheet(found, openLedger, ledgerNames, onOpenProduct = { sheet = false; onOpenProduct(it) },
    onDismiss = { sheet = false })
  if (showExit) io.github.sandroroque.getnf.ui.catalog.CalmoSheet("Sair sem terminar?", onDismiss = { showExit = false; model.setAttached(true) }) {
    io.github.sandroroque.getnf.ui.calmo.Sub("O QR Code já foi lido. As imagens desta tentativa são descartadas ao sair; as fotos anteriores desta nota ficam.")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      io.github.sandroroque.getnf.ui.calmo.Pill("Continuar escaneando", io.github.sandroroque.getnf.ui.calmo.ButtonKind.Solid) { showExit = false; model.setAttached(true) }
      io.github.sandroroque.getnf.ui.calmo.Pill("Sair guardando só o código", io.github.sandroroque.getnf.ui.calmo.ButtonKind.Outline) { showExit = false; model.exit(ExitChoice.KEEP_CODE) }
      io.github.sandroroque.getnf.ui.calmo.Pill("Descartar a tentativa", io.github.sandroroque.getnf.ui.calmo.ButtonKind.Ghost) { showExit = false; model.exit(ExitChoice.DISCARD) }
    }
  }
}

private val GOOD = Color(0xFF7CC29A)
private val PENDING = Color(0xFFE8C46A)

/**
 * The receipt so far, as a strip of paper: its rows in order (blank gaps to scale), green where captured, faint where
 * still to read; a bracket where the camera is; its top and bottom drawn as an edge once seen, fading while unknown.
 */
@Composable
private fun ReceiptMap(state: CaptureUiState, modifier: Modifier) {
  if (state.rows.isEmpty()) return
  Canvas(modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = .45f)).padding(horizontal = 9.dp, vertical = 12.dp)) {
    val heights = state.rows.map { 1f + it.gapAfter.toFloat() }
    val unit = size.height / heights.sum()
    val paper = Color.White.copy(alpha = .9f)
    drawRect(paper.copy(alpha = .12f), size = size)
    var y = 0f
    for ((i, row) in state.rows.withIndex()) {
      val h = heights[i] * unit
      drawRect(if (row.captured) GOOD else Color.White.copy(alpha = .28f), Offset(3.dp.toPx(), y + h * .15f),
        androidx.compose.ui.geometry.Size(size.width - 6.dp.toPx(), maxOf(1f, unit * .7f)))
      y += h
    }
    fun edge(at: Float, seen: Boolean) {
      if (seen) drawLine(paper, Offset(0f, at), Offset(size.width, at), 2.dp.toPx())
      else drawLine(paper.copy(alpha = .35f), Offset(0f, at), Offset(size.width, at), 1.dp.toPx(),
        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
    }
    edge(0f, state.topClosed); edge(size.height, state.bottomClosed)
    state.view?.let { v ->
      val top = heights.take(v.first).sum() * unit; val bottom = heights.take(v.last + 1).sum() * unit
      drawRect(Color.White, Offset(-2.dp.toPx(), top), androidx.compose.ui.geometry.Size(size.width + 4.dp.toPx(), bottom - top),
        style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
    }
  }
}

/** A round icon button on the camera. */
@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, selected: Boolean = false,
                        enabled: Boolean = true, onClick: () -> Unit) {
  Box(Modifier.size(48.dp).clip(CircleShape).background(if (selected) Color.White else Color.Black.copy(alpha = .45f))
    .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
    Icon(icon, description, Modifier.size(22.dp), tint = if (selected) Color.Black else Color.White)
  }
}

@Composable
private fun RoundLabel(text: String, onClick: () -> Unit) {
  Box(Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
    Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
  }
}

/**
 * The scan is done (D99): the page is offered now, while the receipt is in hand and the person ready for SEFAZ's
 * check. "Depois" leaves it queued ("Para você", the home list) and asks for a reminder in a few hours.
 */
@Composable
private fun Saved(chave: String, onFetch: () -> Unit, onLater: () -> Unit) {
  val context = LocalContext.current
  val budgetCheck = io.github.sandroroque.getnf.ui.page.rememberBudgetCheck()
  val budget = remember {
    val network = io.github.sandroroque.getnf.data.CurrentNetwork.of(context)
    io.github.sandroroque.getnf.ui.page.budgetText(network, (context.applicationContext as io.github.sandroroque.getnf.GetNFApp).lookupBudget.status(network.key))
  }
  fun remindLater() { io.github.sandroroque.getnf.sync.PageReminderWorker.schedule(context); onLater() }
  val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { remindLater() }
  fun later() {
    if (android.os.Build.VERSION.SDK_INT >= 33 &&
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else remindLater()
  }
  androidx.activity.compose.BackHandler { later() }
  Column(Modifier.fillMaxSize().background(io.github.sandroroque.getnf.theme.Calmo.bg).safeDrawingPadding().padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
    io.github.sandroroque.getnf.ui.calmo.ScreenTitle("Nota salva")
    io.github.sandroroque.getnf.ui.calmo.Sub("As fotos estão no celular. Falta a página da SEFAZ, com os itens e os valores: buscar agora leva um instante, e às vezes a SEFAZ pede uma verificação.")
    io.github.sandroroque.getnf.ui.calmo.Sub(budget)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      io.github.sandroroque.getnf.ui.calmo.Pill("Buscar a página agora", io.github.sandroroque.getnf.ui.calmo.ButtonKind.Solid) {
        budgetCheck.request(onFetch = onFetch, onLater = ::later)
      }
      io.github.sandroroque.getnf.ui.calmo.Pill("Depois", io.github.sandroroque.getnf.ui.calmo.ButtonKind.Ghost, onClick = ::later)
    }
  }
  budgetCheck.Dialog()
}
