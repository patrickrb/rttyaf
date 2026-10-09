package radio.ks3ckc.ft8af.rtty

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Pure spectral helpers that make RTTY signals *findable*: noise-floor-relative
 * display scaling, peak snapping for tap-to-tune, a mark/space pair scanner
 * that flags likely RTTY signals on the waterfall, and an AFC tracker that
 * keeps the demodulator centred on a signal once it has been tuned.
 *
 * Everything here works on a display column: a [FloatArray] of per-bin levels
 * where index `i` is the bin centred at `i * binHz`. Levels are 0..1 where 0 is
 * at or below the tracked noise floor and 1 is [WaterfallScaler.rangeDb] above
 * it (see [WaterfallScaler]). No Android types — all unit-tested.
 */

/**
 * Converts raw linear FFT magnitudes to a 0..1 display level relative to a
 * slowly tracked noise floor.
 *
 * The previous display normalised each column to its own maximum, so an empty
 * band looked as busy as a crowded one and a weak station next to a strong one
 * vanished. Here each bin is expressed in dB above the floor over a fixed
 * [rangeDb] span, so brightness means the same thing from one column to the
 * next and signals stand out against noise regardless of band activity.
 *
 * The floor is the median of each column's dB values (robust to a few strong
 * carriers) blended into a running estimate with [floorAlpha] per column.
 *
 * @param rangeDb    dB span mapped onto the 0..1 display level
 * @param floorAlpha per-column blend weight for the running floor (0..1)
 */
class WaterfallScaler(
    val rangeDb: Double = 40.0,
    private val floorAlpha: Double = 0.05,
) {
    init {
        require(rangeDb > 0.0) { "rangeDb must be > 0" }
        require(floorAlpha > 0.0 && floorAlpha <= 1.0) { "floorAlpha must be in (0, 1]" }
    }

    /** Current noise-floor estimate in dB, or null before the first column. */
    var floorDb: Double? = null
        private set

    private var scratch = DoubleArray(0)

    /** Scale one column of linear magnitudes into display levels (new array). */
    fun scale(linear: DoubleArray): FloatArray {
        if (linear.isEmpty()) return FloatArray(0)
        if (scratch.size != linear.size) scratch = DoubleArray(linear.size)
        val db = DoubleArray(linear.size) { 20.0 * log10(linear[it] + 1e-9) }
        System.arraycopy(db, 0, scratch, 0, db.size)
        scratch.sort()
        val median = scratch[scratch.size / 2]
        val prev = floorDb
        val floor = if (prev == null) median else prev + floorAlpha * (median - prev)
        floorDb = floor
        return FloatArray(db.size) { ((db[it] - floor) / rangeDb).toFloat().coerceIn(0f, 1f) }
    }

    fun reset() {
        floorDb = null
    }
}

/** Index of the strongest bin within ±[windowBins] of [centreBin], or -1 if none is ≥ [minLevel]. */
internal fun strongestBin(spectrum: FloatArray, centreBin: Int, windowBins: Int, minLevel: Float): Int {
    var best = -1
    var bestLevel = minLevel
    val lo = (centreBin - windowBins).coerceAtLeast(0)
    val hi = (centreBin + windowBins).coerceAtMost(spectrum.size - 1)
    for (b in lo..hi) {
        if (spectrum[b] >= bestLevel) {
            bestLevel = spectrum[b]
            best = b
        }
    }
    return best
}

/**
 * Snap [targetHz] to the strongest spectral peak within ±[windowHz], so a thumb
 * tap lands on the signal rather than beside it. Returns null when nothing in
 * the window reaches [minLevel] (i.e. the user tapped empty band).
 */
fun nearestPeakHz(
    spectrum: FloatArray,
    binHz: Double,
    targetHz: Double,
    windowHz: Double = 50.0,
    minLevel: Float = 0.3f,
): Double? {
    if (spectrum.isEmpty() || binHz <= 0.0) return null
    val centre = (targetHz / binHz).roundToInt()
    val window = (windowHz / binHz).roundToInt().coerceAtLeast(1)
    val b = strongestBin(spectrum, centre, window, minLevel)
    return if (b < 0) null else b * binHz
}

