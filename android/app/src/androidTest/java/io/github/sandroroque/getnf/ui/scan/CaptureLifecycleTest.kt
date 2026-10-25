package io.github.sandroroque.getnf.ui.scan

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import io.github.sandroroque.getnf.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * Empty-scene emulator with CAMERA pre-granted by adb; no receipt lookup or upload is requested. The controls are the
 * redesigned ones (D81): the bar's scan button, the close and "more" buttons, the camera sheet.
 */
class CaptureLifecycleTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  private fun scan() = compose.onNodeWithContentDescription("Escanear nota").performClick()
  private fun backHome() = compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Escanear nota").fetchSemanticsNodes().isNotEmpty() }
  private fun showMask() {
    compose.onNodeWithContentDescription("Mais opções").performClick()
    compose.onNodeWithText("mostrar").performClick()
  }

  @Test fun landscapeKeepsMaskPreferenceAndControlsVisible() {
    scan()
    showMask()
    try {
      compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
      compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
      compose.onNodeWithContentDescription("Mais opções").assertIsDisplayed().performClick()
      compose.onNodeWithText("ocultar").assertIsDisplayed()
      compose.onNodeWithText("inspecionar este frame").assertIsDisplayed()
      pressBack()
      compose.onNodeWithContentDescription("Sair da captura").assertIsDisplayed()
      pressBack()
      backHome()
    } finally { compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
  }

  @Test fun maskControlAndSameFrameInspectionKeepTheSession() {
    scan()
    showMask()
    compose.onNodeWithContentDescription("Mais opções").performClick()
    compose.onNodeWithText("ocultar").assertIsDisplayed()
    compose.onNodeWithText("inspecionar este frame").performClick()
    compose.waitUntil(15_000) { compose.onAllNodesWithText("Voltar à câmera").fetchSemanticsNodes().isNotEmpty() }
    compose.onNodeWithContentDescription("Frame analisado com contorno e máscara").assertIsDisplayed()
    compose.activityRule.scenario.recreate()
    compose.onNodeWithText("Voltar à câmera").performClick()
    compose.onNodeWithContentDescription("Sair da captura").assertIsDisplayed()
    pressBack()
    backHome()
  }

  @Test fun rotationKeepsCaptureAndReentryStartsANewSession() {
    scan()
    compose.onNodeWithContentDescription("Sair da captura").assertIsDisplayed()
    fun sessions() = File(compose.activity.filesDir, "capture-sessions").listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSet()
    compose.waitUntil(10_000) { sessions().size == 1 }
    val firstSession = sessions()
    compose.activityRule.scenario.recreate()
    compose.onNodeWithContentDescription("Sair da captura").assertIsDisplayed()
    assertEquals(firstSession, sessions())
    pressBack()
    backHome()
    scan()
    compose.onNodeWithContentDescription("Sair da captura").assertIsDisplayed()
    compose.waitUntil(10_000) { sessions().size == 1 && sessions() != firstSession }
    assertNotEquals(firstSession, sessions())
    pressBack()
    backHome()
  }
}
