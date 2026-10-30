package io.github.sandroroque.getnf.demo

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.sandroroque.getnf.data.BackupArchive
import io.github.sandroroque.getnf.data.BackupManifest
import io.github.sandroroque.getnf.data.CategoryRow
import io.github.sandroroque.getnf.data.Chave
import io.github.sandroroque.getnf.data.ReceiptDatabase
import io.github.sandroroque.getnf.data.ReceiptParser
import io.github.sandroroque.getnf.data.ReceiptStore
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A made-up caderneta for screenshots and the portfolio (D105): six months of a household's shopping at four invented
 * stores, as SEFAZ pages run through the real store and parser, categorized as Jev would, written as a backup zip that
 * Ajustes › Restaurar reads. Nothing in it is real: stores, CNPJs, chaves, brands and codes are invented.
 *
 * Runs only when asked: `GETNF_DEMO_OUT=/path/demo.zip ./gradlew :app:testDebugUnitTest --tests '*DemoCaderneta*' --rerun`.
 * Restore it on the emulator signed out, so it never syncs anywhere.
 */
class DemoCaderneta {
  private class Store(val name: String, base: String, val street: String, val priceFactor: Double) {
    val cnpj = cnpj(base)
  }

  /** [price] in cents per unit or per kg on 2026-05-01; [drift] is the monthly change. */
  private class Item(
    val code: String, val text: String, val unit: String, val price: Long, val path: String, val name: String,
    val drift: Double = .004, val qty: () -> Double = { 1.0 },
  )

  private val rnd = Random(2026)
  private val zone = ZoneId.of("America/Sao_Paulo")
  private val start = LocalDate.of(2026, 5, 1)
  private val end = LocalDate.of(2026, 10, 27)

  private val figueira = Store("MERCADO FIGUEIRA LTDA", "41526374", "RUA DAS FIGUEIRAS, 210", 1.0)
  private val pontaLeste = Store("SUPERMERCADO PONTA LESTE LTDA", "52637485", "AV. BEIRA-MAR LESTE, 1480", .985)
  private val valeVerde = Store("HORTIFRUTI VALE VERDE LTDA", "63748596", "RUA DO ACUDE, 75", .92)
  private val trigoFino = Store("PADARIA TRIGO FINO LTDA", "74859607", "RUA DOS PADEIROS, 33", 1.0)

  private fun kg(from: Double, to: Double): () -> Double = { (from + rnd.nextDouble() * (to - from)).let { Math.round(it * 1000) / 1000.0 } }
  private fun gtin(n: Int): String = "7890000${(10000 + n).toString().takeLast(5)}".let { it + gtinCheck(it) }

