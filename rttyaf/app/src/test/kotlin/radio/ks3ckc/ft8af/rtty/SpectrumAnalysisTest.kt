package radio.ks3ckc.ft8af.rtty

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Coverage for the signal-finding helpers in SpectrumAnalysis.kt: noise-floor
 * display scaling, tap-to-tune peak snapping, the mark/space pair scanner and
 * the AFC tracker — plus the decoder's in-place [RttyDecoder.retune] and the
 * engine's tune entry points. Pure DSP/math; no Android runner needed.
 */
class SpectrumAnalysisTest {

    private val binHz = 12000.0 / 1024 // 11.72 Hz, as the engine's FFT

    /** A display column with flat noise at [noise] and tone peaks at the given Hz. */
    private fun column(vararg peaks: Pair<Double, Float>, bins: Int = 256, noise: Float = 0.05f): FloatArray {
        val col = FloatArray(bins) { noise }
        for ((hz, level) in peaks) col[(hz / binHz).roundToInt()] = level
        return col
    }

    /** Linear FFT magnitudes of an encoded RTTY over at [markHz], blended over all frames. */
    private fun rttySpectrum(markHz: Double, text: String = "CQ CQ DE KS3CKC K"): FloatArray {
        val cfg = RttyConfig(markHz = markHz)
        val pcm = RttyEncoder(cfg).encode(text)
        val rnd = Random(7)
        for (i in pcm.indices) pcm[i] += (rnd.nextGaussian() * 0.01).toFloat()
        val fft = SimpleFft(RttyEngine.FFT_SIZE)
        val scaler = WaterfallScaler()
        var avg = FloatArray(0)
        var i = 0
        val chunk = cfg.sampleRate * RttyEngine.CHUNK_MS / 1000
        while (i + chunk <= pcm.size) {
            val col = scaler.scale(fft.linearMagnitudes(pcm.copyOfRange(i, i + chunk), cfg.sampleRate, RttyEngine.DISPLAY_MAX_HZ))
            avg = blendColumn(avg, col, RttyEngine.SCAN_ALPHA)
            i += chunk
        }
        return avg
    }

    // ---- WaterfallScaler ---------------------------------------------------

    @Test
    fun scaler_toneStandsOutAboveNoiseFloor_andNoiseSitsNearZero() {
        val s = WaterfallScaler()
        val lin = DoubleArray(256) { 1e-3 }
        lin[100] = 1.0 // 60 dB above the floor
        val out = s.scale(lin)
        assertThat(out[100]).isEqualTo(1f)
        assertThat(out[50]).isLessThan(0.05f)
        assertThat(s.floorDb).isNotNull()
    }

    @Test
    fun scaler_floorTracksSlowly_soAQuietBandDoesNotLookBusy() {
        val s = WaterfallScaler(floorAlpha = 0.5)
        s.scale(DoubleArray(64) { 1e-3 })
        val first = s.floorDb!!
        s.scale(DoubleArray(64) { 1e-1 }) // band got 40 dB louder
        val second = s.floorDb!!
        // Moves toward the new median but only by the alpha fraction.
        assertThat(second).isGreaterThan(first)
        assertThat(second - first).isWithin(1.0).of(20.0)
    }

    @Test
    fun scaler_emptyInputAndReset() {
        val s = WaterfallScaler()
        assertThat(s.scale(DoubleArray(0))).isEmpty()
        s.scale(DoubleArray(8) { 1.0 })
        s.reset()
        assertThat(s.floorDb).isNull()
    }

    // ---- nearestPeakHz / resolveMarkHz --------------------------------------

    @Test
    fun nearestPeak_snapsATapToTheStrongestBinInWindow() {
        val col = column(1500.0 to 0.9f)
        val hz = nearestPeakHz(col, binHz, 1530.0)
        assertThat(hz).isNotNull()
        assertThat(abs(hz!! - 1500.0)).isLessThan(binHz)
    }

    @Test
    fun nearestPeak_returnsNullOnEmptyBandOrEmptySpectrum() {
        assertThat(nearestPeakHz(column(), binHz, 1500.0)).isNull()
        assertThat(nearestPeakHz(FloatArray(0), binHz, 1500.0)).isNull()
    }

    @Test
    fun resolveMark_tapOnSpaceLine_tunesMarkOneShiftBelow() {
        val col = column(1500.0 to 0.8f, 1670.0 to 0.8f)
        val mark = resolveMarkHz(col, binHz, 1675.0, 170)
        assertThat(abs(mark - 1500.0)).isLessThan(binHz)
    }

    @Test
    fun resolveMark_tapOnMarkLine_keepsTappedAsMark() {
        val col = column(1500.0 to 0.8f, 1670.0 to 0.8f)
        val mark = resolveMarkHz(col, binHz, 1495.0, 170)
        assertThat(abs(mark - 1500.0)).isLessThan(binHz)
    }

