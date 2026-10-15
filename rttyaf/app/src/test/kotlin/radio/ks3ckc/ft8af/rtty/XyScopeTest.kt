package radio.ks3ckc.ft8af.rtty

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The XY tuning scope: the trace ring buffer, the display helpers, and — the
 * part that matters — that the demodulator's filter outputs really do draw a
 * balanced cross for a tuned signal and collapse when the signal is off
 * frequency or has the wrong shift. Pure DSP, no Android.
 */
class XyScopeTest {

    /** Trace left by decoding [signal] with a decoder tuned to [decoderCfg]. */
    private fun traceFor(signal: FloatArray, decoderCfg: RttyConfig): FloatArray {
        val trace = XyTrace(capacity = 4096, decimate = 1)
        val dec = RttyDecoder(decoderCfg).also { it.xyTrace = trace }
        dec.process(signal)
        return trace.snapshot()
    }

    private val text = "RYRYRYRYRYRYRYRYRYRY" // alternating-ish bits: both tones keyed often

    // ---- XyTrace ring buffer ---------------------------------------------------

    @Test
    fun trace_decimatesAndKeepsTheNewestPointsOldestFirst() {
        val t = XyTrace(capacity = 3, decimate = 2)
        for (i in 1..10) t.push(i.toFloat(), -i.toFloat()) // keeps 2,4,6,8,10 → last three: 6,8,10
        assertThat(t.size).isEqualTo(3)
        assertThat(t.snapshot().toList()).containsExactly(6f, -6f, 8f, -8f, 10f, -10f).inOrder()
        t.clear()
        assertThat(t.size).isEqualTo(0)
        assertThat(t.snapshot()).isEmpty()
    }

    @Test
    fun trace_rejectsNonsenseSizes() {
        try {
            XyTrace(capacity = 0)
            assert(false) { "expected IllegalArgumentException" }
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    // ---- Display helpers -------------------------------------------------------

    @Test
    fun xyArms_measuresPeakPerAxis() {
        val arms = xyArms(floatArrayOf(0.2f, 0.1f, -0.8f, 0.3f, 0.1f, -0.4f))
        assertThat(arms.mark).isEqualTo(0.8f)
        assertThat(arms.space).isEqualTo(0.4f)
        assertThat(arms.peak).isEqualTo(0.8f)
        assertThat(xyArms(FloatArray(0))).isEqualTo(XyArms(0f, 0f))
    }

    @Test
    fun normalizeXy_autoGainsToTheLargerArm_andKeepsTheRatio() {
        val out = normalizeXy(floatArrayOf(0.5f, 0.25f, -0.5f, 0.0f))
        assertThat(out.toList()).containsExactly(1f, 0.5f, -1f, 0f).inOrder()
    }

    @Test
    fun normalizeXy_silenceStaysFlatInsteadOfBlowingUpNoise() {
        val out = normalizeXy(floatArrayOf(1e-5f, -2e-5f, 3e-5f, 0f))
        assertThat(out.toList()).containsExactly(0f, 0f, 0f, 0f)
    }

    // ---- What the scope actually shows -------------------------------------------

    @Test
    fun tunedSignal_drawsABalancedCross() {
        val cfg = RttyConfig()
        val arms = xyArms(traceFor(RttyEncoder(cfg).encode(text), cfg))
        // Encoder amplitude is 0.5; each reconstructed tone swings ~±0.5.
        assertThat(arms.mark).isGreaterThan(0.35f)
        assertThat(arms.space).isGreaterThan(0.35f)
        val ratio = arms.mark / arms.space
        assertThat(ratio).isWithin(0.25f).of(1f)
    }

    @Test
    fun signal500HzOff_collapsesBothArms() {
        // 500 Hz keeps both transmitted tones well clear of *both* decoder filters.
        val tx = RttyConfig(markHz = 2125.0 + 500.0)
        val arms = xyArms(traceFor(RttyEncoder(tx).encode(text), RttyConfig()))
        assertThat(arms.mark).isLessThan(0.15f)
        assertThat(arms.space).isLessThan(0.15f)
    }

    @Test
    fun wrongShift_lightsOnlyTheMarkArm() {
        // Mark lands in the mark filter but space (850 Hz up) misses the space filter.
        // The mark tone still leaks a little into the space filter 170 Hz away
        // (plus keying transients), so the test is about the arm *ratio*.
        val tx = RttyConfig(shiftHz = 850)
        val arms = xyArms(traceFor(RttyEncoder(tx).encode(text), RttyConfig()))
        assertThat(arms.mark).isGreaterThan(0.35f)
        assertThat(arms.space).isLessThan(arms.mark * 0.6f)
    }

    @Test
    fun noTraceAttached_decoderStillDecodes() {
        val cfg = RttyConfig()
        val dec = RttyDecoder(cfg)
        assertThat(dec.xyTrace).isNull()
        assertThat(dec.process(RttyEncoder(cfg).encode("DE KS3CKC"))).contains("DE KS3CKC")
    }
}
