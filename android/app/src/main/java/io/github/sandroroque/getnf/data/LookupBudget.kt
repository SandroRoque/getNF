package io.github.sandroroque.getnf.data

/**
 * SEFAZ blocks an IP after roughly [LIMIT] lookups per [WINDOW_MS] (docs/sefaz-sc.md, working hypothesis from two blocks).
 * This keeps a log of our own lookups per network so the app can pace page fetches. It only advises: the user can
 * always fetch anyway (e.g. after switching SIM, which is a different IP).
 *
 * A "lookup" is a main-frame request to SEFAZ: opening a receipt's QR URL, or the Validar postback after a challenge.
 * Storage is a plain string so it can live in SharedPreferences; see [encode]/[decode].
 */
class LookupBudget(private val load: () -> String, private val save: (String) -> Unit, private val now: () -> Long = System::currentTimeMillis) {

  data class Status(val used: Int, val remaining: Int, val blocked: Boolean, val nextFreeAt: Long?)

  private data class Entry(val at: Long, val network: String, val blocked: Boolean)

  @Synchronized
  fun record(network: String) = update { it + Entry(now(), network, blocked = false) }

  /** SEFAZ showed its rate-limit page on this network: treat the budget as used up for a full window. */
  @Synchronized
  fun markBlocked(network: String) = update { it + Entry(now(), network, blocked = true) }

  @Synchronized
  fun status(network: String): Status {
    val t = now()
    val recent = decode(load()).filter { it.network == network && t - it.at < WINDOW_MS }
    val block = recent.filter { it.blocked }.maxOfOrNull { it.at }
    if (block != null) return Status(used = LIMIT, remaining = 0, blocked = true, nextFreeAt = block + WINDOW_MS)
    val lookups = recent.map { it.at }.sorted()
    val remaining = (LIMIT - lookups.size).coerceAtLeast(0)
    // When full, a slot frees up as the oldest lookup in the window ages out.
    val nextFreeAt = if (remaining > 0) null else lookups[lookups.size - LIMIT] + WINDOW_MS
    return Status(used = lookups.size, remaining = remaining, blocked = false, nextFreeAt = nextFreeAt)
  }

  private fun update(change: (List<Entry>) -> List<Entry>) {
    val t = now()
    save(encode(change(decode(load())).filter { t - it.at < WINDOW_MS }))
  }

  private fun encode(entries: List<Entry>) = entries.joinToString("\n") { "${it.at}|${it.network}|${if (it.blocked) 1 else 0}" }

  private fun decode(s: String) =
    s.lineSequence().mapNotNull { line ->
      val p = line.split("|")
      if (p.size == 3) p[0].toLongOrNull()?.let { Entry(it, p[1], p[2] == "1") } else null
    }.toList()

  companion object {
    const val LIMIT = 5
    const val WINDOW_MS = 60 * 60 * 1000L
  }
}
