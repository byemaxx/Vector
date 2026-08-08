package org.matrix.vector.daemon.system

import android.os.SystemClock
import io.github.libxposed.service.IXposedScopeCallback

/**
 * Tracks unanswered scope prompts so one request receives exactly one answer.
 *
 * Adapted from JingMatrix/Vector commit 4fcea0e528e737efb5fdadf02ee7fd47d55d527b.
 * Vector-SR keeps the bookkeeping outside NotificationManager to avoid importing unrelated daemon
 * notification changes from the newer Manager architecture.
 */
internal object ScopeRequestTracker {
  const val TIMEOUT_MS = 60L * 60 * 1000
  private const val MAX_OPEN_PER_MODULE = 16
  private const val MAX_ENTRIES = 512

  data class Abandoned(val tag: String, val callback: IXposedScopeCallback)

  private data class Open(val callback: IXposedScopeCallback, val postedAt: Long)

  private val open = LinkedHashMap<String, Open>()

  private fun countOf(modulePkg: String): Int {
    val stillLiveAfter = SystemClock.elapsedRealtime() - TIMEOUT_MS
    return open.count { (tag, value) ->
      tag.startsWith("$modulePkg:") && value.postedAt > stillLiveAfter
    }
  }

  /**
   * Registers a prompt before it is posted.
   *
   * null means the new prompt is refused by the per-module ceiling. Reposting the same canonical
   * tag replaces the prior callback without consuming a second slot because it is the same user
   * question and will replace the same notification/PendingIntent.
   */
  @Synchronized
  fun post(tag: String, callback: IXposedScopeCallback): List<Abandoned>? {
    val replaced = open.remove(tag) != null
    val modulePkg = tag.substringBefore(':')
    if (!replaced && countOf(modulePkg) >= MAX_OPEN_PER_MODULE) return null

    open[tag] = Open(callback, SystemClock.elapsedRealtime())
    val abandoned = mutableListOf<Abandoned>()
    while (open.size > MAX_ENTRIES) {
      val oldest = open.keys.first()
      abandoned += Abandoned(oldest, open.remove(oldest)!!.callback)
    }
    return abandoned
  }

  /** Takes the right to answer one prompt. A second button/timeout sees null. */
  @Synchronized
  fun claim(tag: String): IXposedScopeCallback? = open.remove(tag)?.callback

  /** Takes every still-unanswered prompt belonging to one module. */
  @Synchronized
  fun claimAllOf(modulePkg: String): Map<String, IXposedScopeCallback> {
    val mine = open.filterKeys { it.startsWith("$modulePkg:") }
    mine.keys.forEach(open::remove)
    return mine.mapValues { it.value.callback }
  }
}