/**
 * Decide which MARK (lower-tone) frequency a tap at [tappedHz] should tune to.
 *
 * The tapped frequency is snapped to the nearest peak; then we look for its
 * partner tone one [shiftHz] above (tapped is mark) or below (tapped is space)
 * and pick the stronger partner so the user may tap *either* line of a pair.
 * With no partner — or empty band — the tapped/snapped frequency is treated as
 * the mark. The result is clamped so both tones stay inside [minHz, maxHz].
 */
fun resolveMarkHz(
    spectrum: FloatArray,
    binHz: Double,
    tappedHz: Double,
    shiftHz: Int,
    minHz: Double = 100.0,
    maxHz: Double = 3000.0,
): Double {
    val snapped = nearestPeakHz(spectrum, binHz, tappedHz) ?: tappedHz
    var mark = snapped
    if (spectrum.isNotEmpty() && binHz > 0.0) {
        // Partner search window: ~15 % of the shift, but never narrower than 1.5 bins.
        val window = maxOf(shiftHz * 0.15, 1.5 * binHz)
        val above = nearestPeakHz(spectrum, binHz, snapped + shiftHz, window)
        val below = nearestPeakHz(spectrum, binHz, snapped - shiftHz, window)
        val aboveLevel = above?.let { spectrum[(it / binHz).roundToInt()] } ?: 0f
        val belowLevel = below?.let { spectrum[(it / binHz).roundToInt()] } ?: 0f
        if (below != null && belowLevel > aboveLevel) mark = snapped - shiftHz
    }
    return clampMarkHz(mark, shiftHz, minHz, maxHz)
}

/** Clamp a mark frequency so mark and mark+shift both lie within [minHz, maxHz]. */
fun clampMarkHz(markHz: Double, shiftHz: Int, minHz: Double = 100.0, maxHz: Double = 3000.0): Double =
    markHz.coerceIn(minHz, (maxHz - shiftHz).coerceAtLeast(minHz))

/** A likely RTTY signal: two tones [shiftHz] apart, both above the noise. */
data class RttyCandidate(val markHz: Double, val spaceHz: Double, val strength: Float)

/**
 * Scan a display column for tone pairs separated by [shiftHz] — the signature
 * of an RTTY signal — and return them strongest-first.
 *
 * A bin is a peak when it reaches [minLevel] and is not lower than its
 * neighbours. For each peak we look for a partner peak within ±[toleranceBins]
 * of `peak + shift`; the pair's strength is the weaker of the two levels.
 * Overlapping pairs — closer than half a shift, or sharing a tone (one shift
 * apart, e.g. a three-line comb) — collapse to the stronger one.
 * Because only one tone is keyed at any instant, callers should pass a column
 * averaged over a few FFT frames so both tones are present together.
 */
fun findRttyCandidates(
    spectrum: FloatArray,
    binHz: Double,
    shiftHz: Int,
    minLevel: Float = 0.35f,
    maxCount: Int = 6,
    toleranceBins: Int = 1,
): List<RttyCandidate> {
    if (spectrum.size < 3 || binHz <= 0.0 || shiftHz <= 0) return emptyList()
    val shiftBins = (shiftHz / binHz).roundToInt()
    if (shiftBins < 2) return emptyList()
    val peaks = ArrayList<Int>()
    for (b in 1 until spectrum.size - 1) {
        val v = spectrum[b]
        if (v >= minLevel && v >= spectrum[b - 1] && v >= spectrum[b + 1]) peaks.add(b)
    }
    val found = ArrayList<RttyCandidate>()
    for (p in peaks) {
        val partner = strongestBin(spectrum, p + shiftBins, toleranceBins, minLevel)
        if (partner < 0) continue
        found.add(RttyCandidate(p * binHz, p * binHz + shiftHz, minOf(spectrum[p], spectrum[partner])))
    }
    found.sortByDescending { it.strength }
    val kept = ArrayList<RttyCandidate>()
    val minSeparation = shiftHz / 2.0
    val sharedToneTolerance = toleranceBins * binHz + 0.5
    fun overlaps(a: RttyCandidate, b: RttyCandidate): Boolean {
        val d = abs(a.markHz - b.markHz)
        return d < minSeparation || abs(d - shiftHz) <= sharedToneTolerance
    }
    for (c in found) {
        if (kept.size >= maxCount) break
        if (kept.none { overlaps(it, c) }) kept.add(c)
    }
    return kept
}

