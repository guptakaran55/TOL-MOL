package com.signalscope.app.ui

/**
 * LIVE PRICES TO THE PAGE, A BATCH AT A TIME.
 *
 * Every tick from every stream (Yahoo, Finnhub, Kite) used to be its own task on
 * the main thread — serialised there, then handed to the page in its own
 * evaluateJavascript call. With dozens of symbols streaming that is dozens of
 * tasks a second on the thread that also delivers your taps, so a tap waited in
 * line behind prices.
 *
 * Now each tick is serialised on the stream's own thread and left here, and the
 * page gets everything waiting in ONE call, twice a second.
 *
 * EVERY TICK, IN ORDER — not just the latest per symbol. The page's price history
 * keeps each minute's high and low across all the ticks inside it, so dropping a
 * superseded tick could lose an extreme on a feed that sends prices without a day
 * high/low. The page is handed exactly the calls it always got, one after another.
 */
class LiveQuoteBatch(private val max: Int = MAX_PENDING) {
    /** (symbol, quote JSON), in arrival order. */
    private val pending = ArrayList<Pair<String, String>>()

    /**
     * Add one tick. @return true when it starts a new batch — the caller then
     * schedules its delivery; false when a delivery is already due.
     */
    @Synchronized
    fun offer(symbol: String, json: String): Boolean {
        val wasEmpty = pending.isEmpty()
        if (pending.size >= max) dropOneSuperseded()
        pending.add(symbol.trim().uppercase() to json)
        return wasEmpty
    }

    /**
     * Only when the page has not taken a batch for a long while (the main thread
     * stuck for many seconds): drop the oldest tick that a later tick of the same
     * symbol supersedes, so the backlog cannot grow without bound. The page's
     * latest price for every symbol is unaffected.
     */
    private fun dropOneSuperseded() {
        val seenLater = HashSet<String>()
        var victim = -1
        for (i in pending.indices.reversed()) {
            val sym = pending[i].first
            if (sym in seenLater) victim = i      // ends on the OLDEST superseded tick
            seenLater.add(sym)
        }
        if (victim >= 0) pending.removeAt(victim)
    }

    /** Everything waiting, oldest first, and the batch emptied. */
    @Synchronized
    fun drain(): List<String> {
        if (pending.isEmpty()) return emptyList()
        val out = pending.map { it.second }
        pending.clear()
        return out
    }

    @Synchronized
    fun size(): Int = pending.size

    companion object {
        /** How long a batch collects before it goes to the page. */
        const val INTERVAL_MS = 500L

        /** Far above any half-second of ticks; reached only if the page stops
         *  taking batches. See [dropOneSuperseded]. */
        const val MAX_PENDING = 5000

        /** One quote's JSON made safe inside a single-quoted JavaScript string —
         *  exactly as each tick was escaped when it was sent on its own. */
        fun escape(json: String): String = json.replace("\\", "\\\\").replace("'", "\\'")

        /** One script for the whole batch: the same call, with the same argument,
         *  that each tick used to get — one after another. */
        fun script(batch: List<String>): String =
            batch.joinToString("\n") { "window.updateLiveQuote && window.updateLiveQuote('${escape(it)}');" }
    }
}