  private val produce = listOf(
    Item("1001", "BANANA PRATA KG", "KG", 649, "food.produce.fruit", "Banana prata", qty = kg(.8, 1.6)),
    Item("1002", "MACA GALA KG", "KG", 998, "food.produce.fruit", "Maçã gala", qty = kg(.6, 1.2)),
    Item("1003", "TOMATE KG", "KG", 749, "food.produce.vegetables", "Tomate", drift = .045, qty = kg(.5, 1.0)),
    Item("1004", "CEBOLA KG", "KG", 529, "food.produce.vegetables", "Cebola", qty = kg(.4, .9)),
    Item("1005", "BATATA KG", "KG", 599, "food.produce.vegetables", "Batata", drift = -.01, qty = kg(.8, 1.5)),
    Item("1006", "ALFACE CRESPA UN", "UN", 349, "food.produce.greens_herbs", "Alface crespa"),
    Item("1007", "MAMAO FORMOSA KG", "KG", 798, "food.produce.fruit", "Mamão formosa", qty = kg(1.0, 1.8)),
    Item("1008", "LIMAO TAHITI KG", "KG", 698, "food.produce.fruit", "Limão taiti", qty = kg(.3, .6)),
  )
  private val staples = listOf(
    Item("2001", "FILE PEITO FRANGO KG", "KG", 1890, "food.meat_fish.poultry", "Filé de peito de frango", qty = kg(.8, 1.4)),
    Item("2002", "CARNE MOIDA PATINHO KG", "KG", 4490, "food.meat_fish.beef", "Patinho moído", drift = .015, qty = kg(.4, .7)),
    Item("2003", "LINGUICA TOSCANA KG", "KG", 2490, "food.meat_fish.sausages", "Linguiça toscana", qty = kg(.5, .9)),
    Item(gtin(1), "LEITE INTEGRAL SERRANO 1L", "UN", 489, "food.dairy_eggs.milk", "Leite integral", drift = .02, qty = { 4.0 + rnd.nextInt(5) }),
    Item("2010", "QJO MUSSARELA FATIADO KG", "KG", 5490, "food.dairy_eggs.cheese", "Muçarela fatiada", qty = kg(.2, .35)),
    Item(gtin(2), "IOGURTE NATURAL CAMPINA 170G", "UN", 329, "food.dairy_eggs.yogurt", "Iogurte natural", qty = { 2.0 + rnd.nextInt(3) }),
    Item(gtin(3), "MANTEIGA SERRANO C/SAL 200G", "UN", 1290, "food.dairy_eggs.butter_cream", "Manteiga"),
    Item(gtin(4), "OVOS BRANCOS SOL NASCENTE C/12", "UN", 1190, "food.dairy_eggs.eggs", "Ovos (dúzia)"),
    Item(gtin(5), "PAO DE FORMA TRIGAL 500G", "UN", 849, "food.bakery.bread", "Pão de forma"),
    Item(gtin(6), "FEIJAO PRETO TERRA BOA 1KG", "UN", 879, "food.grains.beans_legumes", "Feijão preto", drift = -.005),
    Item(gtin(7), "MACARRAO ESPAGUETE MASSA FINA 500G", "UN", 459, "food.grains.pasta", "Espaguete"),
    Item(gtin(8), "AVEIA EM FLOCOS GRAOSUL 200G", "UN", 549, "food.grains.flour_cereals", "Aveia em flocos"),
    Item(gtin(9), "MOLHO TOMATE CASEIRO 300G", "UN", 299, "food.pantry.sauces_condiments", "Molho de tomate", qty = { 1.0 + rnd.nextInt(2) }),
    Item(gtin(10), "BISCOITO MAIZENA RECREIO 350G", "UN", 529, "food.snacks_sweets.biscuits", "Biscoito maisena"),
    Item(gtin(11), "CHOCOLATE AO LEITE CACAU SUL 90G", "UN", 699, "food.snacks_sweets.chocolate_candy", "Chocolate ao leite", drift = .025),
    Item(gtin(12), "CAFE TORRADO MOIDO MONTANHA 500G", "UN", 2490, "beverages.coffee", "Café Montanha", drift = .03),
    Item(gtin(13), "AGUA MINERAL FONTE CLARA 1,5L", "UN", 279, "beverages.water", "Água mineral", qty = { 2.0 + rnd.nextInt(4) }),
    Item(gtin(14), "CERVEJA PILSEN BOREAL LT 350ML", "UN", 379, "beverages.alcoholic.beer", "Cerveja pilsen", qty = { 6.0 }),
    Item(gtin(15), "DETERGENTE LIQ BRILHO 500ML", "UN", 269, "household.cleaning", "Detergente"),
    Item(gtin(16), "ESPONJA MULTIUSO C/3", "UN", 549, "household.cleaning", "Esponjas"),
    Item(gtin(17), "SABONETE ERVA DOCE 85G", "UN", 249, "personal_care.hygiene", "Sabonete", qty = { 2.0 + rnd.nextInt(3) }),
  )
  private val monthly = listOf(
    Item(gtin(20), "ARROZ TIPO 1 VALE DO SUL 5KG", "UN", 2690, "food.grains.rice", "Arroz 5 kg", drift = -.008),
    Item(gtin(21), "OLEO SOJA PRIMAVERA 900ML", "UN", 749, "food.pantry.oils_fats", "Óleo de soja", drift = -.01, qty = { 2.0 }),
    Item(gtin(22), "ACUCAR REFINADO DOCE LAR 1KG", "UN", 499, "food.pantry.sugar_sweeteners", "Açúcar refinado"),
    Item(gtin(23), "SAL REFINADO MARINHO 1KG", "UN", 249, "food.pantry.spices_seasonings", "Sal"),
    Item(gtin(24), "AZEITE EXTRA VIRGEM OLIVAL 500ML", "UN", 3490, "food.pantry.oils_fats", "Azeite extravirgem", drift = -.02),
    Item(gtin(25), "ERVA MATE CAMPO VERDE 1KG", "UN", 1990, "beverages.tea_mate", "Erva-mate"),
    Item(gtin(26), "SUCO UVA INTEGRAL SERRA 1L", "UN", 1490, "beverages.juice", "Suco de uva integral"),
    Item(gtin(27), "AGUA SANITARIA LIMPAX 1L", "UN", 449, "household.cleaning", "Água sanitária"),
    Item(gtin(28), "SABAO LIQUIDO LAVA BEM 3L", "UN", 3290, "household.laundry", "Sabão líquido"),
    Item(gtin(29), "PAPEL HIG FOLHA DUPLA MACIO 12UN", "UN", 2190, "household.paper_goods", "Papel higiênico"),
    Item(gtin(30), "PAPEL TOALHA COZINHA C/2", "UN", 799, "household.kitchen_disposables", "Papel-toalha"),
    Item(gtin(31), "CREME DENTAL MENTA 90G", "UN", 499, "personal_care.hygiene", "Creme dental", qty = { 2.0 }),
    Item(gtin(32), "SHAMPOO CABELO LEVE 350ML", "UN", 1690, "personal_care.hygiene", "Xampu"),
    Item(gtin(33), "RACAO GATO ADULTO MIAU 1KG", "UN", 2990, "pets", "Ração de gato", drift = .01),
  )
  private val bakery = listOf(
    Item("31", "PAO FRANCES KG", "KG", 1690, "food.bakery.bread", "Pão francês", drift = .01, qty = kg(.3, .6)),
    Item("32", "BOLO DE FUBA UN", "UN", 1400, "food.bakery.cakes_pastries", "Bolo de fubá"),
    Item("33", "CUCA DE BANANA UN", "UN", 1800, "food.bakery.cakes_pastries", "Cuca de banana"),
    Item("34", "PAO DE QUEIJO KG", "KG", 4990, "food.bakery.cakes_pastries", "Pão de queijo", qty = kg(.15, .3)),
  )
  /** Left for "Para você": a model's guess below the threshold, waiting on the user (code to path and confidence). */
  private val suggested = mapOf(gtin(25) to ("beverages.tea_mate" to .74), "33" to ("food.bakery.cakes_pastries" to .68),
    gtin(33) to ("pets" to .81))
  /** Corrected or confirmed by hand, the rest set by Jev. */
  private val byHand = setOf(gtin(12), "1003", gtin(1), "31")

