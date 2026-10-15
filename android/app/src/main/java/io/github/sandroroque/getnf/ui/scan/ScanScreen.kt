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
  else Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
    Text("Permita o uso da câmera para capturar a nota.")
    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Permitir câmera") }
    OutlinedButton(onClick = onCancel) { Text("Cancelar") }
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

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    Box(Modifier.fillMaxSize().then(if (landscape) Modifier.padding(horizontal = 216.dp) else Modifier)) {
    AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
    Canvas(Modifier.fillMaxSize()) {
      if (showMask) {
        val path = Path().apply { fillType = PathFillType.EvenOdd }
        state.maskPaths.forEach { ring ->
          ring.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x.toFloat(), p.y.toFloat()) else path.lineTo(p.x.toFloat(), p.y.toFloat()) }
          path.close()
        }
        drawPath(path, Color(0x4400C8FF))
      }
      state.edges.forEach { edge ->
        drawLine(if (edge.good) Color(0xFF56E28C) else Color(0xFFFFD166),
          Offset(edge.start.x.toFloat(), edge.start.y.toFloat()), Offset(edge.end.x.toFloat(), edge.end.y.toFloat()), 3.dp.toPx())
      }
    }
    }
    Column((if (landscape) Modifier.width(216.dp).align(Alignment.TopStart) else Modifier.fillMaxWidth().align(Alignment.TopCenter)).safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("${state.segmentCount} trecho(s) desta captura", color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = .75f)).padding(8.dp))
      FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = if (landscape) 2 else 4) {
        listOf("Topo" to state.top, "Fundo" to state.bottom, "Cobertura" to state.connected, "QR" to state.qr).forEach { (label, present) ->
          Text("${if (present) "✓" else "○"} $label", color = if (present) Color(0xFF56E28C) else Color.White,
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.background(Color.Black.copy(alpha = .75f)).padding(4.dp))
        }
      }
      if (state.geometryKnown) Text("Formato da nota reconhecido", color = Color.White,
        style = MaterialTheme.typography.labelMedium, modifier = Modifier.background(Color.Black.copy(alpha = .75f)).padding(8.dp))
      if (state.error != null) Text(state.error!!, color = Color(0xFFFFD166), modifier = Modifier.background(Color.Black.copy(alpha = .8f)).padding(8.dp))
      if (state.saving) Text("Salvando captura completa…", color = Color.White, modifier = Modifier.background(Color.Black.copy(alpha = .8f)).padding(8.dp))
      else state.guidance.warnings.forEach { hint ->
        Text(hint.label, color = Color.White, style = MaterialTheme.typography.titleSmall,
          modifier = Modifier.background(Color.Black.copy(alpha = .75f)).padding(8.dp))
      }
    }
    Column((if (landscape) Modifier.width(216.dp).align(Alignment.CenterEnd) else Modifier.fillMaxWidth().align(Alignment.BottomCenter)).safeDrawingPadding().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (landscape) 4.dp else 8.dp)) {
      val controlPadding = PaddingValues(horizontal = if (landscape) 4.dp else 12.dp, vertical = 8.dp)
      val arrow = when (state.guidance.destination) {
        MovementTarget.TOP -> "↑"
        MovementTarget.BOTTOM -> "↓"
        MovementTarget.QR -> "⊕"
        null -> if (state.completed) "✓" else ""
      }
      if (arrow.isNotEmpty()) Text(arrow, color = Color.White, style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.rotate(if (state.guidance.destination == MovementTarget.QR) 0f else state.axisAngle).background(Color.Black.copy(alpha = .7f)).padding(horizontal = 16.dp, vertical = 4.dp))
      if (!landscape) Text("Percorra a nota devagar, mantendo as laterais visíveis.", color = Color.White, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.background(Color.Black.copy(alpha = .75f)).padding(8.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = if (landscape) 2 else 4) {
        TextButton(contentPadding = controlPadding, onClick = { focusOnReceipt() }) { Text("Focar", color = Color.White) }
        TextButton(contentPadding = controlPadding, onClick = {
          if (controller.cameraInfo?.hasFlashUnit() == true) {
            val next = !torch
            val result = controller.enableTorch(next)
            result.addListener({ if (runCatching { result.get() }.isSuccess) torch = next }, ContextCompat.getMainExecutor(context))
          }
        }) { Text(if (torch) "Apagar luz" else "Lanterna", color = Color.White) }
        for (zoom in listOf(1f, 2f)) TextButton(contentPadding = controlPadding, onClick = {
          controller.zoomState.value?.let { range -> controller.setZoomRatio(zoom.coerceIn(range.minZoomRatio, range.maxZoomRatio)) }
        }) { Text("${zoom.toInt()}×", color = Color.White) }
      }
      FlowRow(maxItemsInEachRow = if (landscape) 1 else 2) {
        TextButton(onClick = { showMask = !showMask }) { Text(if (showMask) "Ocultar área analisada" else "Mostrar área analisada", color = Color.White) }
        if (BuildConfig.DEBUG) TextButton(onClick = { model.inspectFrame() }) { Text("Inspecionar frame", color = Color.White) }
      }
      if (!landscape) Text("Toque para focar · Pinça para zoom", color = Color.White, style = MaterialTheme.typography.labelSmall)
      FilledTonalButton(onClick = { exit() }, enabled = !state.saving) { Text("Sair da captura") }
    }
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
  if (showExit) AlertDialog(
    onDismissRequest = { showExit = false; model.setAttached(true) },
    title = { Text("Captura incompleta") },
    text = { Text("O QR foi identificado. As imagens desta tentativa serão descartadas ao sair. Fotos anteriores desta nota serão preservadas.") },
    confirmButton = { TextButton(onClick = { showExit = false; model.exit(ExitChoice.KEEP_CODE) }) { Text("Sair guardando só o código") } },
    dismissButton = {
      Column {
        TextButton(onClick = { showExit = false; model.exit(ExitChoice.DISCARD) }) { Text("Descartar tentativa") }
        TextButton(onClick = { showExit = false; model.setAttached(true) }) { Text("Continuar capturando") }
      }
    },
  )
}
