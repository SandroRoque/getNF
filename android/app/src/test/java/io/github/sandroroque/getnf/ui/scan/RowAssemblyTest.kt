package io.github.sandroroque.getnf.ui.scan

import org.junit.Assert.*
import org.junit.Test

/** Assembling a receipt's rows from frames that each see part of it (D85), on a synthetic receipt. */
class RowAssemblyTest {
  // A receipt of 40 rows; items repeat a line ("DESCONTO056") as real receipts do.
  private val receipt = (1..40).map { i ->
    when {
      i % 7 == 0 -> "DESCONTO056"
      else -> "%02d7896%07dPRODUTO%dUN%d".format(i, i * 7919, i % 4 + 1, i * 3)
    }
  }

  private fun frame(from: Int, to: Int, confidence: Double = .8, noise: (Int, String) -> String = { _, t -> t }) =
    (from until to).map { Row(noise(it, receipt[it]), confidence, it * 30.0, 20.0) }

  @Test fun overlappingFramesAssembleTheWholeReceipt() {
    val a = RowAssembly()
    assertNotNull(a.add(frame(0, 18), topSeen = true, bottomSeen = false))
    assertNotNull(a.add(frame(12, 30), topSeen = false, bottomSeen = false))
    assertNotNull(a.add(frame(25, 40), topSeen = false, bottomSeen = true))
    assertEquals(receipt, a.rows.map { it.text })
    assertTrue(a.topClosed && a.bottomClosed)
  }

  @Test fun aViewOfTheMiddleJoinsByItsTextAndRepeatedLinesDontMisplaceIt() {
    val a = RowAssembly()
    a.add(frame(0, 20), true, false)
    // Rows 14..24: starts at a repeated "DESCONTO056" (also row 7's text); its neighbours place it.
    val at = a.add(frame(13, 24), false, false)!!
    assertEquals((13 until 24).toList(), at.toList())
    assertEquals(receipt.subList(0, 24), a.rows.map { it.text })
  }

  @Test fun ocrMisreadsStillMatchAndTheSurerReadWins() {
    val a = RowAssembly()
    // The first frame misreads a character in every row; the second reads them right, more surely.
    a.add(frame(0, 15, .7) { _, t -> t.replaceFirst('7', '1') }, true, false)
    a.add(frame(5, 20, .85), false, false)
    assertEquals(receipt.subList(5, 15), a.rows.subList(5, 15).map { it.text })
    assertEquals(20, a.rows.size)
  }

  @Test fun aRowOcrMissedInOneFrameIsFilledInByAnother() {
    val a = RowAssembly()
    a.add(frame(0, 20).filterIndexed { i, _ -> i != 10 }, true, false)
    assertEquals(19, a.rows.size)
    a.add(frame(5, 20), false, false)
    assertEquals(receipt.subList(0, 20), a.rows.map { it.text })
  }

  @Test fun aFalseEndReopensWhenPrintAppearsPastIt() {
    val a = RowAssembly()
    a.add(frame(0, 20), true, bottomSeen = true) // a shadow taken for the bottom edge
    assertTrue(a.bottomClosed)
    a.add(frame(15, 30), false, false)
    assertFalse(a.bottomClosed)
    assertEquals(30, a.rows.size)
  }

  @Test fun aFrameUnrelatedToTheReceiptIsNotPlaced() {
    val a = RowAssembly()
    a.add(frame(0, 20), true, false)
    assertNull(a.add((0 until 10).map { Row("OUTRALOJA%05d".format(it * 31), .8, it * 30.0, 20.0) }, false, false))
  }

  @Test fun twoLinesFusedIntoOneRowAreResolvedByLaterFrames() {
    val a = RowAssembly()
    // One frame reads rows 8 and 9 fused into one box.
    val fusedText = receipt[8].take(10) + receipt[9].take(8)
    val fused = frame(0, 16).toMutableList().apply { this[8] = this[8].copy(text = fusedText); removeAt(9) }
    a.add(fused, true, false)
    a.add(frame(2, 16), false, false)
    a.add(frame(4, 18), false, false)
    // Both real rows are there, and the fused read is gone (re-read as one of them) or doubted.
    assertTrue(receipt.subList(0, 18).all { t -> a.rows.any { it.text == t } })
    assertTrue(a.rows.none { it.text == fusedText && !it.doubtful })
  }

  @Test fun aPieceOfARowIsNotAddedAsAnotherRow() {
    val a = RowAssembly()
    a.add(frame(0, 20), true, false)
    // OCR splits row 10 into two boxes on two "rows": its second half alone.
    val split = frame(5, 20).toMutableList().apply { add(6, Row(receipt[10].takeLast(12), .8, 10.5 * 30, 20.0)) }
    a.add(split, false, false)
    assertEquals(20, a.rows.size)
  }

  @Test fun onlyAFrameReadWellStartsTheAssembly() {
    val a = RowAssembly()
    assertNull(a.add(frame(0, 20, .4), true, true))
    assertTrue(a.rows.isEmpty())
    assertNotNull(a.add(frame(0, 20, .8), true, false))
  }
}