    @Test
    fun resolveMark_noSignal_usesRawTapAndClampsToPassband() {
        assertThat(resolveMarkHz(column(), binHz, 1234.0, 170)).isEqualTo(1234.0)
        assertThat(resolveMarkHz(column(), binHz, 2990.0, 170, maxHz = 3000.0)).isEqualTo(2830.0)
        assertThat(resolveMarkHz(column(), binHz, 10.0, 170)).isEqualTo(100.0)
    }

    @Test
    fun clampMark_keepsBothTonesInside() {
        assertThat(clampMarkHz(2900.0, 170, maxHz = 3000.0)).isEqualTo(2830.0)
        assertThat(clampMarkHz(50.0, 170)).isEqualTo(100.0)
        assertThat(clampMarkHz(1500.0, 170)).isEqualTo(1500.0)
    }

    // ---- findRttyCandidates ------------------------------------------------

    @Test
    fun scanner_findsAPairOneShiftApart_strongestFirst() {
        val col = column(1000.0 to 0.5f, 1170.0 to 0.5f, 2125.0 to 0.9f, 2295.0 to 0.9f)
        val found = findRttyCandidates(col, binHz, 170)
        assertThat(found).hasSize(2)
        assertThat(abs(found[0].markHz - 2125.0)).isLessThan(binHz)
        assertThat(found[0].spaceHz - found[0].markHz).isEqualTo(170.0)
        assertThat(found[0].strength).isGreaterThan(found[1].strength)
    }

    @Test
    fun scanner_ignoresLoneCarriersAndWrongSpacing() {
        val col = column(1000.0 to 0.9f, 1500.0 to 0.9f, 1800.0 to 0.9f)
        assertThat(findRttyCandidates(col, binHz, 170)).isEmpty()
    }

    @Test
    fun scanner_collapsesOverlappingPairs_andRespectsMaxCount() {
        // A three-line comb 170 Hz apart yields two pairs sharing a tone; keep the stronger.
        val col = column(1000.0 to 0.9f, 1170.0 to 0.9f, 1340.0 to 0.6f)
        val found = findRttyCandidates(col, binHz, 170)
        assertThat(found).hasSize(1)
        assertThat(abs(found[0].markHz - 1000.0)).isLessThan(binHz)
        // Two genuine stations 250 Hz apart (contest spacing) are both kept.
        val two = column(1000.0 to 0.9f, 1170.0 to 0.9f, 1250.0 to 0.7f, 1420.0 to 0.7f)
        assertThat(findRttyCandidates(two, binHz, 170)).hasSize(2)
        val many = column(500.0 to 0.9f, 670.0 to 0.9f, 1500.0 to 0.9f, 1670.0 to 0.9f, 2400.0 to 0.9f, 2570.0 to 0.9f)
        assertThat(findRttyCandidates(many, binHz, 170, maxCount = 2)).hasSize(2)
    }

    @Test
    fun scanner_degenerateInputs() {
        assertThat(findRttyCandidates(FloatArray(0), binHz, 170)).isEmpty()
        assertThat(findRttyCandidates(column(), 0.0, 170)).isEmpty()
        assertThat(findRttyCandidates(column(), binHz, 0)).isEmpty()
        assertThat(findRttyCandidates(column(), 500.0, 170)).isEmpty() // shift < 2 bins
    }

    @Test
    fun scanner_findsARealEncodedRttySignal() {
        val found = findRttyCandidates(rttySpectrum(1500.0), binHz, 170)
        assertThat(found).isNotEmpty()
        // Keying sidebands can tip the local maximum one bin either side of the carrier.
        assertThat(abs(found[0].markHz - 1500.0)).isAtMost(1.5 * binHz)
    }

    // ---- AfcTracker --------------------------------------------------------

    @Test
    fun afc_movesTowardAnOffsetSignal_andSettlesOnIt() {
        val afc = AfcTracker()
        afc.anchor(1480.0)
        val col = column(1500.0 to 0.9f, 1670.0 to 0.9f)
        var mark = 1480.0
        repeat(30) { mark = afc.update(col, binHz, mark, 170) }
        assertThat(abs(mark - 1500.0)).isLessThan(binHz)
    }

    @Test
    fun afc_staysPutOnAnEmptyChannel() {
        val afc = AfcTracker()
        afc.anchor(1500.0)
        assertThat(afc.update(column(), binHz, 1500.0, 170)).isEqualTo(1500.0)
        assertThat(afc.update(FloatArray(0), binHz, 1500.0, 170)).isEqualTo(1500.0)
    }

    @Test
    fun afc_limitsStepPerUpdate_andTotalDriftFromAnchor() {
        val afc = AfcTracker(maxDriftHz = 20.0, maxStepHz = 5.0)
        afc.anchor(1500.0)
        val col = column(1540.0 to 0.9f, 1710.0 to 0.9f) // 40 Hz high, inside the search window
        val once = afc.update(col, binHz, 1500.0, 170)
        assertThat(once - 1500.0).isWithin(1e-9).of(5.0)
        var mark = 1500.0
        repeat(50) { mark = afc.update(col, binHz, mark, 170) }
        assertThat(mark).isEqualTo(1520.0)
    }

