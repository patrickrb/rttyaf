package radio.ks3ckc.ft8af.rtty

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * RTTY (Baudot/ITA2) AFSK modulator.
 *
 * Turns text into a continuous-phase mark/space audio waveform:
 *
 *  1. Text → ITA2 codes, inserting LTRS/FIGS shift codes as the character set
 *     demands and upper-casing letters (mirroring USOS so it stays in lock-step
 *     with a USOS-enabled decoder).
 *  2. Each code → a 5-bit frame: 1 START bit (space), 5 data bits LSB-first
 *     (set bit = mark), then [RttyConfig.stopBits] STOP bits (mark).
 *  3. Frames → samples with a single running phase accumulator, so tone changes
 *     never introduce a phase discontinuity (no clicks / spectral splatter).
 *
 * A short MARK idle is emitted before and after the message so a receiver has a
 * resting tone to detect the first start bit against, and a leading LTRS aligns
 * both ends on the LETTERS shift.
 *
 * @param config    modem parameters
 * @param amplitude peak sample amplitude for the float output (0..1)
 */
class RttyEncoder(
    val config: RttyConfig,
    private val amplitude: Double = 0.5,
) {
    /** MARK idle bit periods emitted before the first character. */
    private val preambleBits: Double = 16.0

    /** MARK idle bit periods emitted after the last character. */
    private val tailBits: Double = 4.0

    /** One contiguous stretch of a single tone, measured in bit periods. */
    private data class Segment(val mark: Boolean, val bits: Double)

    /**
     * Convert [text] to the ITA2 code stream, inserting shift codes as needed.
     * A leading [Baudot.LTRS] pins the receiver to the LETTERS shift.
     */
    internal fun textToCodes(text: String): List<Int> {
        val out = ArrayList<Int>()
        var shift = Baudot.Shift.LETTERS
        out.add(Baudot.LTRS)
        for (raw in text) {
            val entry = Baudot.encode(raw) ?: continue // skip unsupported chars
            if (entry.shift != null && entry.shift != shift) {
                out.add(if (entry.shift == Baudot.Shift.FIGURES) Baudot.FIGS else Baudot.LTRS)
                shift = entry.shift
            }
            out.add(entry.code)
            // USOS: a space returns the (real) decoder to LETTERS, so track it
            // here too, otherwise a following figure would be sent unshifted.
            if (config.unshiftOnSpace && entry.code == Baudot.SPACE) {
                shift = Baudot.Shift.LETTERS
            }
        }
        return out
    }

    /** Build the mark/space segment list (idle + framed codes) for [text]. */
    private fun buildSegments(text: String): List<Segment> {
        val segs = ArrayList<Segment>()
        segs.add(Segment(mark = true, bits = preambleBits))
        for (code in textToCodes(text)) {
            segs.add(Segment(mark = false, bits = 1.0)) // START (space)
            for (i in 0 until 5) {
                val bitSet = (code shr i) and 1 == 1 // LSB first; set bit = mark
                segs.add(Segment(mark = bitSet, bits = 1.0))
            }
            segs.add(Segment(mark = true, bits = config.stopBits)) // STOP (mark)
        }
        segs.add(Segment(mark = true, bits = tailBits))
        return segs
    }

    /** Encode [text] to a mono float waveform at [RttyConfig.sampleRate]. */
    fun encode(text: String): FloatArray {
        val segs = buildSegments(text)
        val spb = config.samplesPerBit
        val totalBits = segs.sumOf { it.bits }
        val totalSamples = (totalBits * spb).roundToInt()
        val out = FloatArray(totalSamples)

        val markInc = 2.0 * PI * config.markToneHz / config.sampleRate
        val spaceInc = 2.0 * PI * config.spaceToneHz / config.sampleRate

        var phase = 0.0
        var cumulativeBits = 0.0
        var written = 0
        for (seg in segs) {
            cumulativeBits += seg.bits
            // Round each boundary off the cumulative total so fractional
            // samples-per-bit never accumulate drift across the message.
            val boundary = (cumulativeBits * spb).roundToInt().coerceAtMost(totalSamples)
            val inc = if (seg.mark) markInc else spaceInc
            var n = written
            while (n < boundary) {
                out[n] = (amplitude * sin(phase)).toFloat()
                phase += inc
                if (phase >= 2.0 * PI) phase -= 2.0 * PI
                n++
            }
            written = boundary
        }
        return out
    }

    /** Encode [text] to 16-bit PCM (same waveform as [encode], scaled to Short). */
    fun encodeShort(text: String): ShortArray {
        val floats = encode(text)
        val out = ShortArray(floats.size)
        for (i in floats.indices) {
            val v = (floats[i] * Short.MAX_VALUE).roundToInt()
            out[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }
}
