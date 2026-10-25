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

/** Continuous local capture: quality, coverage and QR run independently. */
@Composable
fun ScanScreen(onCaptured: () -> Unit, onCancel: () -> Unit) {
  val context = LocalContext.current
  var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
  val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
  LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
  if (granted) ContinuousCapture(onCaptured, onCancel)
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
private fun ContinuousCapture(onCaptured: () -> Unit, onCancel: () -> Unit) {
  val context = LocalContext.current
  val model = viewModel { CaptureViewModel(context.applicationContext as android.app.Application) }
  val lifecycle = LocalLifecycleOwner.current
  val state by model.state.collectAsStateWithLifecycle()
  val inspection by model.inspection.collectAsStateWithLifecycle()
  val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  var showMask by rememberSaveable { mutableStateOf(false) }
  var showExit by remember { mutableStateOf(false) }
  var torch by remember { mutableStateOf(false) }
  var showMore by remember { mutableStateOf(false) }
  val currentState by rememberUpdatedState(state)
  val exitDialogVisible by rememberUpdatedState(showExit || inspection != null)
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
  LaunchedEffect(state.completed, state.exited) {
    if (state.completed) onCaptured() else if (state.exited) onCancel()
  }
  fun focusOnReceipt() {
    val points = currentState.edges.flatMap { listOf(it.start, it.end) }
    val x = if (points.isEmpty()) preview.width / 2f else points.map { it.x }.average().toFloat()
    val y = if (points.isEmpty()) preview.height / 2f else points.map { it.y }.average().toFloat()
    if (preview.width > 0 && preview.height > 0) {
      val point = preview.meteringPointFactory.createPoint(x, y)
      controller.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point)
        .setAutoCancelDuration(2, TimeUnit.SECONDS).build())
    }
  }
  // Keep CameraX's continuous autofocus. Repeated AF triggers used to lock/restart it
  // every three seconds; explicit tap/focus requests now expire back to continuous AF.
  fun exit() {
    if (state.saving || state.completed || state.exited) return
    model.setAttached(false)
    if (state.qr) showExit = true else model.exit(ExitChoice.DISCARD)
  }
  BackHandler { exit() }

  // The capture screen in the Calmo look (D81): the camera full-bleed, a thin four-step progress bar, one hint at a
  // time, round controls. Everything drawn on the camera sits on soft scrims so it reads over any background.
  val onCamera = Color.White
  val hint = primaryHint(state.guidance.warnings)
  Box(Modifier.fillMaxSize().background(Color.Black)) {
    Box(Modifier.fillMaxSize().then(if (landscape) Modifier.padding(horizontal = 196.dp) else Modifier)) {
      AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
      Canvas(Modifier.fillMaxSize()) {
        if (showMask) {
          val path = Path().apply { fillType = PathFillType.EvenOdd }
          state.maskPaths.forEach { ring ->
            ring.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x.toFloat(), p.y.toFloat()) else path.lineTo(p.x.toFloat(), p.y.toFloat()) }
            path.close()
          }
          drawPath(path, Color(0x3393B2DA))
        }
        state.edges.forEach { edge ->
          drawLine(if (edge.good) GOOD else PENDING,
            Offset(edge.start.x.toFloat(), edge.start.y.toFloat()), Offset(edge.end.x.toFloat(), edge.end.y.toFloat()), 3.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
      }
    }

    // Top: close, the progress, the flashlight.
    Column(
      (if (landscape) Modifier.width(196.dp).fillMaxHeight().align(Alignment.TopStart) else Modifier.fillMaxWidth().align(Alignment.TopCenter))
        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = .65f), Color.Transparent)))
        .safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        RoundButton(io.github.sandroroque.getnf.ui.calmo.Icons.Close, "Sair da captura", enabled = !state.saving) { exit() }
        Spacer(Modifier.weight(1f))
        if (!landscape) Text("Escaneando nota", color = onCamera, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        RoundButton(io.github.sandroroque.getnf.ui.calmo.Icons.Torch, if (torch) "Apagar a lanterna" else "Acender a lanterna", selected = torch) {
          if (controller.cameraInfo?.hasFlashUnit() == true) {
            val next = !torch
            val result = controller.enableTorch(next)
            result.addListener({ if (runCatching { result.get() }.isSuccess) torch = next }, ContextCompat.getMainExecutor(context))
          }
        }
      }
      Progress(listOf("Topo" to state.top, "Fundo" to state.bottom, "Tudo coberto" to state.connected, "QR Code" to state.qr), vertical = landscape)
      if (state.segmentCount > 0) Text("${state.segmentCount} ${if (state.segmentCount == 1) "trecho salvo" else "trechos salvos"}" +
        if (state.geometryKnown) " · formato reconhecido" else "", color = onCamera.copy(alpha = .75f), style = MaterialTheme.typography.labelMedium)
    }

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
        hint != null -> hint.label
        state.completed -> "Pronto"
        else -> null
      }
      if (message != null) Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        val destination = state.guidance.destination
        // The arrow only when the hint itself is "go up/down": next to "centralize" it would contradict it.
        if (!state.saving && state.error == null && (hint == CaptureHint.TOP || hint == CaptureHint.BOTTOM) && destination != null) Icon(
          io.github.sandroroque.getnf.ui.calmo.Icons.Arrow, null, tint = onCamera,
          modifier = Modifier.size(22.dp).rotate(state.axisAngle + if (destination == MovementTarget.BOTTOM) 180f else 0f),
        )
        Text(message, color = if (state.error != null) PENDING else onCamera,
          style = if (landscape) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium)
      }
      val others = state.guidance.warnings.filter { it != hint && it !in DIRECTIONS }.take(1)
      if (!state.saving) others.forEach { Text(it.label, color = onCamera.copy(alpha = .8f), style = MaterialTheme.typography.bodySmall) }
      if (!landscape && !state.saving) Text("Percorra a nota devagar, com as laterais à vista.", color = onCamera.copy(alpha = .7f),
        style = MaterialTheme.typography.bodySmall)
      // Four in a row in portrait; two by two in landscape's narrower side column.
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
    io.github.sandroroque.getnf.ui.calmo.FactRow("Área analisada", if (showMask) "ocultar" else "mostrar", onClick = { showMask = !showMask; showMore = false })
    if (BuildConfig.DEBUG) io.github.sandroroque.getnf.ui.calmo.FactRow("Depuração", "inspecionar este frame", onClick = { showMore = false; model.inspectFrame() })
    io.github.sandroroque.getnf.ui.calmo.Sub("Toque na tela para focar; pinça para zoom.")
  }
  inspection?.let { bitmap ->
    Dialog(onDismissRequest = { model.closeInspection() }) {
      Column(Modifier.fillMaxWidth().background(Color.Black).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Mesmo frame: bordas amarelas, referência magenta, área analisada azul", color = Color.White)
        Image(bitmap.asImageBitmap(), contentDescription = "Frame analisado com contorno e máscara", modifier = Modifier.fillMaxWidth().weight(1f, fill = false))
        TextButton(onClick = { model.closeInspection() }) { Text("Voltar à câmera") }
      }
    }
  }
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
private val DIRECTIONS = setOf(CaptureHint.TOP, CaptureHint.BOTTOM, CaptureHint.QR)

