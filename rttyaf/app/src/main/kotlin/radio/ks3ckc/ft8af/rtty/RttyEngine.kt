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

    /** Latest normalised magnitude spectrum, DC..[DISPLAY_MAX_HZ]. */
    var spectrum by mutableStateOf(FloatArray(0))
        private set

    var listening by mutableStateOf(false)
        private set

    /** Automatic frequency control / tuning-net toggles (display + future demod use). */
    var afc by mutableStateOf(true)
    var net by mutableStateOf(true)

    private var decoder = RttyDecoder(config)
    private var monitor: HamRecorder.VoiceDataMonitor? = null
    private val fft = SimpleFft(FFT_SIZE)

    // The decoder and fft carry mutable per-sample state and reuse internal
    // buffers, so every path that touches them — the audio callback, the config
    // swap, and the loopback self-test — must be mutually exclusive. Without
    // this, tapping TEST (or changing config) while RX is live interleaves two
    // process()/magnitudes() calls and corrupts the framer / spectrum.
    private val audioLock = Any()

    // Rolling RX text kept in a StringBuilder so each decoded chunk trims in
    // place instead of allocating two full-length strings on the audio thread.
    private val rxBuffer = StringBuilder()

    /** MARK tone position as a 0..1 fraction of the displayed span, for the cursor overlay. */
    val markFraction: Float get() = (config.markToneHz / DISPLAY_MAX_HZ).toFloat().coerceIn(0f, 1f)

    /** SPACE tone position as a 0..1 fraction of the displayed span. */
    val spaceFraction: Float get() = (config.spaceToneHz / DISPLAY_MAX_HZ).toFloat().coerceIn(0f, 1f)

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
            spectrum = fft.magnitudes(chunk, config.sampleRate, DISPLAY_MAX_HZ)
        }
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

    /** Swap demod parameters (baud/shift/tones); rebuilds the decoder and re-taps if live. */
    fun applyConfig(newConfig: RttyConfig) {
        // Under audioLock so the decoder swap can't land mid-process() on the
        // audio thread.
        synchronized(audioLock) {
            config = newConfig
            if (listening) {
                stop()
                start()
            } else {
                decoder = RttyDecoder(newConfig)
            }
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
            spectrum = fft.magnitudes(samples, config.sampleRate, DISPLAY_MAX_HZ)
        }
    }

    companion object {
        /** Audio accumulated per looping callback (ms). Short enough for a lively waterfall. */
        const val CHUNK_MS = 120
        const val MAX_RX = 4000
        const val FFT_SIZE = 1024
        const val DISPLAY_MAX_HZ = 3000.0
    }
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
