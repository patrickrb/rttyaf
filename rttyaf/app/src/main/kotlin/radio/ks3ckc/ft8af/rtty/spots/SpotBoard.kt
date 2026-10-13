package radio.ks3ckc.ft8af.rtty.spots

import kotlin.math.abs

/**
 * The merged, de-duplicated set of live RTTY spots from every source.
 *
 * Reports for the same callsign within [mergeHz] of each other are one
 * station: the merged spot keeps the freshest frequency and time, the best
 * SNR, the union of spotters, and RBN as the source if either report was RBN
 * (a skimmer's frequency is more trustworthy than a reception report's).
 * Spots older than [maxAgeMs] are dropped by [prune]. Thread-safe.
 */
class SpotBoard(
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val mergeHz: Long = 300L,
) {
    private val spots = ArrayList<RttySpot>()

    @Synchronized
    fun add(spot: RttySpot) {
        val i = spots.indexOfFirst { it.call == spot.call && abs(it.freqHz - spot.freqHz) <= mergeHz }
        if (i < 0) {
            spots.add(spot)
            return
        }
        val old = spots[i]
        val newer = if (spot.lastHeardMs >= old.lastHeardMs) spot else old
        spots[i] = RttySpot(
            call = old.call,
            freqHz = if (newer.source == SpotSource.RBN || old.source != SpotSource.RBN) newer.freqHz else old.freqHz,
            source = if (old.source == SpotSource.RBN || spot.source == SpotSource.RBN) SpotSource.RBN else SpotSource.PSK,
            snrDb = listOfNotNull(old.snrDb, spot.snrDb).maxOrNull(),
            baud = spot.baud ?: old.baud,
            spotters = old.spotters + spot.spotters,
            lastHeardMs = maxOf(old.lastHeardMs, spot.lastHeardMs),
            isCq = newer.isCq,
        )
    }

    @Synchronized
    fun addAll(list: List<RttySpot>) = list.forEach { add(it) }

    /** Drop spots not heard within [maxAgeMs] of [nowMs]. Returns how many were removed. */
    @Synchronized
    fun prune(nowMs: Long): Int {
        val before = spots.size
        spots.removeAll { nowMs - it.lastHeardMs > maxAgeMs }
        return before - spots.size
    }

    @Synchronized
    fun clear() = spots.clear()

    /** Every live spot, ranked by [rank]. */
    @Synchronized
    fun snapshot(nowMs: Long): List<RttySpot> = spots.sortedWith(rank(nowMs))

    /** Spots inside [segment], ranked. */
    @Synchronized
    fun inSegment(segment: RttySegment, nowMs: Long): List<RttySpot> =
        spots.filter { segment.contains(it.freqHz) }.sortedWith(rank(nowMs))

    @Synchronized
    fun size(): Int = spots.size

    companion object {
        const val DEFAULT_MAX_AGE_MS = 15L * 60_000L

        /**
         * "What should I work next" order: fresh (< 2 min) RBN spots first, then
         * everything else by recency, with more skimmers breaking ties.
         */
        fun rank(nowMs: Long): Comparator<RttySpot> = compareBy<RttySpot> { spot ->
            val fresh = nowMs - spot.lastHeardMs < FRESH_MS
            if (fresh && spot.source == SpotSource.RBN) 0 else 1
        }.thenByDescending { it.lastHeardMs }.thenByDescending { it.spotters.size }

        const val FRESH_MS = 2L * 60_000L
    }
}
