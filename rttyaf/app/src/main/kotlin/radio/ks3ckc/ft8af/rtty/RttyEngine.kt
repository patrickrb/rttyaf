package radio.ks3ckc.ft8af.rtty

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.ft8transmit.FT8TransmitSignal
import com.k1af.ft8af.wave.HamRecorder

/**
 * Runtime glue between the reused FT8AF audio capture ([HamRecorder]) and the
 * pure-Kotlin RTTY modem ([RttyDecoder] / [RttyEncoder]).
 *
 * It registers a *looping* voice-data monitor on the recorder (the same funnel
 * FT8 decode uses, but continuous instead of slot-based), feeds each audio chunk
 * to the decoder, appends decoded characters to [rxText], and computes a
 * waterfall column via [SimpleFft]. All observable fields are Compose snapshot
 * state so the Operate screen recomposes as text and spectra arrive.
 *
 * State updates happen on the recorder's callback thread; Compose snapshot state
 * is safe to write off the main thread.
 */
class RttyEngine(
    private val hamRecorder: HamRecorder?,
    private val transmitSignal: FT8TransmitSignal? = null,
) {

    // Stable fallback so [transmittingLive] is non-null (and safe to observe
    // unconditionally in Compose) even when no TX backend is wired — e.g. tests
    // or previews that construct the engine without a transmitSignal.
    private val noTxState = MutableLiveData(false)

    /** Live TX state (true while an over is on the air), for the UI to observe. */
    val transmittingLive: LiveData<Boolean>
        get() = transmitSignal?.mutableIsRttyTransmitting ?: noTxState

    var config by mutableStateOf(RttyConfig())
        private set

    /** Rolling decoded text (capped at [MAX_RX] chars). */
    var rxText by mutableStateOf("")
        private set

    /**
     * Latest display spectrum, DC..[DISPLAY_MAX_HZ]: per-bin level 0..1 where 0
     * is the tracked noise floor and 1 is [WaterfallScaler.rangeDb] above it,
     * so brightness is comparable from column to column (see [WaterfallScaler]).
     */
    var spectrum by mutableStateOf(FloatArray(0))
        private set

    /** Likely RTTY signals (mark/space pairs) found in the recent spectrum, strongest first. */
    var candidates by mutableStateOf<List<RttyCandidate>>(emptyList())
        private set

    var listening by mutableStateOf(false)
        private set

    private var afcState by mutableStateOf(true)

    /** Automatic frequency control: track the tuned signal's drift (see [AfcTracker]). */
    var afc: Boolean
        get() = afcState
        set(value) {
            afcState = value
            // Switching AFC on re-anchors at wherever the operator has it now.
            if (value) synchronized(audioLock) { afcTracker.anchor(config.markHz) }
        }

    /** Tuning net toggle (TX follows RX tones; display only for now). */
    var net by mutableStateOf(true)

    private var decoder = RttyDecoder(config)
    private var monitor: HamRecorder.VoiceDataMonitor? = null
    private val fft = SimpleFft(FFT_SIZE)
    private val scaler = WaterfallScaler()
    private val afcTracker = AfcTracker().also { it.anchor(config.markHz) }

    /** Width of one spectrum bin in Hz. */
    val binHz: Double get() = fft.binHz(config.sampleRate)

    // Slow average of the display spectrum for the pair scanner: only one RTTY
    // tone is keyed at any instant, so a single FFT frame may show just one
    // line; a few frames blended together show the pair.
    private var scanAverage = FloatArray(0)

    // The decoder and fft carry mutable per-sample state and reuse internal
    // buffers, so every path that touches them — the audio callback, the config
    // swap, and the loopback self-test — must be mutually exclusive. Without
    // this, tapping TEST (or changing config) while RX is live interleaves two
    // process()/magnitudes() calls and corrupts the framer / spectrum.
    private val audioLock = Any()

    // Rolling RX text kept in a StringBuilder so each decoded chunk trims in
    // place instead of allocating two full-length strings on the audio thread.
    private val rxBuffer = StringBuilder()

    /** Begin continuous RX: tap the recorder and decode every chunk. Idempotent. */
    fun start() {
        if (listening) return
        val hr = hamRecorder ?: return
        decoder = RttyDecoder(config)
        monitor = hr.getVoiceData(CHUNK_MS, false) { data -> onAudio(data) }
        listening = true
    }

    /** Stop continuous RX and unregister the monitor. Idempotent. */
    fun stop() {
        val m = monitor
        if (m != null) hamRecorder?.deleteVoiceDataMonitor(m)
        monitor = null
        listening = false
    }

    private fun onAudio(data: FloatArray) {
        // The recorder reuses one buffer across looping callbacks and zeroes its
        // counter after we return, so copy before we do any work on another thread.
        val chunk = data.copyOf()
        synchronized(audioLock) {
            val decoded = decoder.process(chunk)
            if (decoded.isNotEmpty()) appendRx(decoded)
            analyse(chunk)
        }
    }

    /**
     * Update the display spectrum, the candidate-pair scan and (when enabled)
     * AFC from one audio chunk. Callers hold [audioLock].
     */
    private fun analyse(chunk: FloatArray) {
        val column = scaler.scale(fft.linearMagnitudes(chunk, config.sampleRate, DISPLAY_MAX_HZ))
        spectrum = column
        scanAverage = blendColumn(scanAverage, column, SCAN_ALPHA)
        candidates = findRttyCandidates(scanAverage, binHz, config.shiftHz)
        if (afc) {
            // Same passband bounds as tune(): AFC may drift around the anchor but
            // never carry a tone outside what the decoder/display covers.
            val corrected = afcTracker.update(scanAverage, binHz, config.markHz, config.shiftHz, maxHz = DISPLAY_MAX_HZ)
            if (kotlin.math.abs(corrected - config.markHz) >= AFC_MIN_CHANGE_HZ) setMark(corrected)
        }
    }

    /**
     * Tune to a tap on the waterfall at [hz]. A tap inside a scanned candidate
     * pair locks onto that pair exactly; otherwise the tap snaps to the nearest
     * peak and its partner tone is inferred (see [resolveMarkHz]).
     */
    fun tuneTapped(hz: Double) {
        val mark = synchronized(audioLock) {
            candidates.firstOrNull { hz >= it.markHz - TAP_MARGIN_HZ && hz <= it.spaceHz + TAP_MARGIN_HZ }?.markHz
                ?: resolveMarkHz(spectrum, binHz, hz, config.shiftHz, maxHz = DISPLAY_MAX_HZ)
        }
        tune(mark)
    }

    /** Tune the mark (lower) tone to [markHz] exactly and re-anchor AFC there. */
    fun tune(markHz: Double) {
        synchronized(audioLock) {
            val clamped = clampMarkHz(markHz, config.shiftHz, maxHz = DISPLAY_MAX_HZ)
            afcTracker.anchor(clamped)
            setMark(clamped)
        }
    }

    /** Fine-tune: move the tone pair by [deltaHz] (drag on the waterfall cursor). */
    fun nudge(deltaHz: Double) = tune(config.markHz + deltaHz)

    /** Retune the live decoder without rebuilding it. Callers hold [audioLock]. */
    private fun setMark(markHz: Double) {
        if (markHz == config.markHz) return
        config = config.copy(markHz = markHz)
        decoder.retune(markHz)
    }

    /** Append decoded text, trimming the front in place. Callers hold [audioLock]. */
    private fun appendRx(s: String) {
        rxBuffer.append(s)
        val over = rxBuffer.length - MAX_RX
        if (over > 0) rxBuffer.delete(0, over)
        rxText = rxBuffer.toString()
    }

    fun clearRx() {
        synchronized(audioLock) {
            rxBuffer.setLength(0)
            rxText = ""
        }
    }

    /**
     * Modulate [text] to an RTTY (Baudot-FSK) waveform at the sound-card rate and
     * transmit it over the air (key → send → unkey), delegating keying + audio
     * routing to the shared [FT8TransmitSignal]. Returns false when there is no
     * TX backend, nothing to send, or the backend blocks the audio route.
     *
     * The waveform is generated at [GeneralVariables.audioSampleRate] (not the
     * 12 kHz RX rate) so the AudioTrack plays the tones at the correct pitch.
     */
    fun transmit(text: String): Boolean {
        val tx = transmitSignal ?: return false
        val msg = text.trim()
        if (msg.isEmpty()) return false
        val rate = txSampleRate(GeneralVariables.audioSampleRate, config.sampleRate)
        val pcm = RttyEncoder(config.copy(sampleRate = rate)).encode(txMessage(msg))
        return tx.transmitRtty(pcm, rate)
    }

    /** Abort an in-progress transmission (STOP). No-op if not transmitting. */
    fun stopTx() {
        transmitSignal?.stopRttyTx()
    }

    /** Swap demod parameters (baud/shift/tones); rebuilds the decoder in place. */
    fun applyConfig(newConfig: RttyConfig) {
        // Under audioLock so the decoder swap can't land mid-process() on the
        // audio thread. The recorder tap doesn't depend on config, so it stays.
        synchronized(audioLock) {
            config = newConfig
            decoder = RttyDecoder(newConfig)
            afcTracker.anchor(newConfig.markHz)
            scaler.reset()
            scanAverage = FloatArray(0)
            candidates = emptyList()
        }
    }

    /**
     * Developer self-test that proves the modem end-to-end without a rig: modulate
     * [text] to AFSK with the current config and run it straight back through the
     * live decoder, so the decoded result lands in [rxText] exactly as an off-air
     * signal would. Wired to a dev affordance on the Operate screen.
     */
    fun injectLoopbackTest(text: String = "CQ CQ DE KS3CKC KS3CKC K") {
        val samples = RttyEncoder(config).encode(txMessage(text))
        // Same lock as onAudio(): the live decoder/fft must not be entered from
        // two threads (TEST button on the main thread vs. RX on the audio thread).
        synchronized(audioLock) {
            val out = decoder.process(samples)
            if (out.isNotEmpty()) appendRx(out)
            // Analyse in the same CHUNK_MS slices the recorder delivers, so the
            // scanner's blended average sees both tones keyed over the burst
            // rather than one FFT of its (mark-idle) tail.
            val chunk = config.sampleRate * CHUNK_MS / 1000
            var i = 0
            while (i < samples.size) {
                analyse(samples.copyOfRange(i, minOf(i + chunk, samples.size)))
                i += chunk
            }
        }
    }

    companion object {
        /** Audio accumulated per looping callback (ms). Short enough for a lively waterfall. */
        const val CHUNK_MS = 120
        const val MAX_RX = 4000
        const val FFT_SIZE = 1024
        const val DISPLAY_MAX_HZ = 3000.0
        /** Blend weight of each new column into the scanner's running average. */
        const val SCAN_ALPHA = 0.3f
        /** AFC corrections smaller than this are ignored (sub-bin jitter). */
        const val AFC_MIN_CHANGE_HZ = 1.0
        /** How far outside a candidate's mark..space span a tap still counts as "on it". */
        const val TAP_MARGIN_HZ = 30.0
    }
}

