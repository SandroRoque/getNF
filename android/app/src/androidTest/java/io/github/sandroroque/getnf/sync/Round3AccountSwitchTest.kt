package io.github.sandroroque.getnf.sync

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.ProjectConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Round 3 (audit): account and caderneta changes while the real SyncWorker runs. Only on a disposable emulator whose app
 * points at the local Supabase through the delay proxy (arguments proxy, key, email, password; the user has two
 * cadernetas: its own and `ledgerB`). Synthetic receipt page. Run with an explicit serial.
 */
class Round3AccountSwitchTest {
  private val args = InstrumentationRegistry.getArguments()
  private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
  private val app = ctx.applicationContext as GetNFApp
  private val settings = app.accountSettings
  private val proxy = args.getString("proxy")!!
  private val key = args.getString("key")!!
  private val chave = "42260111222333000181651090000000011123456784"
  private val html = javaClass.classLoader!!.getResource("sc-detalhes-server-html.html")!!.readText()

  private fun ctl(query: String) = OkHttpClient().newCall(Request.Builder().url("$proxy/__ctl?$query").build()).execute().use { check(it.isSuccessful) }

  private fun signIn(): SupabaseSession {
    check(proxy.contains("10.0.2.2")) { "refusing: the app must point at the host's loopback proxy" }
    settings.setProject(ProjectConfig(proxy, key))
    return SupabaseClient(proxy, key).signIn(args.getString("email")!!, args.getString("password")!!).also(settings::signedIn)
  }

  private fun waitForTheWorker() {
    val wm = WorkManager.getInstance(ctx)
    val end = System.currentTimeMillis() + 60_000
    while (System.currentTimeMillis() < end) {
      if (wm.getWorkInfosForUniqueWork(SyncWorker.NAME).get().all { it.state.isFinished }) return
      Thread.sleep(250)
    }
    error("the sync worker never finished")
  }

  private fun serverReceipts(session: SupabaseSession, ledger: String) =
    SupabaseClient(proxy, key).select(session, "receipt", listOf("ledger_id" to "eq.$ledger")).size

  @Before fun before() { ctl("token_ms=0&rest_ms=0") }
  @After fun after() { ctl("token_ms=0&rest_ms=0"); settings.signOut() }

  /** T-20 (D191). */
  @Test fun reviewRound3SignOutWhileTheWorkerRefreshesMustStaySignedOut() {
    signIn()
    ctl("token_ms=3000")                       // the worker's refresh is slow, in flight when the person taps "Sair"
    SyncWorker.enqueue(ctx)
    Thread.sleep(1200)
    settings.signOut()                         // AccountScreen "Sair"
    assertNull(settings.account())
    waitForTheWorker()
    assertNull("a refresh already in flight must not sign the person back in", settings.account())
  }

  /** T-20 (D191). */
  @Test fun reviewRound3SignOutMidSyncMustStopSendingTheirData() = runBlocking {
    val session = signIn()
    val ledger = Ledgers.pick(Ledgers.mine(SupabaseClient(proxy, key), session), null, session.userId)!!
    settings.openLedger(ledger)
    app.receiptStore.load()
    app.receiptStore.open(chave, "qr"); app.receiptStore.savePage(chave, "https://x", html); app.receiptParser.parse(app.receiptStore.get(chave)!!)
    ctl("rest_ms=1200")                        // every REST call of the run is slow: the run lasts many seconds
    SyncWorker.enqueue(ctx)
    Thread.sleep(3500)                         // refresh done, the run is pushing
    println("R3 signOut at epoch ms ${System.currentTimeMillis()}")
    settings.signOut()
    waitForTheWorker()
    println("R3 worker finished at epoch ms ${System.currentTimeMillis()}")
    val after = SupabaseClient(proxy, key).refresh(session.refreshToken)   // test-side session to look at the server
    val sent = serverReceipts(after, ledger)
    println("R3 receipts that reached the server after the person signed out mid-run: $sent; signed-out state kept=${settings.account() == null}")
    assertEquals("signing out must stop the run before it sends the person's receipts", 0, sent)
  }

  /** T-25. */
  @Test fun reviewRound3OpeningAnotherCadernetaBeforeTheRestartMustNotPushTheOldDatabaseIntoIt() = runBlocking {
    val session = signIn()
    val client = SupabaseClient(proxy, key)
    val mine = Ledgers.mine(client, session)
    check(mine.size >= 2) { "the synthetic user needs two cadernetas" }
    val own = mine.first().id
    val other = mine.last().id
    settings.openLedger(own)
    app.receiptStore.load()
    app.receiptStore.open(chave, "qr"); app.receiptStore.savePage(chave, "https://x", html); app.receiptParser.parse(app.receiptStore.get(chave)!!)
    // "Abrir" in Ajustes: the choice is committed, then the process is killed. A run that starts in between still holds
    // the old database (bound when the process started).
    settings.openLedger(other)
    SyncWorker.enqueue(ctx)
    waitForTheWorker()
    val fresh = client.refresh(session.refreshToken)
    println("R3 receipts of the old database now in the newly opened caderneta: ${serverReceipts(fresh, other)}; in its own: ${serverReceipts(fresh, own)}")
    assertEquals("the old database must never be pushed into the caderneta opened after it", 0, serverReceipts(fresh, other))
  }
}
