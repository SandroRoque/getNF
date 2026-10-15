package io.github.sandroroque.getnf.ui.scan

import android.graphics.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrameMappingTest {
  @Test fun allQuarterTurnsMatchSensorCoordinatesWithCropAndZoom() {
    for (cameraTurn in listOf(0, 90, 180, 270)) for (analysisTurn in listOf(0, 90, 180, 270)) {
      val sensorToBuffer = Matrix().apply { setValues(floatArrayOf(.5f, 0f, -100f, 0f, .5f, -50f, 0f, 0f, 1f)) }
      val bufferToSensor = Matrix(); assertTrue(sensorToBuffer.invert(bufferToSensor))
      val sensorToView = Matrix().apply { setValues(floatArrayOf(.8f, 0f, 10f, 0f, .8f, 30f, 0f, 0f, 1f)) }
      val mapping = FrameMapping(640, 480, cameraTurn, bufferToSensor, sensorToView, analysisTurn)
      for (p in listOf(DocumentPoint(.1, .2), DocumentPoint(.7, .8), DocumentPoint(.5, .5))) {
        val observed = ImageCoordinates.rotated(ImageCoordinates.rotated(p, cameraTurn), analysisTurn)
        val output = mapping.map(observed)!!
        assertEquals((p.x * 640 + 100) * 1.6 + 10, output.x, .001)
        assertEquals((p.y * 480 + 50) * 1.6 + 30, output.y, .001)
      }
    }
  }
  @Test fun missingPreviewTransformNeverInventsCoordinates() {
    assertNull(FrameMapping(640, 480, 90, Matrix(), null, 90).map(DocumentPoint(.5, .5)))
  }
}
