package io.github.sandroroque.getnf.sync

import androidx.room.useReaderConnection
import io.github.sandroroque.getnf.data.CatalogEditor
import io.github.sandroroque.getnf.data.Chave
import io.github.sandroroque.getnf.data.LocalFiles
import io.github.sandroroque.getnf.data.ReceiptDatabase
import io.github.sandroroque.getnf.data.ReceiptParser
import io.github.sandroroque.getnf.data.ReceiptStore
import io.github.sandroroque.getnf.data.editsTree
import io.github.sandroroque.getnf.data.testDatabase
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.util.Random
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The sync, simulated (D210): two or three phones, three people and their cadernetas go through a random sequence of
 * events drawn from a seed (scans, edits, deletions, syncs at any point, people joining, leaving and being removed,
 * another person signing in on a phone, another caderneta opened), against the local Supabase. Then everyone syncs
 * until nothing moves, and D210's rules are checked: cadernetas never mix, nothing a person did is lost, phones on
 * one caderneta hold the same data.
 *
 * The real [SyncEngine], [ReceiptStore], [ReceiptParser] and [CatalogEditor] run on in-memory databases, with the
 * real [SupabaseClient] and the server's own rules. What sits above the engine in the app ([SyncWorker], the
 * per-caderneta files) is a [Device] here: it takes the worker's choices from the same functions ([Ledgers.toSync],
 * [Ledgers.others], [LocalFiles.of]) and repeats its order of calls.
 *
 * Skipped unless GETNF_SUPABASE_LOCAL is set (as [SupabaseLocalTest]). A failure prints the seed, the number of events
 * and the events; `tools/sync-sim.sh` replays one and runs many.
 */
class SyncSimulationTest {
  @get:Rule val tmp = TemporaryFolder()
  private val keys = System.getenv("GETNF_SUPABASE_LOCAL")?.split(",")
  private val url = System.getenv("GETNF_SUPABASE_URL") ?: "http://127.0.0.1:54321"

  /** The seeds every push runs (`seed:events`), or GETNF_SIM_SEEDS's. Each was a failure once, or covers a kind of event. */
  @Test fun theFixedSeedsKeepTheRules() {
    assumeTrue("set GETNF_SUPABASE_LOCAL to run against a local Supabase", keys != null)
    val seeds = (System.getenv("GETNF_SIM_SEEDS")?.takeIf { it.isNotBlank() } ?: FIXED).split(",").map { it.trim() }
    val failures = seeds.mapNotNull { spec ->
      val seed = spec.substringBefore(':').toLong()
      run(seed, spec.substringAfter(':', "$STEPS").toInt())
    }
    if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
  }

  /** GETNF_SIM_RANDOM=n: n seeds nobody chose, starting at GETNF_SIM_FROM (the clock's if unset). Every failure is printed, with its shortest prefix. */
  @Test fun randomSeedsKeepTheRules() {
    val n = System.getenv("GETNF_SIM_RANDOM")?.toIntOrNull()
    assumeTrue("set GETNF_SIM_RANDOM to a number of seeds", keys != null && n != null)
    val from = System.getenv("GETNF_SIM_FROM")?.toLongOrNull() ?: (System.currentTimeMillis() / 1000)
    val steps = System.getenv("GETNF_SIM_STEPS")?.toIntOrNull() ?: STEPS
    val failures = mutableListOf<String>()
    for (seed in from until from + n!!) {
      val failure = run(seed, steps)
      println("sync simulation: seed $seed, $steps events: ${if (failure == null) "ok" else "FAILED"}")
      if (failure == null) continue
      // The shortest prefix that still fails: the events of a prefix are the same events.
      var low = 1; var high = steps; var shortest: String = failure
      while (low < high) {
        val mid = (low + high) / 2
        val shorter = run(seed, mid)
        if (shorter != null) { high = mid; shortest = shorter } else low = mid + 1
      }
      println(shortest)
      failures += shortest
    }
    if (failures.isNotEmpty()) fail("${failures.size} of $n seeds failed (from $from):\n\n" + failures.joinToString("\n\n"))
  }

  /** Null when every rule held; otherwise what to print. */
  private fun run(seed: Long, steps: Int): String? = runBlocking {
    val sim = Simulation(seed, steps, tmp.newFolder())
    try {
      sim.run()
    } catch (e: Throwable) {
      sim.violations += "the simulation itself stopped: ${e.stackTraceToString().lineSequence().take(12).joinToString("\n    ")}"
    } finally {
      sim.close()
    }
    if (sim.violations.isEmpty()) null
    else "sync simulation FAILED: seed $seed, $steps events (replay: tools/sync-sim.sh $seed:$steps)\n" +
      sim.log.joinToString("\n") { "  $it" } + "\nbroken rules:\n" + sim.violations.distinct().joinToString("\n") { "  $it" }
  }