  private data class Line(val item: Item, val qty: Double, val unitCents: Long) {
    val total: Long get() = (qty * unitCents).roundToLong()
  }

  private fun priceOf(item: Item, store: Store, day: LocalDate): Long {
    val months = (day.toEpochDay() - start.toEpochDay()) / 30.4
    val noise = 1 + (rnd.nextDouble() - .5) * .012
    return (item.price * (1 + item.drift).pow(months) * store.priceFactor * noise).roundToLong().coerceAtLeast(1)
  }

  private fun trip(store: Store, day: LocalDate, items: List<Item>): Triple<Store, LocalDateTime, List<Line>> {
    val at = day.atTime(8 + rnd.nextInt(11), rnd.nextInt(60), rnd.nextInt(60))
    return Triple(store, at, items.map { Line(it, it.qty(), priceOf(it, store, day)) })
  }

  private fun trips(): List<Triple<Store, LocalDateTime, List<Line>>> {
    val out = mutableListOf<Triple<Store, LocalDateTime, List<Line>>>()
    var day = start
    var week = 0
    while (!day.isAfter(end)) {
      when (day.dayOfWeek) {
        DayOfWeek.SATURDAY -> {
          // The weekly shop alternates between the two supermarkets; the monthly items come in the first week.
          val store = if (week % 2 == 0) figueira else pontaLeste
          val firstWeek = day.dayOfMonth <= 7
          val items = staples.filter { rnd.nextDouble() < .55 } +
            (if (firstWeek) monthly.filter { rnd.nextDouble() < .8 } else emptyList()) +
            produce.filter { rnd.nextDouble() < .2 }
          out += trip(store, day, items.distinctBy { it.code })
          week++
        }
        DayOfWeek.WEDNESDAY -> out += trip(valeVerde, day, produce.filter { rnd.nextDouble() < .55 }.ifEmpty { produce.take(2) })
        DayOfWeek.SUNDAY -> out += trip(trigoFino, day, listOf(bakery[0]) + bakery.drop(1).filter { rnd.nextDouble() < .35 })
        else -> if (rnd.nextDouble() < .08) out += trip(trigoFino, day, listOf(bakery[0]))
      }
      day = day.plusDays(1)
    }
    return out
  }

