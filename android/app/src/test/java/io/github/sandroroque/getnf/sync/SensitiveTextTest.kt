package io.github.sandroroque.getnf.sync

import io.github.sandroroque.getnf.discounts.PHOTO_ANGLES
import io.github.sandroroque.getnf.discounts.PhotoText
import io.github.sandroroque.getnf.discounts.TextLine
import io.github.sandroroque.getnf.discounts.turnedBy
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** What the uploaded copy of a photo blanks (D98), on a synthetic receipt laid out like the supermarket's. */
class SensitiveTextTest {
  private fun receipt(vararg texts: String) = PhotoText("p.jpg", 1000, 2000, texts.mapIndexed { i, t -> TextLine(t, 100, 100 + i * 30, 100 + t.length * 12, 125 + i * 30, .9f) })
  private fun blanked(p: PhotoText) = SensitiveText.blanks(p).flatMap { b ->
    p.lines.filter { l -> (l.corners?.chunked(2)?.map { it[0] to it[1] } ?: listOf(l.left to l.top, l.right to l.top, l.right to l.bottom, l.left to l.bottom)).all { (x, y) -> b.contains(x.toDouble(), y.toDouble()) } }.map { it.text }
  }.toSet()

  private val sample = receipt(
    "01 7891000100103 MACA NAC KG 0,875 KG 6,49 5,68",
    "02 7896000000017 REDE DE PROTECAO 1 UN 19,90 19,90",
    "Forma de pagamento Cartao de Credito 25,58",
    "Protocolo de autorizacao: 142260000000001",
    "Data de autorizacao: 15/01/2026 10:00:01",
    "Tributos Totais Incidentes (Lei Federal 12.741/2012) FEDERAL R$ 3,10 ESTADUAL R$ 2,20",
    "Valor 25,58",
    "03 CARTAO CREDITO",
    ":123456789 527 -5",
    "MASTERCARD REDE",
    "411111++e***1111 A 123456",
    "CREDITO A VISTA VALOR 25,58",
    "213072000000000 DOC. 123456789 (SiTef)",
    "DESCONTOS",
    "Voce economizou 0,56",
    "Oper. 900000001",
    "LOJA - 306",
    "CPF do consumidor: 123.456.789-09",
  )

  @Test fun theCardSlipTheOperatorAndACpfAreBlanked() {
    val gone = blanked(sample)
    for (t in listOf("03 CARTAO CREDITO", ":123456789 527 -5", "MASTERCARD REDE", "411111++e***1111 A 123456", "CREDITO A VISTA VALOR 25,58",
      "213072000000000 DOC. 123456789 (SiTef)", "Oper. 900000001", "CPF do consumidor: 123.456.789-09")) assertTrue("$t should be blanked", t in gone)
  }

  /** RJ's print (D161) names the operator; OCR misreads the word, and it may not start the line. */
  @Test fun theOperatorsNameIsBlankedHoweverOcrReadsIt() {
    val p = receipt("Tributos Incid. Lei Federal 12.741/12 - R$ 1,18", "Operadoi : 001234-Fulana de Tal", "0perador: 001234-Fulana de Tal",
      "N:01002 Operador:001234-Fulana", "Atendente: Beltrano",
      "Gpersdor : 001234-Fulana de Tal", "UpeCadar: 001234-Fulana", "perador : 001234-Fulana", "Uperadar : 001234-Fulana de Tal", "Trib Federais: R$ 0,78 (5,66%)")
    val gone = blanked(p)
    for (t in p.lines.map { it.text }.filter { "Fulana" in it || "Beltrano" in it }) assertTrue("$t should be blanked", t in gone)
    assertTrue("taxes stay", "Trib Federais: R$ 0,78 (5,66%)" !in gone)
  }

  @Test fun itemsTotalsTaxesAndSefazsAuthorizationStay() {
    val gone = blanked(sample)
    for (t in listOf("01 7891000100103 MACA NAC KG 0,875 KG 6,49 5,68", "02 7896000000017 REDE DE PROTECAO 1 UN 19,90 19,90",
      "Forma de pagamento Cartao de Credito 25,58", "Protocolo de autorizacao: 142260000000001", "Data de autorizacao: 15/01/2026 10:00:01",
      "Tributos Totais Incidentes (Lei Federal 12.741/2012) FEDERAL R$ 3,10 ESTADUAL R$ 2,20", "DESCONTOS", "LOJA - 306"))
      assertTrue("$t should stay", t !in gone)
  }

