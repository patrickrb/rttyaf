package radio.ks3ckc.ft8af.rtty

import kotlin.math.abs
import kotlin.math.max

/**
 * The classic RTTY **XY tuning scope** ("crossed ellipses"), as found in
 * MMTTY, fldigi and every hardware TU since the ST-6.
 *
 * X is the output of the MARK tone filter, Y the output of the SPACE tone
 * filter. While a mark bit is keyed the trace swings along X; during a space
 * bit it swings along Y, so a correctly tuned signal draws a symmetric cross.
 * Tune off frequency and the arm whose tone has left its filter shrinks or
 * disappears; a signal with the wrong shift lights only one arm. Operators
 * read this at a glance far faster than a waterfall.
 *
 * [XyTrace] is the ring buffer the demodulator fills (every input sample by
 * default — decimating would alias: at a 3 kHz point rate a 3000 Hz space tone
 * is sampled at one fixed phase and its arm collapses to a dot even when
 * perfectly tuned); [normalizeXy] auto-gains a snapshot for display and
 * [xyArms] measures the two arms for tests and any future "tuned" cue.
 */
class XyTrace(val capacity: Int = DEFAULT_CAPACITY, val decimate: Int = DEFAULT_DECIMATE) {
    init {
        require(capacity > 0) { "capacity must be > 0" }
        require(decimate > 0) { "decimate must be > 0" }
    }

    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private var head = 0
    private var count = 0
    private var skip = 0

    /** Offer one demodulator sample; every [decimate]-th call is kept. */
    fun push(x: Float, y: Float) {
        if (++skip < decimate) return
        skip = 0
        xs[head] = x
        ys[head] = y
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    val size: Int get() = count

    /** Interleaved `x0,y0,x1,y1,…`, oldest first. New array each call (safe to hand to the UI). */
    fun snapshot(): FloatArray {
        val out = FloatArray(count * 2)
        val start = (head - count + capacity) % capacity
        for (i in 0 until count) {
            val idx = (start + i) % capacity
            out[2 * i] = xs[idx]
            out[2 * i + 1] = ys[idx]
        }
        return out
    }

    fun clear() {
        head = 0
        count = 0
        skip = 0
    }

    companion object {
        /** ~170 ms of trace at 12 kHz — a few characters' worth. */
        const val DEFAULT_CAPACITY = 2048

        /** Keep every sample: any decimation puts a tone at `rate / decimate / 2` or its multiples on a fixed phase. */
        const val DEFAULT_DECIMATE = 1
    }
}

/** Peak excursion along each axis of an interleaved trace. */
data class XyArms(val mark: Float, val space: Float) {
    /** Larger of the two arms (the display's auto-gain reference). */
    val peak: Float get() = max(mark, space)
}

/** Measure the MARK (x) and SPACE (y) arms of an interleaved trace. */
fun xyArms(points: FloatArray): XyArms {
    var mx = 0f
    var my = 0f
    var i = 0
    while (i + 1 < points.size) {
        mx = max(mx, abs(points[i]))
        my = max(my, abs(points[i + 1]))
        i += 2
    }
    return XyArms(mx, my)
}

/**
 * Auto-gain an interleaved trace for display: scale so the larger arm reaches
 * ±1, preserving the ratio between the arms (that ratio *is* the tuning
 * information). Traces whose peak is under [floor] are treated as no-signal
 * and returned as all zeros rather than blown up into a noise ball.
 */
fun normalizeXy(points: FloatArray, floor: Float = 1e-3f): FloatArray {
    val peak = xyArms(points).peak
    if (peak < floor) return FloatArray(points.size)
    val g = 1f / peak
    return FloatArray(points.size) { (points[it] * g).coerceIn(-1f, 1f) }
}