  @Test fun write() = runBlocking {
    val outPath = System.getenv("GETNF_DEMO_OUT")
    assumeTrue("set GETNF_DEMO_OUT to write the demo caderneta", outPath != null)
    val work = File(outPath!!).absoluteFile.parentFile.resolve("demo-work").apply { deleteRecursively(); mkdirs() }
    val dbFile = File(work, "getnf.db")
    val receipts = File(work, "receipts").apply { mkdirs() }
    // A file database on the JVM: Room asks the Context for the path and nothing else (journal mode set, as in tests).
    val context = object : ContextWrapper(null) {
      override fun getDatabasePath(name: String) = File(name)
      override fun getApplicationContext(): Context = this
    }
    val db = Room.databaseBuilder(context, ReceiptDatabase::class.java, dbFile.path).setDriver(BundledSQLiteDriver())
      .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
    var clock = 0L
    val store = ReceiptStore(receipts, db, now = { clock })
    val parser = ReceiptParser(db, receipts, now = { clock }, log = { _, _ -> })
    val dao = db.catalog()

    // The taxonomy as the server seeds it into a new caderneta.
    val ids = mutableMapOf<String, String>()
    taxonomy().forEachIndexed { i, (path, label) ->
      val id = UUID.nameUUIDFromBytes("demo-category|$path".toByteArray()).toString()
      ids[path] = id
      dao.insertCategory(CategoryRow(id, path, label, ids[path.substringBeforeLast('.', "")], i))
    }

    val all = trips()
    all.forEachIndexed { n, (s, at, lines) ->
      val chave = chave(s, at, n + 1)
      clock = at.atZone(zone).toInstant().toEpochMilli() + 3 * 60_000
      store.open(chave, "https://sat.sef.sc.gov.br/nfce/consulta?p=$chave|2|1|1|DEMO")
      clock += 60_000
      store.savePage(chave, "https://sat.sef.sc.gov.br/nfce/consulta?p=$chave|2|1|1|DEMO", page(s, at, n + 1, chave, lines))
      val outcome = parser.parse(store.get(chave)!!)
      check(outcome is ReceiptParser.Outcome.Parsed && outcome.warnings.isEmpty()) { "$chave: $outcome" }
    }

    // Categorized as Jev would, a few corrected by hand, three waiting on the user.
    val items = (produce + staples + monthly + bakery).associateBy { it.code }
    // Store codes are per store, so each store's banana got its own product: linked into one, as the user would.
    val productOf = mutableMapOf<String, String>()
    val merged = mutableListOf<String>()
    for (si in dao.storeItems().sortedBy { it.id }) {
      val keep = productOf.getOrPut(si.code) { si.productId }
      if (si.productId != keep) { merged += si.productId; dao.updateStoreItem(si.copy(productId = keep)) }
    }
    dao.deleteProducts(merged)
    val at = end.atTime(4, 0).atZone(zone).toInstant().toEpochMilli()
    for ((code, item) in items) {
      val p = dao.products().single { it.id == productOf[code] }
      val hint = suggested[code]
      dao.updateProduct(when {
        hint != null -> p.copy(suggestionCategoryId = ids.getValue(hint.first), suggestionConfidence = hint.second,
          suggestionModel = "jev-latest", suggestionAt = at)
        code in byHand -> p.copy(name = item.name, categoryId = ids.getValue(item.path), categorySource = "user",
          categoryReview = "confirmed", categoryAt = at)
        else -> p.copy(name = item.name, categoryId = ids.getValue(item.path), categorySource = "jev", categoryReview = "automatic",
          categoryModel = "jev-latest", categoryConfidence = .9 + rnd.nextDouble() * .09, categoryAt = at)
      })
    }
    val counts = listOf(db.receipts().receipts().size, dao.lines().size, dao.products().size)
    db.close()

    val files = linkedMapOf(BackupArchive.DATABASE to dbFile)
    receipts.listFiles()!!.sorted().forEach { dir -> dir.listFiles()!!.sorted().forEach { files["receipts/${dir.name}/${it.name}"] = it } }
    File(outPath).outputStream().use { out ->
      BackupArchive.write(out, files) { hashes ->
        BackupManifest(appVersion = "demo", schemaVersion = ReceiptDatabase.VERSION, createdAt = at, receipts = counts[0],
          photos = 0, lines = counts[1], products = counts[2], files = hashes)
      }
    }
    work.deleteRecursively()
    println("demo caderneta: ${counts[0]} receipts, ${counts[1]} lines, ${counts[2]} products -> $outPath")
  }