    @Test
    fun afc_tracksOneToneWhenOnlyTheMarkIsKeyed() {
        val afc = AfcTracker()
        afc.anchor(1490.0)
        val col = column(1500.0 to 0.9f) // idle: continuous mark, no space
        val next = afc.update(col, binHz, 1490.0, 170)
        assertThat(next).isGreaterThan(1490.0)
    }

    @Test
    fun afc_convergesOnARealEncodedSignal() {
        val spec = rttySpectrum(1500.0)
        val afc = AfcTracker()
        afc.anchor(1470.0)
        var mark = 1470.0
        repeat(40) { mark = afc.update(spec, binHz, mark, 170) }
        assertThat(abs(mark - 1500.0)).isLessThan(binHz)
    }

    // ---- blendColumn / SimpleFft additions ----------------------------------

    @Test
    fun blendColumn_reseedsOnSizeChange_andAveragesOtherwise() {
        val seeded = blendColumn(FloatArray(0), floatArrayOf(1f, 0f), 0.5f)
        assertThat(seeded).isEqualTo(floatArrayOf(1f, 0f))
        val blended = blendColumn(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), 0.5f)
        assertThat(blended).isEqualTo(floatArrayOf(0.5f, 0.5f))
    }

    @Test
    fun fft_linearMagnitudesPeakAtTheTone_andBinHzMatches() {
        val fft = SimpleFft(1024)
        assertThat(fft.binHz(12000)).isWithin(1e-9).of(12000.0 / 1024)
        val tone = FloatArray(1024) { kotlin.math.sin(2 * Math.PI * 1500.0 * it / 12000).toFloat() }
        val lin = fft.linearMagnitudes(tone, 12000, 3000.0)
        val peak = lin.indices.maxByOrNull { lin[it] }!!
        assertThat(abs(peak * fft.binHz(12000) - 1500.0)).isLessThan(fft.binHz(12000))
        // The legacy normalised view still tops out at 1.
        assertThat(fft.magnitudes(tone, 12000, 3000.0).max()).isEqualTo(1f)
    }

    // ---- RttyDecoder.retune --------------------------------------------------

    @Test
    fun decoder_retune_movesTonesWithoutRebuild() {
        val signal = RttyEncoder(RttyConfig(markHz = 1500.0)).encode("CQ CQ DE KS3CKC K")
        val dec = RttyDecoder(RttyConfig(markHz = 2125.0))
        assertThat(dec.process(signal)).doesNotContain("KS3CKC")
        dec.retune(1500.0)
        assertThat(dec.markHz).isEqualTo(1500.0)
        assertThat(dec.process(signal)).contains("KS3CKC")
    }

    // ---- RttyEngine tune entry points ---------------------------------------

    @Test
    fun engine_tuneNudgeAndTap_updateConfigWithinPassband() {
        val engine = RttyEngine(hamRecorder = null)
        engine.tune(1500.0)
        assertThat(engine.config.markHz).isEqualTo(1500.0)
        engine.nudge(-25.0)
        assertThat(engine.config.markHz).isEqualTo(1475.0)
        engine.tune(5000.0)
        assertThat(engine.config.markHz).isEqualTo(RttyEngine.DISPLAY_MAX_HZ - engine.config.shiftHz)
        // No spectrum yet: a tap tunes straight to the tapped frequency.
        engine.tuneTapped(1000.0)
        assertThat(engine.config.markHz).isEqualTo(1000.0)
        assertThat(engine.binHz).isWithin(1e-9).of(12000.0 / RttyEngine.FFT_SIZE)
    }

    @Test
    fun engine_loopback_populatesSpectrumAndCandidates_andTapLocksOntoPair() {
        val engine = RttyEngine(hamRecorder = null)
        engine.afc = false
        engine.tune(1500.0)
        repeat(3) { engine.injectLoopbackTest("RYRYRYRYRYRYRYRY") }
        assertThat(engine.spectrum).isNotEmpty()
        assertThat(engine.candidates).isNotEmpty()
        val pair = engine.candidates[0]
        engine.tune(2000.0)
        engine.tuneTapped(pair.spaceHz + 10.0) // inside the bracket margin
        assertThat(engine.config.markHz).isEqualTo(pair.markHz)
    }

    @Test
    fun engine_afcToggle_andApplyConfigResetScan() {
        val engine = RttyEngine(hamRecorder = null)
        engine.afc = false
        assertThat(engine.afc).isFalse()
        engine.afc = true
        assertThat(engine.afc).isTrue()
        engine.injectLoopbackTest()
        engine.applyConfig(RttyConfig(baudRate = 75.0))
        assertThat(engine.candidates).isEmpty()
        assertThat(engine.config.baudRate).isEqualTo(75.0)
    }
}