/**
 * Automatic frequency control: nudges the tuned mark frequency toward the
 * energy centroid of the signal around the current mark/space tones so a
 * drifting (or slightly mistuned) station stays centred in the demodulator.
 *
 * Each [update] measures the level-weighted offset of the energy within
 * ±[searchHz] of the mark tone and of the space tone, averages the two (both
 * tones move together — the shift is fixed), and applies [gain] of it, limited
 * to [maxStepHz] per update. Total excursion from the [anchorHz] the operator
 * tuned to is capped at ±[maxDriftHz] so AFC can never wander off onto a
 * neighbouring station. Nothing happens unless a tone in the search window
 * reaches [minLevel], so AFC stays put on an empty channel.
 */
class AfcTracker(
    val maxDriftHz: Double = 100.0,
    val maxStepHz: Double = 10.0,
    val searchHz: Double = 40.0,
    val gain: Double = 0.3,
    val minLevel: Float = 0.35f,
) {
    /** The operator-chosen mark frequency AFC is allowed to drift around. */
    var anchorHz: Double = 0.0
        private set

    /** Re-centre on a freshly tuned frequency. */
    fun anchor(markHz: Double) {
        anchorHz = markHz
    }

    /**
     * Compute the corrected mark frequency for the current column.
     * Returns [markHz] unchanged when no signal is present or no correction is needed.
     */
    fun update(spectrum: FloatArray, binHz: Double, markHz: Double, shiftHz: Int): Double {
        if (spectrum.isEmpty() || binHz <= 0.0) return markHz
        val markOffset = centroidOffset(spectrum, binHz, markHz)
        val spaceOffset = centroidOffset(spectrum, binHz, markHz + shiftHz)
        val offset = when {
            markOffset != null && spaceOffset != null -> (markOffset + spaceOffset) / 2.0
            markOffset != null -> markOffset
            spaceOffset != null -> spaceOffset
            else -> return markHz
        }
        val step = (offset * gain).coerceIn(-maxStepHz, maxStepHz)
        val proposed = (markHz + step).coerceIn(anchorHz - maxDriftHz, anchorHz + maxDriftHz)
        return proposed
    }

    /**
     * Level-weighted centroid of the energy within ±[searchHz] of [toneHz],
     * expressed as an offset from [toneHz]; null when no bin reaches [minLevel].
     */
    private fun centroidOffset(spectrum: FloatArray, binHz: Double, toneHz: Double): Double? {
        val centre = (toneHz / binHz).roundToInt()
        val window = (searchHz / binHz).roundToInt().coerceAtLeast(1)
        val lo = (centre - window).coerceAtLeast(0)
        val hi = (centre + window).coerceAtMost(spectrum.size - 1)
        if (lo > hi) return null
        var peak = 0f
        for (b in lo..hi) if (spectrum[b] > peak) peak = spectrum[b]
        if (peak < minLevel) return null
        // Only weight bins that are part of the signal (within 6 dB-ish of the
        // peak in display terms) so the noise shoulders don't bias the centroid.
        val cut = peak * 0.7f
        var sum = 0.0
        var weight = 0.0
        for (b in lo..hi) {
            val v = spectrum[b]
            if (v < cut) continue
            sum += v * (b * binHz - toneHz)
            weight += v
        }
        return if (weight <= 0.0) null else sum / weight
    }
}