  @Test fun aReceiptIsBlankedAlongItsPrintAtAnyAngle() {
    // Upright boxes around turned lines would cover the totals beside the slip; blocks follow the print instead.
    for (angle in PHOTO_ANGLES) assertEquals("at $angle°", blanked(sample), blanked(sample.turnedBy(angle)))
  }

  @Test fun aReceiptWithoutASlipBlanksNothing() {
    assertEquals(emptyList<Blank>(), SensitiveText.blanks(receipt("01 7891000100103 MACA NAC KG 5,68", "Valor total R$ 5,68", "Forma de pagamento Dinheiro 10,00", "Troco 4,32")))
  }

  @Test fun aPhotoWithNoTextReadIsNotUploaded() {
    // Nothing read is nothing checked: no blanks would mean the whole photo goes out as it is (T-8).
    assertThrows(UnreadablePhoto::class.java) { SensitiveText.blanks(PhotoText("p.jpg", 1000, 2000, emptyList())) }
  }

  /** Local only: GETNF_SENSITIVE_DIR = a directory of `*.ocr.json` (real frames); prints what each would blank. */
  @Test fun realFrames() {
    val dir = System.getenv("GETNF_SENSITIVE_DIR")?.let(::File)
    assumeTrue(dir != null)
    val json = Json { ignoreUnknownKeys = true }
    for (f in dir!!.walk().filter { it.name.endsWith(".ocr.json") }.sortedBy { it.path }) {
      val p = json.decodeFromString<PhotoText>(f.readText())
      println("== ${f.parentFile.name}/${f.name}: " + blanked(p).joinToString(" | "))
      // Beside each frame's JPEG, a preview of the copy as it would go up (GETNF_SENSITIVE_OUT).
      val jpg = File(f.path.removeSuffix(".ocr.json")).takeIf { it.isFile } ?: continue
      val out = System.getenv("GETNF_SENSITIVE_OUT")?.let(::File) ?: continue
      val image = javax.imageio.ImageIO.read(jpg)
      val g = image.createGraphics(); g.color = java.awt.Color.BLACK
      for (b in SensitiveText.blanks(p)) g.fillPolygon(b.corners.map { it.first.toInt() }.toIntArray(), b.corners.map { it.second.toInt() }.toIntArray(), b.corners.size)
      g.dispose()
      javax.imageio.ImageIO.write(image, "jpg", File(out.apply { mkdirs() }, "${f.parentFile.name}-${jpg.nameWithoutExtension}.jpg"))
    }
  }

  /**
   * Local only (D161): over every `*.ocr.json` under GETNF_SENSITIVE_DIR, the lines that look private (a CPF, a masked
   * card number, a "CPF" line with digits, the operator) and aren't wholly blanked. Prints each and the totals.
   */
  @Test fun realFramesLeaks() {
    val dir = System.getenv("GETNF_SENSITIVE_DIR")?.let(::File)
    assumeTrue(dir != null)
    val json = Json { ignoreUnknownKeys = true }
    val private = listOf(Regex("\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}"), Regex("\\d{4,6}\\S{0,3}[*+xX#•]{2,}\\S{0,4}\\d{3,4}"),
      Regex("^CPF\\b.*\\d{3}", RegexOption.IGNORE_CASE), Regex("\\b[O0]PER[A-Z0-9]{0,5}\\b", RegexOption.IGNORE_CASE),
      Regex(":\\s*\\d{4,}\\s*-\\s*\\p{L}{3,}"))
    var frames = 0; var seen = 0; var leaked = 0
    for (f in dir!!.walk().filter { it.name.endsWith(".ocr.json") }.sortedBy { it.path }) {
      val p = json.decodeFromString<PhotoText>(f.readText())
      frames++
      val covered = blanked(p)
      for (l in p.lines.filter { l -> private.any { it.containsMatchIn(l.text.trim()) } }) {
        seen++
        if (l.text !in covered) { leaked++; println("LEAK ${f.parentFile.name}/${f.name}: ${l.text}") }
      }
    }
    println("frames $frames, private lines $seen, not blanked $leaked")
  }
}