  private fun taxonomy(): List<Pair<String, String>> {
    val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "taxonomy/categories.txt") }.first { it.isFile }
    val stack = mutableListOf<String>()
    return file.readLines().mapNotNull { raw ->
      val line = raw.substringBefore('#').trimEnd()
      if (line.isBlank()) return@mapNotNull null
      val depth = (line.length - line.trimStart().length) / 2
      val (slug, label) = line.trim().split(Regex("\\s{2,}"), limit = 2)
      while (stack.size > depth) stack.removeAt(stack.lastIndex)
      stack += slug
      stack.joinToString(".") to label.trim()
    }
  }

  private fun chave(s: Store, at: LocalDateTime, n: Int): String {
    val first = "42" + at.format(DateTimeFormatter.ofPattern("yyMM")) + s.cnpj + "65" + "001" + n.toString().padStart(9, '0') +
      "1" + (10_000_000 + n * 7919 % 89_999_999).toString().padStart(8, '0')
    return first + Chave.checkDigit(first)
  }

  private fun money(cents: Long) = "%d,%02d".format(cents / 100, cents % 100)
  private fun qty(q: Double) = if (q == Math.floor(q)) q.toLong().toString() else "%.3f".format(q).replace('.', ',').trimEnd('0')

  private fun page(s: Store, at: LocalDateTime, n: Int, chave: String, lines: List<Line>): String {
    val gross = lines.sumOf { it.total }
    val discount = if (s.priceFactor < 1 && gross > 10_000 && rnd.nextDouble() < .3) gross * (1 + rnd.nextInt(3)) / 100 else 0
    val paid = gross - discount
    val payment = listOf("Cartão de Crédito", "Cartão de Débito", "Pagamento Instantâneo (PIX)").random(rnd)
    val stamp = at.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))
    val rows = lines.mapIndexed { i, l ->
      """
      <tr id="Item + ${i + 1}">
        <td valign="top"><span class="txtTit">${l.item.text}</span><span class="RCod">
										(Código:
										${l.item.code}
										)
									</span><br><span class="Rqtd"><strong>Qtde.:</strong>${qty(l.qty)}</span><span class="RUN"><strong>UN: </strong>${l.item.unit}</span><span class="RvlUnit"><strong>Vl. Unit.:</strong>

										${money(l.unitCents)}</span></td>
        <td align="right" valign="top" class="txtTit noWrap">
									Vl. Total
									<br><span class="valor">${money(l.total)}</span></td>
      </tr>"""
    }.joinToString("")
    val c = s.cnpj
    return """<!-- Demo caderneta (D105): invented store, items and chave. -->
<!DOCTYPE html><html><head><meta charset="utf-8"><title>NFCe - Detalhes</title></head><body>
<div id="conteudo">
    <div id="avisos"></div>
    <div class="txtCenter">
      <div id="u20" class="txtTopo">${s.name}</div>
      <div class="text">
								CNPJ: ${c.substring(0, 2)}.${c.substring(2, 5)}.${c.substring(5, 8)}/${c.substring(8, 12)}-${c.substring(12)}</div>
      <div class="text">${s.street} , , CENTRO , FLORIANOPOLIS , SC</div>
    </div>
    <table border="0" align="center" cellpadding="0" cellspacing="0" id="tabResult" data-filter="true">$rows
      </table>
    <div id="totalNota" class="txtRight">
      <div id="linhaTotal"><label>Qtd. total de itens:</label><span class="totalNumb">${lines.size}</span></div>
      <div id="linhaTotal"><label>Valor total R$:</label><span class="totalNumb">${money(gross)}</span></div>
      <div id="linhaTotal"><label>Descontos R$:</label><span class="totalNumb">${money(discount)}</span></div>
      <div id="linhaTotal" class="linhaShade"><label>Valor a pagar R$:</label><span class="totalNumb txtMax">${money(paid)}</span></div>
      <div id="linhaForma"><label>Forma de pagamento:</label><span class="totalNumb txtTitR">Valor pago R$:</span></div>
      <div id="linhaTotal"><label class="tx">
											$payment
										</label><span class="totalNumb">${money(paid)}</span></div>
      <div id="linhaTotal"></div>
    </div>
  </div>
  <div id="infos" class="txtCenter">
    <div data-role="collapsible" data-collapsed="false">
      <h4>Informações gerais da Nota</h4>
      <ul data-role="listview" data-inset="false">
        <li><strong>EMISSÃO NORMAL</strong><br><br><strong>Número: </strong>$n<strong> Série: </strong>1<strong> Emissão: </strong>$stamp
								- Via Consumidor 2
								<br><br><strong>Protocolo de Autorização: </strong>000000000000000       ${stamp.substring(0, 10)}
        às
      ${stamp.substring(11)}<br><br><strong>
										Ambiente de Produção -
									Versão XML:
									4.00
									- Versão XSLT: 2.07
								</strong></li>
      </ul>
    </div>
    <div data-role="collapsible" data-collapsed="false">
      <h4>Chave de acesso</h4>
      <ul data-role="listview" data-inset="false">
        <li>
								Consulte pela Chave de Acesso em
								https://sat.sef.sc.gov.br/nfce/consulta<br><br><strong>Chave de acesso:</strong><br><span class="chave">${Chave.pretty(chave)}</span></li>
      </ul>
    </div>
    <div data-role="collapsible" data-collapsed="false">
      <h4>Consumidor</h4>
      <ul data-role="listview" data-inset="false">
        <li><strong>Consumidor não identificado</strong></li>
      </ul>
    </div>
  </div>
</div></div>
                <div style="display: none;">
                    <span id="Body_lblDataHora">$stamp</span>
                </div>
            </div>
</body></html>"""
  }

  companion object {
    /** A CNPJ with valid check digits from 8 digits, branch 0001. */
    fun cnpj(base: String): String {
      var digits = base + "0001"
      for (weights in listOf(listOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2), listOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2))) {
        val r = digits.mapIndexed { i, ch -> (ch - '0') * weights[i] }.sum() % 11
        digits += if (r < 2) 0 else 11 - r
      }
      return digits
    }

    fun gtinCheck(first12: String): Int {
      var sum = 0
      first12.forEachIndexed { i, ch -> sum += (ch - '0') * if (i % 2 == 0) 1 else 3 }
      return (10 - sum % 10) % 10
    }
  }
}