  // ---- what the people did, as the rules need it ----

  /** A receipt that came to be in a database by a scan (not from the caderneta), at [at]. */
  private data class Copy(val files: Int, val chave: String, val at: Long)
  private data class Deletion(val files: Int, val chave: String, val at: Long)
  /** [copyAt]: the scan time of the copy the page was read into. */
  private data class PageSave(val files: Int, val chave: String, val copyAt: Long)
  private data class Scan(val files: Int, val chave: String, val person: Int?)
  /** One field of one row set by hand to [value], a value nobody else ever sets; [over] is what the phone showed before. */
  /** [copyAt], for a line: the scan time of the receipt's copy it was set on, which it lives and dies with. */
  private data class Edit(val id: Int, val files: Int, val field: String, val value: String, val over: String?, val copyAt: Long = 0)

  private inner class Simulation(val seed: Long, val steps: Int, val root: File) {
    val rng = Random(seed)
    val log = mutableListOf<String>()
    val violations = mutableListOf<String>()
    /** Every phone's clock: one that only moves forward, a millisecond a reading, so every time in a run is its own. */
    var time = 1_760_000_000_000L
    fun now() = ++time
    var ids = 0L
    val anon = SupabaseClient(url, keys!![0])
    val service = SupabaseClient(url, keys!![1])
    val asService = SupabaseSession("service", "", keys!![1], "")
    val html = javaClass.classLoader!!.getResource("sc-detalhes-server-html.html")!!.readText()
    val baseChave = "42260111222333000181651090000000011123456784"
    val chaves = (1..5).map { k -> (baseChave.take(25) + "%09d".format(k) + baseChave.substring(34, 43)).let { it + Chave.checkDigit(it) } }
    fun page(chave: String) = html.replace(Chave.pretty(baseChave), Chave.pretty(chave))

    lateinit var people: List<SupabaseSession>
    /** Each person's own caderneta; the first two are the ones others join. */
    lateinit var ledgers: List<String>
    /** Who is in each caderneta, and as what, as the events left it. */
    val members = List(3) { mutableMapOf<Int, String>() }
    /** Who was ever out of each caderneta after being in it. */
    val wentOut = List(3) { mutableSetOf<Int>() }
    val devices = mutableListOf<Device>()
    val allFiles = mutableListOf<Files>()
    var round = 0
    var pushed = 0

    val copies = mutableListOf<Copy>(); val deletions = mutableListOf<Deletion>(); val pages = mutableListOf<PageSave>()
    val scans = mutableListOf<Scan>(); val edits = mutableListOf<Edit>()

    /** One caderneta's data on one phone (D104): a database and a receipts folder. */
    inner class Files(val tag: Int, val device: Int, val key: LocalFiles) {
      val dir = File(root, "files-$tag/receipts")
      val db = testDatabase()
      val store = ReceiptStore(dir, db, now = ::now, warn = { _, _ -> })
      val parser = ReceiptParser(db, dir, now = ::now, log = { _, _ -> })
      val editor = CatalogEditor(db, now = ::now, newId = { UUID(seed, ++ids).toString() })
      val engine = SyncEngine(db, store, { photo, _ -> photo.readBytes() }, contribute = { true }, log = {})
      var syncedInRound = -1
      /** Everyone who synced these files, signed in on their phone. */
      val syncedBy = mutableSetOf<Int>()
      suspend fun bound(): String? = db.sync().state(SyncEngine.LEDGER)
      override fun toString() = "phone $device's files #$tag"
    }

    /** A phone: who is signed in, which caderneta is open, and the files it has (AccountSettings and GetNFApp in the app). */
    inner class Device(val n: Int) {
      var account: Int? = null
      var open: String? = null
      var legacy: String? = null
      val files = LinkedHashMap<LocalFiles, Files>()
      lateinit var current: Files
      val client = SupabaseClient(url, keys!![0], active = { s -> account?.let { people[it].userId } == s.userId })
      fun key(ledger: String?) = LocalFiles.of(ledger, legacy)
      fun openLedger(id: String) { open = id; if (legacy == null) legacy = id }
      /** The app starts: its database is the open caderneta's. */
      suspend fun restart() {
        val key = key(open)
        current = files[key] ?: Files(allFiles.size, n, key).also { it.store.load(); files[key] = it; allFiles += it }
      }

      /** SyncWorker.doWork, in its order. */
      suspend fun sync() {
        val session = people[account ?: return]
        try {
          val mine = Ledgers.mine(client, session)
          val wasOpen = open
          val ledger = Ledgers.toSync(mine, wasOpen, session.userId)?.also { if (wasOpen == null) openLedger(it) }
          if (key(open) != current.key) return
          if (ledger != null && one(current, SupabaseRemote(client, session, ledger))) return
          for (other in Ledgers.others(mine, open) { key(it) in files }) {
            try { one(files.getValue(key(other)), SupabaseRemote(client, session, other)) }
            catch (e: SupabaseException) { if (e.status == 401) throw e; violations += "[quiet] phone $n: caderneta ${name(other)} refused a sync: ${e.message}" }
          }
        } catch (e: SupabaseException) {
          violations += "[quiet] phone $n: the sync run failed: ${e.message}"
        } catch (e: IOException) {
          violations += "[quiet] phone $n: the sync run failed: ${e.message}"
        }
      }

      /** One database with its caderneta, then again if receipts came with a page to read. True: it was another caderneta's (D193). */
      private suspend fun one(f: Files, remote: SupabaseRemote): Boolean {
        fun note(r: SyncEngine.Report) {
          pushed += r.pushed + r.photos
          // The one refusal the events can cause: a category renamed by someone the server knows as a plain member.
          r.errors.filterNot { it.startsWith("category ") || it.startsWith("category_event ") }
            .forEach { violations += "[quiet] $f: the sync reported: $it" }
        }
        val report = f.engine.sync(remote)
        note(report)
        if (report.foreign) { violations += "[apart] $f was taken for another caderneta's when syncing with ${name(remote.ledgerId)}"; return true }
        f.syncedInRound = round; f.syncedBy += account!!
        if (f.parser.parsePending(f.store.receipts.value) > 0) note(f.engine.sync(remote))
        return false
      }
    }

    fun name(ledger: String?) = ledger?.let { "L${ledgers.indexOf(it)}" } ?: "none"
    fun say(what: String) { log += "${log.size + 1}. $what" }

    suspend fun run() {
      people = List(3) { newUser() }
      ledgers = people.map { Ledgers.pick(Ledgers.mine(anon, it), null, it.userId)!! }
      for (l in 0..2) members[l][l] = "owner"
      val setup = mutableListOf<String>()
      for ((person, ledger) in listOf(1 to 0, 2 to 0, 2 to 1, 0 to 1)) if (rng.nextInt(10) < 6) {
        val role = if (rng.nextBoolean()) "member" else "manager"
        join(person, ledger, role); setup += "u$person in L$ledger as $role"
      }
      repeat(2 + rng.nextInt(2)) { n ->
        devices += Device(n).apply { restart(); account = rng.nextInt(4).takeIf { it < 3 } }
        setup += "phone $n: ${devices[n].account?.let { "u$it signed in" } ?: "no account"}"
      }
      log += "start: ${setup.joinToString("; ")}"
      repeat(steps) { step() }

      // Everyone as they are: sync until nothing moves.
      quiet("as they are")
      val live = allFiles.filter { it.syncedInRound == round }
      apart(live); same(live, parkedAllowed = true)

      // Everyone back in every caderneta, as a manager (so a refused category edit is taken on "Tentar de novo"),
      // someone signed in on every phone: now every database can sync, and nothing may be left behind.
      for (l in 0..2) for (p in 0..2) when (members[l][p]) {
        null -> join(p, l, "manager")
        "member" -> role(l, p, "manager")
        else -> {}
      }
      for (d in devices) if (d.account == null) d.account = d.n % 3
      for (f in allFiles) f.db.sync().unparkAll()
      quiet("everyone back")
      for (f in allFiles) if (f.syncedInRound != round) violations += "[kept] $f never synced though a member is signed in on its phone"
      apart(allFiles); same(allFiles, parkedAllowed = false); kept(); server(); nothingPending()
    }

    fun close() { for (f in allFiles) runCatching { f.db.close() } }

    // ---- events ----

    private val kinds = listOf("sync" to 24, "scan" to 12, "rename" to 8, "size" to 5, "category" to 4, "override" to 6, "label" to 5,
      "delete" to 5, "photo" to 4, "sign out" to 3, "sign in" to 6, "open" to 5, "join" to 4, "remove" to 3, "leave" to 3, "role" to 2)

    private suspend fun step() {
      repeat(50) {
        var pick = rng.nextInt(kinds.sumOf { it.second })
        val kind = kinds.first { pick -= it.second; pick < 0 }.first
        if (event(kind)) return
      }
      say("(nothing possible)")
    }

    private fun <T> List<T>.any(): T? = if (isEmpty()) null else this[rng.nextInt(size)]

    /** False when [kind] can't happen now; nothing was changed then. */
    private suspend fun event(kind: String): Boolean {
      val d = devices[rng.nextInt(devices.size)]
      val f = d.current
      val catalog = f.db.catalog()
      when (kind) {
        "sync" -> { if (d.account == null) return false; say("phone ${d.n} syncs (u${d.account}, open ${name(d.open)})"); d.sync() }
        "scan" -> {
          val k = rng.nextInt(chaves.size); val chave = chaves[k]; val withPage = rng.nextInt(4) != 0
          val had = f.store.get(chave)
          val r = f.store.open(chave, "qr")
          if (had == null) copies += Copy(f.tag, chave, r.capturedAt)
          scans += Scan(f.tag, chave, d.account)
          val reads = withPage && r.pageFile == null
          if (reads) {
            f.store.savePage(chave, "https://x", page(chave)); f.parser.parse(f.store.get(chave)!!)
            pages += PageSave(f.tag, chave, r.capturedAt)
          }
          say("phone ${d.n} scans receipt $k${if (had != null) " again" else ""}${if (reads) " and reads its page" else ""} (files #${f.tag})")
        }
        "rename" -> {
          val p = catalog.products().filter { !it.deleted }.sortedBy { it.id }.any() ?: return false
          val e = Edit(edits.size, f.tag, "product/${p.id}/name", "n${edits.size}", p.name).also { edits += it }
          f.editor.rename(p.id, e.value)
          say("phone ${d.n} names product ${p.id.take(8)} \"${e.value}\" (was ${p.name})")
        }
        "size" -> {
          val p = catalog.products().filter { !it.deleted }.sortedBy { it.id }.any() ?: return false
          val e = Edit(edits.size, f.tag, "product/${p.id}/size", "${(edits.size + 1) * 1000L}", p.sizeE6?.toString()).also { edits += it }
          f.editor.setSize(p.id, e.value.toLong(), "kg")
          say("phone ${d.n} sets product ${p.id.take(8)}'s size to ${e.value} (was ${p.sizeE6})")
        }
        "category" -> {
          val p = catalog.products().filter { !it.deleted }.sortedBy { it.id }.any() ?: return false
          val c = catalog.categories().filter { !it.deleted && it.seedPath != null }.sortedBy { it.seedPath }.any() ?: return false
          f.editor.setCategory(p.id, c.id)
          say("phone ${d.n} puts product ${p.id.take(8)} in ${c.seedPath}")
        }
        "override" -> {
          val line = catalog.lines().any() ?: return false
          val field = "line/${line.receipt}/${line.lineNo}/override"
          val used = edits.filter { it.field == field }.map { it.value }
          val p = catalog.products().filter { !it.deleted && it.id !in used }.sortedBy { it.id }.any() ?: return false
          edits += Edit(edits.size, f.tag, field, p.id, line.productOverrideId, f.store.get(line.receipt)!!.capturedAt)
          f.editor.overrideProduct(line.receipt, line.lineNo, p.id)
          say("phone ${d.n} says line ${line.lineNo} of receipt ${chaves.indexOf(line.receipt)} is product ${p.id.take(8)} (was ${line.productOverrideId?.take(8)})")
        }
        "label" -> {
          // The app offers the tree only to someone it knows as owner or manager, or doesn't know yet (T-29).
          val role = f.db.rows("SELECT role FROM member WHERE you = 1").firstOrNull()?.get(0)
          if (!editsTree(role)) return false
          val c = catalog.categories().filter { !it.deleted && it.seedPath != null }.sortedBy { it.seedPath }.take(4).any() ?: return false
          val e = Edit(edits.size, f.tag, "category/${c.seedPath}/label", "c${edits.size}", c.label).also { edits += it }
          f.editor.renameCategory(c.id, e.value, c.description)
          say("phone ${d.n} renames category ${c.seedPath} \"${e.value}\" (was ${c.label})")
        }
        "delete" -> {
          val r = f.store.receipts.value.sortedBy { it.chave }.any() ?: return false
          f.store.delete(r.chave)
          deletions += Deletion(f.tag, r.chave, time)
          say("phone ${d.n} deletes receipt ${chaves.indexOf(r.chave)} (files #${f.tag})")
        }
        "photo" -> {
          val r = f.store.receipts.value.sortedBy { it.chave }.any() ?: return false
          f.store.addPhoto(r.chave, f.store.newPhotoFile(r.chave).apply { parentFile.mkdirs(); writeText("photo of files #${f.tag}") })
          say("phone ${d.n} adds a photo to receipt ${chaves.indexOf(r.chave)}")
        }
        "sign out" -> { if (d.account == null) return false; say("phone ${d.n}: u${d.account} signs out"); d.account = null }
        "sign in" -> { if (d.account != null) return false; d.account = rng.nextInt(3); say("phone ${d.n}: u${d.account} signs in") }
        "open" -> {
          val who = d.account ?: return false
          val l = (0..2).filter { who in members[it] && ledgers[it] != d.open }.any() ?: return false
          d.openLedger(ledgers[l]); d.restart()
          say("phone ${d.n}: u$who opens L$l (files #${d.current.tag})")
        }
        "join" -> {
          val l = rng.nextInt(2)
          val p = (0..2).filter { it !in members[l] }.any() ?: return false
          val role = if (rng.nextBoolean()) "member" else "manager"
          join(p, l, role); say("u$p joins L$l as $role")
        }
        "remove", "leave" -> {
          val l = rng.nextInt(2)
          val p = (0..2).filter { it != l && it in members[l] }.any() ?: return false
          if (kind == "remove") LedgerCalls.remove(anon, people[l], ledgers[l], people[p].userId) else LedgerCalls.leave(anon, people[p], ledgers[l])
          members[l].remove(p); wentOut[l] += p
          say(if (kind == "remove") "u$l removes u$p from L$l" else "u$p leaves L$l")
        }
        "role" -> {
          val l = rng.nextInt(2)
          val p = (0..2).filter { it != l && it in members[l] }.any() ?: return false
          val to = if (members[l][p] == "member") "manager" else "member"
          role(l, p, to); say("u$l makes u$p a $to of L$l")
        }
      }
      return true
    }

    private fun join(person: Int, ledger: Int, role: String) {
      val code = anon.rpc(people[ledger], "create_invite", buildJsonObject { put("lid", ledgers[ledger]); put("role", role) }).jsonPrimitive.content
      anon.rpc(people[person], "join_ledger", buildJsonObject { put("code", code) })
      members[ledger][person] = role
    }

    private fun role(ledger: Int, person: Int, role: String) {
      anon.rpc(people[ledger], "set_role", buildJsonObject { put("lid", ledgers[ledger]); put("uid", people[person].userId); put("role", role) })
      members[ledger][person] = role
    }

    private fun newUser(): SupabaseSession {
      val email = "sim-${UUID.randomUUID()}@example.test"; val password = UUID.randomUUID().toString()
      val body = buildJsonObject { put("email", email); put("password", password); put("email_confirm", true) }
      val request = Request.Builder().url("$url/auth/v1/admin/users").header("apikey", keys!![1])
        .post(body.toString().toRequestBody("application/json".toMediaType())).build()
      OkHttpClient().newCall(request).execute().use { check(it.isSuccessful) { "creating a user: ${it.code} ${it.body.string()}" } }
      return anon.signIn(email, password)
    }

    // ---- the rules (D210) ----

    /** Rule 0: with nobody doing anything, the phones stop sending within a few rounds. */
    private suspend fun quiet(phase: String) {
      var before: String? = null
      repeat(ROUNDS) {
        round++; pushed = 0
        for (d in devices) d.sync()
        val state = allFiles.map { "#${it.tag}\n" + content(it).joinToString("\n") }.joinToString("\n")
        if (pushed == 0 && state == before) return
        before = state
      }
      violations += "[quiet] $phase: after $ROUNDS rounds of everyone syncing, the phones still send or change ($pushed rows sent in the last round)"
    }

    private fun ours(files: Int, ledger: String, bound: Map<Int, String?>) = bound[files] == ledger

    /** Rule 1 on the phones: what a database holds was made in a database of its own caderneta. */
    private suspend fun apart(databases: List<Files>) {
      val bound = allFiles.associate { it.tag to it.bound() }
      for (f in databases) {
        val ledger = bound[f.tag] ?: continue
        val expected = if (f.key == LocalFiles.of(null, null)) devices[f.device].legacy else ledgers.find { devices[f.device].key(it) == f.key }
        if (ledger != expected) violations += "[apart] $f syncs with ${name(ledger)} but is ${name(expected)}'s place on the phone"
        fun check(what: String, edit: Edit?) {
          if (edit != null && !ours(edit.files, ledger, bound))
            violations += "[apart] $f (${name(ledger)}) holds $what, set in files #${edit.files} (${name(bound[edit.files])})"
        }
        for (p in f.db.catalog().products()) {
          check("product name ${p.name}", edits.find { it.field.endsWith("/name") && it.value == p.name })
          check("product size ${p.sizeE6}", edits.find { it.field.endsWith("/size") && it.value == p.sizeE6?.toString() })
        }
        for (c in f.db.catalog().categories()) check("category label ${c.label}", edits.find { it.field.endsWith("/label") && it.value == c.label })
        for (r in f.store.receipts.value) if (copies.none { it.chave == r.chave && it.at == r.capturedAt && ours(it.files, ledger, bound) })
          violations += "[apart] $f (${name(ledger)}) holds receipt ${chaves.indexOf(r.chave)} as scanned at ${r.capturedAt}, which no phone of ${name(ledger)} did"
      }
    }

    /** A database's content as the rules compare it: every synced table, without the phone's own bookkeeping. */
    private suspend fun content(f: Files, without: (String) -> Boolean = { false }): List<String> {
      val out = mutableListOf<String>()
      suspend fun table(name: String, sql: String) { for (row in f.db.rows(sql)) out += "$name ${row.joinToString("|")}" }
      // A receipt's scan time, versions and photos are each phone's own; the page's time is of the phone's own fetch.
      table("receipt", "SELECT chave, pageFile IS NOT NULL FROM receipt")
      table("receipt_parsed", "SELECT chave, issuerCnpj, issuedAt, grossCents, discountCents, paidCents, parseError, changeCents, taxTotalCents FROM receipt_parsed")
      for (name in listOf("receipt_line", "product", "store_item", "category", "tag", "issuer", "product_tag", "link_suggestion", "receipt_scan", "member")) {
        val columns = f.db.rows("SELECT name FROM pragma_table_info('$name')").map { it[0]!! }.filter { it != "dirty" && it != "you" }
        table(name, "SELECT ${columns.joinToString(",")} FROM $name")
      }
      return out.filterNot(without).sorted()
    }

    /** Rule 3: the databases of one caderneta hold the same. */
    private suspend fun same(databases: List<Files>, parkedAllowed: Boolean) {
      for ((ledger, group) in databases.groupBy { it.bound() }) {
        if (ledger == null) continue
        // A row the server refused differs on the phone that holds it, until it's taken (rule 2c).
        val parked = mutableSetOf<String>()
        if (parkedAllowed) for (g in group) g.db.sync().parked().filter { it.tableName == "category" }.mapTo(parked) { "category ${it.rowKey}|" }
        // Finding 3: the server drops the scan marks of someone who left and of a deleted receipt; a phone that had
        // them, and kept its copy of the receipt, keeps them.
        val bound = allFiles.associate { it.tag to it.bound() }
        val gone = if (!STALE_SCAN_MARKS_STAY) emptyList() else wentOut[ledgers.indexOf(ledger)].map { "|${people[it].userId}" } +
          deletions.filter { ours(it.files, ledger, bound) }.map { "receipt_scan ${it.chave}|" }
        // Finding 4: a phone that was away while a receipt was deleted and scanned again keeps its old copy's page and header.
        val rescanned = if (!OLD_COPY_OUTLIVES_A_MISSED_DELETION) emptyList() else
          deletions.filter { ours(it.files, ledger, bound) }.flatMap { listOf("receipt ${it.chave}|", "receipt_parsed ${it.chave}|") }
        val without = { line: String -> parked.any { it in line } || rescanned.any(line::startsWith) ||
          (line.startsWith("receipt_scan ") && gone.any { line.endsWith(it) || line.startsWith(it) }) }
        val first = content(group.first(), without)
        for (other in group.drop(1)) {
          val theirs = content(other, without)
          if (theirs == first) continue
          val onlyFirst = (first - theirs.toSet()).take(6); val onlyOther = (theirs - first.toSet()).take(6)
          violations += "[same] ${name(ledger)}: ${group.first()} and $other differ.\n      only in #${group.first().tag}: $onlyFirst\n      only in #${other.tag}: $onlyOther"
        }
      }
    }

    private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    private fun rowsOf(table: String, ledger: String) = service.select(asService, table, listOf("ledger_id" to "eq.$ledger"))

    /** Rule 2: nothing a person did is lost. Read from the server, which rule 3 ties to every phone. */
    private suspend fun kept() {
      val bound = allFiles.associate { it.tag to it.bound() }
      for ((l, ledger) in ledgers.withIndex()) {
        val receipts = rowsOf("receipt", ledger).associateBy { it.text("chave")!! }
        val lastDeletion = chaves.associateWith { c -> deletions.filter { it.chave == c && ours(it.files, ledger, bound) }.maxOfOrNull { it.at } ?: 0L }
        // 2a: a receipt is in the caderneta exactly when a phone scanned a copy of it after its last deletion.
        for ((k, chave) in chaves.withIndex()) {
          val lastCopy = copies.filter { it.chave == chave && ours(it.files, ledger, bound) }.maxOfOrNull { it.at } ?: 0L
          val row = receipts[chave]?.takeIf { it.text("deleted") != "true" }
          val expected = lastCopy > lastDeletion.getValue(chave)
          if (expected != (row != null)) violations += "[kept] L$l, receipt $k: ${if (expected) "scanned at $lastCopy, after its last deletion (${lastDeletion[chave]}), but gone" else "deleted at ${lastDeletion[chave]}, after its last scan ($lastCopy), but still there"}"
          // 2b: a page read into a copy scanned after the last deletion stays.
          if (row != null && row.text("page_path") == null && pages.any { it.chave == chave && ours(it.files, ledger, bound) && it.copyAt > lastDeletion.getValue(chave) })
            violations += "[kept] L$l, receipt $k: its page was read into a copy scanned after the last deletion, and the caderneta has none"
        }
        // 2c: a field set by hand ends as one of the values nobody knowingly replaced.
        val products = rowsOf("product", ledger).associateBy { it.text("id")!! }
        val categories = rowsOf("category", ledger).associateBy { it.text("seed_path") }
        val lines = rowsOf("receipt_line", ledger).associateBy { "${it.text("chave")}/${it.text("line_no")}" }
        for ((field, all) in edits.filter { ours(it.files, ledger, bound) }.groupBy { it.field }) {
          val (table, key, column) = field.split("/", limit = 2).let { (t, rest) -> Triple(t, rest.substringBeforeLast('/'), rest.substringAfterLast('/')) }
          val standing = all.filter { e -> all.none { it.over == e.value } }
          val allowed = standing.map { it.value }.toMutableSet<String?>()
          // Finding 1: a phone that made the row itself (read a page before it had the caderneta's row) sends it whole.
          val chaveOfLine = if (table == "line") key.substringBefore('/') else null
          if (FIRST_PUSH_OVERWRITES && table != "category" &&
            pages.filter { ours(it.files, ledger, bound) && (chaveOfLine == null || it.chave == chaveOfLine) }.map { it.files }.distinct().size > 1) allowed += null
          val final = when (table) {
            "product" -> products[key]?.text(if (column == "name") "name" else "size_e6")
            "category" -> categories[key]?.text("label")
            else -> {
              val chave = key.substringBefore('/')
              val line = lines[key] ?: continue // no receipt, or no page read: rule 2a and 2b
              // A correction goes with its copy of the receipt when a deletion takes that copy; it may or may not reach a later scan's lines.
              if (standing.all { it.copyAt <= lastDeletion.getValue(chave) }) allowed += null
              line.text("product_override_id")
            }
          }
          if (final !in allowed) violations += "[kept] L$l, $field: ended as $final; the values set and not replaced were ${standing.map { "${it.value} (files #${it.files})" }}"
        }
        // 2d: a scan mark names someone who scanned the receipt in this caderneta (or whose phone had nobody signed in then).
        for (mark in rowsOf("receipt_scan", ledger)) {
          val person = people.indexOfFirst { it.userId == mark.text("user_id") }
          // Finding 2: a scan goes up as whoever is signed in when it syncs, not who was when it was made.
          if (scans.none { it.chave == mark.text("chave") && ours(it.files, ledger, bound) &&
              (it.person == person || it.person == null || (SCAN_MARKS_FOLLOW_THE_SIGN_IN && person in allFiles[it.files].syncedBy)) })
            violations += "[kept] L$l, receipt ${chaves.indexOf(mark.text("chave"))}: marked as scanned by u$person, who didn't scan it here"
        }
      }
    }

    /** Rules 1 and 3 on the server: each caderneta's rows and files are its own, and they are what its phones hold. */
    private suspend fun server() {
      val bound = allFiles.associate { it.tag to it.bound() }
      for ((l, ledger) in ledgers.withIndex()) {
        fun check(what: String, edit: Edit?) {
          if (edit != null && !ours(edit.files, ledger, bound)) violations += "[apart] L$l holds $what online, set in files #${edit.files} (${name(bound[edit.files])})"
        }
        val products = rowsOf("product", ledger)
        for (p in products) {
          check("product name ${p.text("name")}", edits.find { it.field.endsWith("/name") && it.value == p.text("name") })
          check("product size ${p.text("size_e6")}", edits.find { it.field.endsWith("/size") && it.value == p.text("size_e6") })
        }
        val categories = rowsOf("category", ledger)
        for (c in categories) check("category label ${c.text("label")}", edits.find { it.field.endsWith("/label") && it.value == c.text("label") })
        val receipts = rowsOf("receipt", ledger)
        for (r in receipts) {
          val at = OffsetDateTime.parse(r.text("captured_at")!!).toInstant().toEpochMilli()
          // A tombstone for a receipt that was never sent carries the deletion's time.
          val unsent = r.text("deleted") == "true" && deletions.any { it.chave == r.text("chave") && it.at == at && ours(it.files, ledger, bound) }
          if (!unsent && copies.none { it.chave == r.text("chave") && it.at == at && ours(it.files, ledger, bound) })
            violations += "[apart] L$l holds receipt ${chaves.indexOf(r.text("chave"))} online as scanned at $at, which no phone of L$l did"
        }
        // Files: exactly the pages and photos its rows name, in its own folder.
        val photos = rowsOf("receipt_photo", ledger)
        val named = (receipts.filter { it.text("deleted") != "true" }.mapNotNull { it.text("page_path") } +
          photos.flatMap { listOfNotNull(it.text("object_path"), it.text("mask_path")) }).toSet()
        for (path in named) if (!path.startsWith("$ledger/")) violations += "[apart] L$l names a file outside its folder: $path"
        val stored = service.listObjects(asService, "$ledger/").toSet()
        for (path in stored - named) violations += "[apart] L$l: a file no row names is left online: ${path.removePrefix(ledger)}"
        for (path in named - stored) violations += "[kept] L$l: a row names a file that isn't online: ${path.removePrefix(ledger)}"
        // Who is in it.
        val online = rowsOf("ledger_member", ledger).associate { m -> people.indexOfFirst { it.userId == m.text("user_id") } to m.text("role") }
        if (online != members[l].toMap()) violations += "[same] L$l's members online are $online, the events left ${members[l]}"
        // What the phones hold is what is online (rule 3 compares the phones with each other).
        val f = allFiles.firstOrNull { bound[it.tag] == ledger } ?: continue
        fun compare(what: String, online: Collection<String>, phone: Collection<String>) {
          if (online.sorted() != phone.sorted()) violations += "[same] L$l, $what: online and $f differ.\n      only online: ${(online - phone.toSet()).take(6)}\n      only on the phone: ${(phone - online.toSet()).take(6)}"
        }
        val deleted = deletions.filter { ours(it.files, ledger, bound) }.map { it.chave }.toSet()
        fun page(chave: String?, has: Boolean) = if (OLD_COPY_OUTLIVES_A_MISSED_DELETION && chave in deleted) "$chave" else "$chave page=$has"
        compare("receipts", receipts.filter { it.text("deleted") != "true" }.map { page(it.text("chave"), it.text("page_path") != null) },
          f.store.receipts.value.map { page(it.chave, it.pageFile != null) })
        compare("products", products.map { "${it.text("id")} ${it.text("name")} ${it.text("size_e6")} ${it.text("category_id")} ${it.text("deleted")}" },
          f.db.catalog().products().map { "${it.id} ${it.name} ${it.sizeE6} ${it.categoryId} ${it.deleted}" })
        compare("categories", categories.map { "${it.text("id")} ${it.text("label")}" }, f.db.catalog().categories().map { "${it.id} ${it.label}" })
        compare("lines", rowsOf("receipt_line", ledger).map { "${it.text("chave")}/${it.text("line_no")} ${it.text("product_override_id")} ${it.text("discount_cents")} ${it.text("store_item_id")}" },
          f.db.catalog().lines().map { "${it.receipt}/${it.lineNo} ${it.productOverrideId} ${it.discountCents} ${it.storeItemId}" })
      }
    }

    /** Rule 2e: once everyone can sync, no phone is still holding something back. */
    private suspend fun nothingPending() {
      for (f in allFiles) {
        for (p in f.db.sync().parked()) violations += "[kept] $f: still refused by the server: ${p.tableName} ${p.rowKey}: ${p.error}"
        for (table in listOf("receipt_line", "product", "store_item", "category", "tag", "issuer", "product_tag", "link_suggestion", "receipt_parsed", "category_event")) {
          val n = f.db.rows("SELECT count(*) FROM $table WHERE dirty = 1").single()[0]
          if (n != "0") violations += "[kept] $f: $n changed row(s) of $table not sent"
        }
        for (r in f.store.receipts.value) if (r.needsUpload) violations += "[kept] $f: receipt ${chaves.indexOf(r.chave)} or its photos not sent"
        val waiting = f.db.rows("SELECT name FROM sync_state").map { it[0]!! }.filter { name -> listOf("deleted:", "deleted-files:", "page:", "reconcile:").any(name::startsWith) }
        if (waiting.isNotEmpty()) violations += "[kept] $f: unfinished work in sync_state: ${waiting.map { it.substringBefore(':') + ":" + chaves.indexOf(it.substringAfter(':')) }}"
        val removed = f.db.rows("SELECT count(*) FROM line_removed").single()[0]
        if (removed != "0") violations += "[kept] $f: $removed removed line(s) not sent"
      }
    }
  }

  private suspend fun ReceiptDatabase.rows(sql: String): List<List<String?>> = useReaderConnection { connection ->
    connection.usePrepared(sql) { s ->
      buildList { while (s.step()) add((0 until s.getColumnCount()).map { if (s.isNull(it)) null else s.getText(it) }) }
    }
  }

  private companion object {
    const val STEPS = 40
    const val ROUNDS = 8
    /** `seed:events`, run by tools/check.sh before every push. */
    const val FIXED = "1,2,3,4,5,6,2:40,101:30,105:39,107:35,109:33,112:21,129:27,1001:38,1005:19,1020:29,1051:38,1085:38,2216:26,2252:40"

    // Findings the simulation made that are not fixed yet (D210): each relaxes one rule exactly where the finding
    // applies, so the rest of the rule still holds. Set one to false to see its failures again.
    /** 1: the first push of a product or line a phone made itself replaces what another phone set in it. */
    const val FIRST_PUSH_OVERWRITES = true
    /** 2: a scan made while one person was signed in, synced after another signed in on the phone, is marked as the other's. */
    const val SCAN_MARKS_FOLLOW_THE_SIGN_IN = true
    /** 3: phones keep scan marks the server dropped: those of someone who left, and of a receipt deleted and scanned again. */
    const val STALE_SCAN_MARKS_STAY = true
    /** 4: a phone that never saw a receipt's deletion, because it was scanned again first, keeps the old copy's page and header. */
    const val OLD_COPY_OUTLIVES_A_MISSED_DELETION = true
  }
}
