package radio.ks3ckc.ft8af.rtty

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * RTTY (Baudot/ITA2) AFSK demodulator with soft tone detection and asynchronous
 * bit/clock recovery.
 *
 * ## Tone detection
 * Two quadrature (I/Q) product detectors — one tuned to the mark tone, one to
 * the space tone — each followed by a one-pole low-pass smoother produce a
 * running estimate of the energy at each tone. Their normalised difference
 * feeds a Schmitt-trigger slicer (hysteresis) whose boolean output is the
 * instantaneous mark/space decision. Sampling at bit *centres* keeps the slicer
 * away from the LPF transients at each transition.
 *
 * ## Clock recovery / framing
 * The demodulator is asynchronous: it idles in [Mode.SEARCH] watching for a
 * mark→space edge (the leading edge of a START bit). On that edge it switches
 * to [Mode.RECEIVE] and samples the slicer at fixed offsets from the edge —
 * the middle of the start bit (validation), the middle of each of the 5 data
 * bits, and inside the stop bit (validation) — reassembling the 5-bit code
 * LSB-first. This tolerates a few percent of clock error because each frame
 * re-syncs to its own start edge rather than a free-running clock.
 *
 * State (filters, slicer, shift, partial frame) persists across [process]
 * calls, so audio can be streamed in arbitrarily-sized chunks.
 *
 * @param config modem parameters
 * @param onChar optional per-character sink; [process] also returns the chunk
 */
