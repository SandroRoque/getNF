package io.github.sandroroque.getnf.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.Chave
import io.github.sandroroque.getnf.ui.page.rememberBudgetCheck
import io.github.sandroroque.getnf.upload.UploadWorker
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

/**
 * Step 1 + 2 of the capture flow (docs/decisions.md D3): find the QR code, then take full-resolution photos.
 * Calls [onDone] with the chave once the user is finished. Receipts whose page is already saved skip the SEFAZ step
 * (each lookup counts against a small per-IP limit, docs/sefaz-sc.md): [onAlreadySaved] instead.
 */
@Composable
fun ScanScreen(onDone: (String) -> Unit, onAlreadySaved: () -> Unit, onCancel: () -> Unit) {
  val context = LocalContext.current
  var granted by remember {
    mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
  }
  // Runtime permission: Android shows its own "Allow getNF to take pictures?" dialog.
  val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
  LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

  if (granted) {
    CameraCapture(onDone, onAlreadySaved, onCancel)
  } else {
    Column(
      Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text("getNF needs the camera to read the receipt's QR code and photograph it.")
      Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
      OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
  }
}

@Composable
private fun CameraCapture(onDone: (String) -> Unit, onAlreadySaved: () -> Unit, onCancel: () -> Unit) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val store = (context.applicationContext as GetNFApp).receiptStore
  val scope = rememberCoroutineScope()

  // Survive rotation: the scanned QR is kept; the photo count is re-read from disk.
  var chave by rememberSaveable { mutableStateOf<String?>(null) }
  var qrUrl by rememberSaveable { mutableStateOf<String?>(null) }
  var ready by remember { mutableStateOf(false) }
  var photoCount by remember { mutableIntStateOf(0) }
  var pageSaved by remember { mutableStateOf(false) }
  val budgetCheck = rememberBudgetCheck()
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  val controller = remember {
    LifecycleCameraController(context).apply {
      cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
      setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
      imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
      imageCaptureResolutionSelector =
        ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build()
    }
  }
  val scanner = remember {
    BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
  }
  val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
  DisposableEffect(lifecycleOwner) {
    controller.bindToLifecycle(lifecycleOwner)
    onDispose {
      controller.unbind()
      scanner.close()
      analysisExecutor.shutdown()
    }
  }

  // Scan frames until an NFC-e QR code is read, then stop analysing (saves battery).
  LaunchedEffect(chave) {
    val found = chave
    if (found == null) {
      val main = ContextCompat.getMainExecutor(context)
      controller.setImageAnalysisAnalyzer(
        analysisExecutor,
        MlKitAnalyzer(listOf(scanner), ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL, main) { result ->
          if (chave != null) return@MlKitAnalyzer
          val hit = result.getValue(scanner).orEmpty().firstNotNullOfOrNull { b ->
            b.rawValue?.let { raw -> Chave.fromQr(raw)?.let { it to raw } }
          }
          if (hit != null) {
            Log.i("ScanScreen", "QR: ${hit.second}")
            qrUrl = hit.second
            chave = hit.first
          }
        },
      )
    } else {
      controller.clearImageAnalysisAnalyzer()
      val receipt = store.open(found, qrUrl)
      photoCount = receipt.photos.size
      pageSaved = receipt.pageFile != null
      ready = true
    }
  }

  fun takePhoto() {
    val c = chave ?: return
    val file = store.newPhotoFile(c)
    busy = true
    error = null
    controller.takePicture(
      ImageCapture.OutputFileOptions.Builder(file).build(),
      ContextCompat.getMainExecutor(context),
      object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
          scope.launch {
            store.addPhoto(c, file)
            photoCount++
            busy = false
          }
        }

        override fun onError(exception: ImageCaptureException) {
          Log.w("ScanScreen", "photo failed", exception)
          error = "Photo failed: ${exception.message}"
          busy = false
        }
      },
    )
  }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    AndroidView(
      factory = { ctx ->
        PreviewView(ctx).apply {
          this.controller = controller
          scaleType = PreviewView.ScaleType.FIT_CENTER
        }
      },
      modifier = Modifier.fillMaxSize(),
    )

    val status =
      when {
        error != null -> error!!
        chave == null -> "Point at the receipt's QR code"
        pageSaved -> "Already saved, page included ✓ · $photoCount photo(s)\nAdd photos if you want, then tap Done"
        photoCount == 0 -> "QR code read ✓\nNow photograph the whole receipt"
        else -> "QR code read ✓ · $photoCount photo(s)\nTake more if the receipt is long, then tap Next"
      }
    Column(Modifier.fillMaxWidth().safeDrawingPadding().padding(16.dp).align(Alignment.TopCenter)) {
      Text(
        status,
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.medium).padding(12.dp),
      )
      chave?.let {
        Text(
          Chave.pretty(it),
          color = Color.White,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(top = 4.dp).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp),
        )
      }
    }

    Row(
      Modifier.fillMaxWidth().safeDrawingPadding().padding(16.dp).align(Alignment.BottomCenter),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Once the QR is read the receipt exists: leaving keeps the photos, and the page can be fetched later from home.
      FilledTonalButton(
        onClick = {
          if (ready) UploadWorker.enqueue(context)
          onCancel()
        }
      ) {
        Text(if (ready) "Later" else "Cancel")
      }
      Button(onClick = ::takePhoto, enabled = ready && !busy) { Text(if (busy) "Saving…" else "Take photo") }
      FilledTonalButton(
        onClick = {
          if (pageSaved) {
            UploadWorker.enqueue(context) // new photos, if any
            onAlreadySaved()
          } else {
            val c = chave ?: return@FilledTonalButton
            budgetCheck.request(onFetch = { onDone(c) }, onLater = { UploadWorker.enqueue(context); onCancel() })
          }
        },
        enabled = ready && !busy,
      ) {
        Text(if (pageSaved) "Done" else "Next")
      }
    }
    budgetCheck.Dialog()
  }
}
