package io.github.sandroroque.getnf.ui.scan

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import io.github.sandroroque.getnf.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Empty-scene emulator with CAMERA pre-granted by adb; no receipt lookup or upload is requested. */
class CaptureLifecycleTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test fun landscapeKeepsMaskPreferenceAndControlsVisible() {
    compose.onNodeWithText("Scan receipt").performClick()
    compose.onNodeWithText("Mostrar área analisada").performClick()
    try {
      compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
      compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
      compose.onNodeWithText("Ocultar área analisada").assertIsDisplayed()
      compose.onNodeWithText("Inspecionar frame").assertIsDisplayed()
      compose.onNodeWithText("Sair da captura").assertIsDisplayed()
      pressBack()
      compose.waitUntil(10_000) { compose.onAllNodesWithText("Scan receipt").fetchSemanticsNodes().isNotEmpty() }
    } finally { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
  }

  @Test fun maskControlAndSameFrameInspectionKeepTheSession() {
    compose.onNodeWithText("Scan receipt").performClick()
    compose.onNodeWithText("Mostrar área analisada").performClick()
    compose.onNodeWithText("Ocultar área analisada").assertIsDisplayed()
    compose.onNodeWithText("Inspecionar frame").performClick()
    compose.waitUntil(15_000) { compose.onAllNodesWithText("Voltar à câmera").fetchSemanticsNodes().isNotEmpty() }
    compose.onNodeWithContentDescription("Frame analisado com contorno e máscara").assertIsDisplayed()
    compose.activityRule.scenario.recreate()
    compose.onNodeWithText("Voltar à câmera").performClick()
    compose.onNodeWithText("Sair da captura").assertIsDisplayed()
    pressBack()
    compose.waitUntil(10_000) { compose.onAllNodesWithText("Scan receipt").fetchSemanticsNodes().isNotEmpty() }
  }

  @Test fun rotationKeepsCaptureAndReentryStartsANewSession() {
    compose.onNodeWithText("Scan receipt").performClick()
    compose.onNodeWithText("Sair da captura").assertIsDisplayed()
    fun sessions() = File(compose.activity.filesDir, "capture-sessions").listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSet()
    compose.waitUntil(10_000) { sessions().size == 1 }
    val firstSession = sessions()
    compose.activityRule.scenario.recreate()
    compose.onNodeWithText("Sair da captura").assertIsDisplayed()
    assertEquals(firstSession, sessions())
    pressBack()
    compose.waitUntil(10_000) { compose.onAllNodesWithText("Scan receipt").fetchSemanticsNodes().isNotEmpty() }
    compose.onNodeWithText("Scan receipt").performClick()
    compose.onNodeWithText("Sair da captura").assertIsDisplayed()
    compose.waitUntil(10_000) { sessions().size == 1 && sessions() != firstSession }
    assertNotEquals(firstSession, sessions())
    pressBack()
    compose.waitUntil(10_000) { compose.onAllNodesWithText("Scan receipt").fetchSemanticsNodes().isNotEmpty() }
  }
}