/**
 * Exponential blend of [column] into [average]: `avg += alpha * (col - avg)`.
 * Returns [column] itself when sizes differ (first column or FFT change) so
 * the average re-seeds instead of mixing mismatched bins. Pure — unit-tested.
 */
internal fun blendColumn(average: FloatArray, column: FloatArray, alpha: Float): FloatArray {
    if (average.size != column.size) return column.copyOf()
    val out = FloatArray(column.size)
    for (i in column.indices) out[i] = average[i] + alpha * (column[i] - average[i])
    return out
}

/**
 * Wrap outgoing operator text as an RTTY over: a leading idle space (gives the
 * receiver a resting character before the payload) and a trailing CR/LF (the
 * teleprinter line-ending convention). Pure — unit-tested.
 */
internal fun txMessage(text: String): String = " ${text.trim()}\r\n"

/**
 * Resolve the sample rate to modulate a transmission at: prefer the sound-card
 * rate ([GeneralVariables.audioSampleRate]), but fall back to the modem's own
 * [fallback] rate if the reported audio rate is implausibly low (misconfig),
 * so we never build an AudioTrack at, say, 0 Hz. Pure — unit-tested.
 */
internal fun txSampleRate(audioRate: Int, fallback: Int): Int =
    if (audioRate >= 8000) audioRate else fallback
