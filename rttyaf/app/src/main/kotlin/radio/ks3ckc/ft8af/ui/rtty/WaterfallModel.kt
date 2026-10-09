package radio.ks3ckc.ft8af.ui.rtty

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Plain-Kotlin geometry and pixel logic behind the Operate-screen waterfall,
 * kept out of the Composable so it can be unit-tested: the zoomable frequency
 * viewport, axis tick selection, the scrolling pixel image, the colour ramp
 * and the single-finger gesture decision (tap / drag-tune / pan).
 */

/**
 * The slice of the audio passband the waterfall shows, in Hz. Pinch zooms,
 * drag pans, and both are clamped to `0..maxHz` with a minimum span so the
 * user can't zoom into a single bin or scroll off the band.
 */
data class WaterfallViewport(
    val loHz: Double,
    val hiHz: Double,
    val maxHz: Double = 3000.0,
    val minSpanHz: Double = 400.0,
) {
    init {
        require(hiHz > loHz) { "viewport must have positive span" }
    }

    val spanHz: Double get() = hiHz - loHz

    /** 0..1 position of [hz] across the view (may fall outside 0..1 if off-screen). */
    fun fractionOf(hz: Double): Float = ((hz - loHz) / spanHz).toFloat()

    /** Frequency at a 0..1 position across the view. */
    fun hzAt(fraction: Float): Double = loHz + fraction * spanHz

    fun contains(hz: Double): Boolean = hz >= loHz && hz <= hiHz

    /**
     * Zoom by [factor] (>1 zooms in) about the frequency at [centreFraction],
     * so the tone under the user's fingers stays put.
     */
    fun zoom(factor: Float, centreFraction: Float): WaterfallViewport {
        if (factor <= 0f || factor == 1f) return this
        val pivot = hzAt(centreFraction)
        val span = (spanHz / factor).coerceIn(minSpanHz, maxHz)
        val lo = pivot - centreFraction * span
        return clamped(lo, lo + span)
    }

    /** Pan by [deltaFraction] of the current span (positive moves the view up in frequency). */
    fun pan(deltaFraction: Float): WaterfallViewport {
        if (deltaFraction == 0f) return this
        val shift = deltaFraction * spanHz
        return clamped(loHz + shift, hiHz + shift)
    }

    private fun clamped(lo: Double, hi: Double): WaterfallViewport {
        val span = (hi - lo).coerceIn(minSpanHz, maxHz)
        var l = lo
        if (l < 0.0) l = 0.0
        if (l + span > maxHz) l = maxHz - span
        return copy(loHz = l, hiHz = l + span)
    }

    companion object {
        /** Default view: trims the sub-500 Hz rumble and the top of the filter so a 170 Hz pair is readable. */
        fun default(maxHz: Double = 3000.0) = WaterfallViewport(500.0, 2800.0, maxHz)
    }
}

/**
 * Pick round-number frequency labels for the span [loHz]..[hiHz]: the coarsest
 * step from [steps] that yields at most [maxTicks] labels, so a zoomed-in view
 * labels every 100 Hz and the full band every 500 Hz.
 */
fun axisTicks(loHz: Double, hiHz: Double, maxTicks: Int = 7, steps: List<Int> = listOf(100, 200, 250, 500, 1000)): List<Int> {
    if (hiHz <= loHz) return emptyList()
    for (step in steps) {
        val first = ceil(loHz / step).toInt() * step
        val last = floor(hiHz / step).toInt() * step
        if (last < first) continue
        val count = (last - first) / step + 1
        if (count <= maxTicks) return (first..last step step).toList()
    }
    val step = steps.last()
    val first = ceil(loHz / step).toInt() * step
    val last = floor(hiHz / step).toInt() * step
    return if (last < first) emptyList() else (first..last step step).toList()
}

/**
 * Scrolling waterfall pixels: a [width] × [height] ARGB image where the newest
 * spectrum column is the bottom row and older rows move up. Backed by a flat
 * [pixels] array in row-major order ready for `Bitmap.setPixels`.
 */
class WaterfallImage(val width: Int, val height: Int, private val ramp: (Float) -> Int) {
    init {
        require(width > 0 && height > 0) { "image must be non-empty" }
    }

    val pixels = IntArray(width * height)

    /** Number of columns pushed so far (capped at [height]); rows above this are still blank. */
    var filledRows: Int = 0
        private set

    /** Append one spectrum column (sized [width]; shorter/longer input is truncated or padded). */
    fun push(column: FloatArray) {
        System.arraycopy(pixels, width, pixels, 0, width * (height - 1))
        val base = width * (height - 1)
        for (x in 0 until width) {
            pixels[base + x] = ramp(if (x < column.size) column[x] else 0f)
        }
        if (filledRows < height) filledRows++
    }

    /** Pixel at ([x], [y]) with y = 0 at the oldest (top) row. */
    fun pixel(x: Int, y: Int): Int = pixels[y * width + x]
}

/**
 * Waterfall colour ramp as packed ARGB: fully transparent at or below the noise
 * floor (so the card background shows through), [bgArgb]→[signalArgb] over the
 * lower half of the range, [signalArgb]→white over the upper half.
 */
fun waterfallArgb(level: Float, bgArgb: Int, signalArgb: Int): Int {
    if (level <= 0.02f) return 0
    val l = level.coerceIn(0f, 1f)
    return if (l < 0.5f) lerpArgb(bgArgb, signalArgb, l * 2f) else lerpArgb(signalArgb, 0xFFFFFFFF.toInt(), (l - 0.5f) * 2f)
}

/** Per-channel linear interpolation of two packed ARGB ints. */
fun lerpArgb(a: Int, b: Int, t: Float): Int {
    val f = t.coerceIn(0f, 1f)
    fun ch(shift: Int): Int {
        val ca = (a ushr shift) and 0xFF
        val cb = (b ushr shift) and 0xFF
        return (ca + (cb - ca) * f + 0.5f).toInt().coerceIn(0, 255)
    }
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** What a single-finger gesture on the waterfall turns into. */
enum class WaterfallGesture { TAP, DRAG_TUNE, PAN }

/**
 * Straight-line distance a finger has travelled from where it went down, in
 * px. Both axes count: a vertical swipe must break the tap threshold just as
 * a horizontal one does, otherwise lifting after a scroll would retune.
 */
fun gestureTravelPx(downX: Float, downY: Float, x: Float, y: Float): Float = hypot(x - downX, y - downY)

/**
 * Classify a finished or in-progress single-finger gesture. A finger that
 * never moved past [touchSlopPx] is a tap. One that started within
 * [grabRadiusPx] of the tuning cursor (its mark or space line, at [cursorXs])
 * drags the tuning; anywhere else it pans the view.
 */
fun classifyGesture(startX: Float, maxTravelPx: Float, cursorXs: List<Float>, touchSlopPx: Float, grabRadiusPx: Float): WaterfallGesture {
    if (maxTravelPx <= touchSlopPx) return WaterfallGesture.TAP
    val nearCursor = cursorXs.any { abs(it - startX) <= grabRadiusPx }
    return if (nearCursor) WaterfallGesture.DRAG_TUNE else WaterfallGesture.PAN
}