/**
 * The one hint to show large: what blocks the capture first (another receipt, lost tracking, the receipt off-centre,
 * blur, light), then where to move. The others wait; at most one shows small under it.
 */
internal fun primaryHint(warnings: List<CaptureHint>): CaptureHint? {
  val order = listOf(CaptureHint.OTHER_RECEIPT, CaptureHint.TRACKING, CaptureHint.CENTER, CaptureHint.FOCUS, CaptureHint.LIGHT,
    CaptureHint.HOLD, CaptureHint.TOP, CaptureHint.BOTTOM, CaptureHint.QR)
  return order.firstOrNull { it in warnings }
}

/** Four steps as one thin bar: filled when done, with the names small under it; a short list in landscape. */
@Composable
private fun Progress(steps: List<Pair<String, Boolean>>, vertical: Boolean = false) {
  if (vertical) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      for ((label, done) in steps) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(18.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (done) GOOD else Color.White.copy(alpha = .28f)))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (done) GOOD else Color.White.copy(alpha = .8f))
      }
    }
    return
  }
  Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      for ((_, done) in steps) Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
        .background(if (done) GOOD else Color.White.copy(alpha = .28f)))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      for ((label, done) in steps) Text((if (done) "✓ " else "") + label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
        color = if (done) GOOD else Color.White.copy(alpha = .75f), maxLines = 1)
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