class RttyDecoder(
    val config: RttyConfig,
    private val onChar: ((Char) -> Unit)? = null,
) {
    private enum class Mode { SEARCH, RECEIVE }

    // --- tone detectors -----------------------------------------------------
    private val markInc = 2.0 * PI * config.markToneHz / config.sampleRate
    private val spaceInc = 2.0 * PI * config.spaceToneHz / config.sampleRate
    // One-pole smoother with a corner near the baud rate: fast enough to settle
    // within a bit, slow enough to reject the tone's own ripple.
    private val lpAlpha = exp(-2.0 * PI * config.baudRate / config.sampleRate)
    private val spb = config.samplesPerBit

    private var markAngle = 0.0
    private var spaceAngle = 0.0
    private var iMark = 0.0
    private var qMark = 0.0
    private var iSpace = 0.0
    private var qSpace = 0.0

    // --- slicer -------------------------------------------------------------
    /** Dead-band half-width (fraction of total energy) for the Schmitt trigger. */
    private val hysteresis = 0.1
    private var curMark = true

    // --- squelch (energy gate) ---------------------------------------------
    // Without this the slicer decides purely on the *normalised* mark/space
    // difference, so on an idle/noisy channel the ratio still crosses the
    // hysteresis band and the framer prints random characters. A light EMA of
    // the total tone energy, compared against [RttyConfig.squelchFloor], gates
    // edge detection and emission so we only decode when a carrier is present.
    private var energyEma = 0.0
    private var squelchOpen = false

    // --- clock recovery / framing ------------------------------------------
    private var mode = Mode.SEARCH
    private var prevMark = true
    // Sample offsets from the start edge: the start-bit centre (validation) plus
    // the five data-bit centres. We emit and re-sync right after the last data
    // bit rather than waiting for a fixed stop-bit point, so the framer never
    // over-runs the next start edge when stopBits < 0.5 (the stop level isn't
    // needed to decode — real links lose it under noise anyway).
    private val sampleOffsets: IntArray = IntArray(6) { i -> ((i + 0.5) * spb).roundToInt() }
    private var samplesSinceEdge = 0
    private var nextTarget = 0
    private var codeBits = 0

    // --- ITA2 shift state ---------------------------------------------------
    private var figures = false

    /** Reset all demodulator state to its power-on condition. */
    fun reset() {
        markAngle = 0.0; spaceAngle = 0.0
        iMark = 0.0; qMark = 0.0; iSpace = 0.0; qSpace = 0.0
        curMark = true
        energyEma = 0.0
        squelchOpen = false
        mode = Mode.SEARCH
        prevMark = true
        samplesSinceEdge = 0
        nextTarget = 0
        codeBits = 0
        figures = false
    }

    /** Feed 16-bit PCM; see [process] (FloatArray). */
    fun process(samples: ShortArray): String {
        val f = FloatArray(samples.size)
        for (i in samples.indices) f[i] = samples[i] / 32768.0f
        return process(f)
    }

    /**
     * Feed a chunk of mono audio. Returns the text decoded from this chunk
     * (possibly empty); each character is also delivered to [onChar].
     */
    fun process(samples: FloatArray): String {
        val sb = StringBuilder()
        for (s in samples) {
            updateSlicer(s.toDouble())
            when (mode) {
                Mode.SEARCH -> {
                    // Falling edge (mark→space) starts a frame — but only when the
                    // squelch is open, so noise never triggers a frame.
                    if (squelchOpen && prevMark && !curMark) {
                        mode = Mode.RECEIVE
                        samplesSinceEdge = 0
                        nextTarget = 0
                        codeBits = 0
                    }
                }
                Mode.RECEIVE -> {
                    samplesSinceEdge++
                    // A rounded offset can coincide; use a while-loop so we never
                    // skip a sampling point.
                    while (nextTarget < sampleOffsets.size &&
                        samplesSinceEdge >= sampleOffsets[nextTarget]
                    ) {
                        onSamplePoint(nextTarget, sb)
                        nextTarget++
                        if (mode != Mode.RECEIVE) break // aborted (bad start bit)
                    }
                }
            }
            prevMark = curMark
        }
        return sb.toString()
    }

    /** Advance the I/Q detectors and Schmitt slicer by one input sample. */
    private fun updateSlicer(x: Double) {
        val mc = cos(markAngle); val ms = sin(markAngle)
        val sc = cos(spaceAngle); val ss = sin(spaceAngle)
        markAngle += markInc; if (markAngle >= 2.0 * PI) markAngle -= 2.0 * PI
        spaceAngle += spaceInc; if (spaceAngle >= 2.0 * PI) spaceAngle -= 2.0 * PI

        // One-pole LPF: y += (1-a)*(x - y).
        iMark += (1 - lpAlpha) * (x * mc - iMark)
        qMark += (1 - lpAlpha) * (x * ms - qMark)
        iSpace += (1 - lpAlpha) * (x * sc - iSpace)
        qSpace += (1 - lpAlpha) * (x * ss - qSpace)

        val markMag = iMark * iMark + qMark * qMark
        val spaceMag = iSpace * iSpace + qSpace * qSpace
        val total = markMag + spaceMag + 1e-12

        // Squelch: a light EMA of the tone energy, gated against the floor. A
        // carrier settles energyEma to ~mark energy within the encoder's mark
        // preamble; band noise stays well below the floor.
        energyEma += SQUELCH_EMA_ALPHA * (total - energyEma)
        squelchOpen = !config.squelch || energyEma > config.squelchFloor

        val norm = (markMag - spaceMag) / total
        if (norm > hysteresis) curMark = true
        else if (norm < -hysteresis) curMark = false
        // else: inside the dead-band, hold the previous decision
    }

    /** Handle the sampling point [index] (0=start, 1..5=data) for a frame. */
    private fun onSamplePoint(index: Int, sb: StringBuilder) {
        when (index) {
            0 -> {
                // Validate the start bit: it must still be space. A mark here
                // means the edge was noise — abandon and resume searching.
                if (curMark) mode = Mode.SEARCH
            }
            in 1..5 -> {
                if (curMark) codeBits = codeBits or (1 shl (index - 1)) // LSB first
                if (index == 5) {
                    // All five data bits sampled at their centres; emit and
                    // re-sync immediately. Returning to SEARCH here (rather than
                    // at a fixed 6.5-bit stop point) means the framer is ready
                    // for the next start edge regardless of stop-bit length.
                    emitCode(codeBits, sb)
                    mode = Mode.SEARCH
                    prevMark = curMark // suppress a phantom edge as the stop mark rises
                }
            }
        }
    }

    /** Apply shift/USOS logic to a completed 5-bit [code] and emit any char. */
    private fun emitCode(code: Int, sb: StringBuilder) {
        // A frame that completed while the squelch was closed is noise — the
        // shift codes still track (cheap, keeps us aligned) but printable
        // characters are suppressed so the RX pane stays clean.
        when (code) {
            Baudot.LTRS -> figures = false
            Baudot.FIGS -> figures = true
            else -> {
                if (!squelchOpen) return
                val ch = Baudot.decode(code, figures) ?: return
                sb.append(ch)
                onChar?.invoke(ch)
                if (config.unshiftOnSpace && code == Baudot.SPACE) figures = false
            }
        }
    }

    private companion object {
        /** EMA smoothing for the squelch energy estimate (~settles within a few bits). */
        const val SQUELCH_EMA_ALPHA = 0.05
    }
}
