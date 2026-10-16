package radio.ks3ckc.rttyaf.rtty

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.k1af.rttyaf.GeneralVariables
import com.k1af.rttyaf.R
import com.k1af.rttyaf.connector.ConnectMode
import com.k1af.rttyaf.ft8transmit.TuneController
import com.k1af.rttyaf.ft8transmit.TuneToneGenerator
import com.k1af.rttyaf.rigs.BaseRigOperation
import com.k1af.rttyaf.ui.ToastMessage
import com.k1af.rttyaf.wave.UsbAudioDevice
import radio.ks3ckc.rttyaf.currentTuneLevel

/** Why [TuneCarrier.start] refused (`NONE` = go). */
enum class TuneBlockReason {
    NONE,

    /** Another transmission (an RTTY over) owns the rig. */
    TX_ACTIVE,

    /** The SWR protection has locked TX out until the operator clears it. */
    SWR_LOCKED,

    /** The dial sits in a WSPR sub-band; a carrier there is never acceptable. */
    WSPR_FREQUENCY,

    /** Network, CAT-audio or USB-direct output: the carrier has no sender for those. */
    UNSUPPORTED_ROUTE,
}

/**
 * Pure gate for the tune carrier, ordered by severity: a live transmission wins,
 * then the same protections TX applies (SWR lockout, WSPR blacklist — tune must
 * not be a backdoor around TX inhibits), then route support. Unit-tested.
 */
internal fun tuneBlockReason(
    txActive: Boolean,
    swrLocked: Boolean,
    wsprFrequency: Boolean,
    catAudioRoute: Boolean,
    networkRoute: Boolean,
    usbDirectRoute: Boolean,
): TuneBlockReason = when {
    txActive -> TuneBlockReason.TX_ACTIVE
    swrLocked -> TuneBlockReason.SWR_LOCKED
    wsprFrequency -> TuneBlockReason.WSPR_FREQUENCY
    catAudioRoute || networkRoute || usbDirectRoute -> TuneBlockReason.UNSUPPORTED_ROUTE
    else -> TuneBlockReason.NONE
}

/**
 * The tune carrier: key PTT and play a steady tone at the current TX offset
 * until [stop], the max-on timeout, or an error. Lifted from FT8AF's
 * `FT8TransmitSignal` so the FT8 engine could go; timing and safety live in
 * [TuneController], the tone in [TuneToneGenerator].
 *
 * @param keyer PTT up/down through the configured control path.
 * @param otherTxBusy true while another transmission owns the rig.
 * @param swrLocked true while the SWR protection has TX locked out.
 * @param catAudioRoute true when the rig's CAT link carries the audio.
 */
class TuneCarrier(
    private val keyer: RttyKeyer,
    private val otherTxBusy: () -> Boolean = { false },
    private val swrLocked: () -> Boolean = { false },
    private val catAudioRoute: () -> Boolean = { false },
) {
    private val controller = TuneController { System.currentTimeMillis() }

    private val tuningState = MutableLiveData(false)
    private val remainingState = MutableLiveData(0)

    /** True while the carrier is on the air. */
    val tuningLive: LiveData<Boolean> get() = tuningState

    /** Seconds left before the max-on timeout drops the carrier. */
    val remainingSecLive: LiveData<Int> get() = remainingState

    val isTuning: Boolean get() = controller.isActive

    /** Start the carrier. Returns false (with an operator message) when blocked. */
    fun start(): Boolean {
        val block = tuneBlockReason(
            txActive = otherTxBusy(),
            swrLocked = swrLocked(),
            wsprFrequency = BaseRigOperation.checkIsWSPR2(
                GeneralVariables.band + Math.round(GeneralVariables.getBaseFrequency()),
            ),
            catAudioRoute = catAudioRoute(),
            networkRoute = GeneralVariables.connectMode == ConnectMode.NETWORK,
            usbDirectRoute = UsbAudioDevice.isUsbDirectOutput(
                GeneralVariables.audioOutputDeviceId, GeneralVariables.usbAudioOutputVendorId,
            ),
        )
        when (block) {
            TuneBlockReason.TX_ACTIVE ->
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.tune_blocked_tx))
            TuneBlockReason.SWR_LOCKED ->
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.swr_lockout_toast))
            TuneBlockReason.WSPR_FREQUENCY ->
                ToastMessage.show(
                    String.format(
                        GeneralVariables.getStringFromResource(R.string.use_wspr2_error),
                        BaseRigOperation.getFrequencyAllInfo(GeneralVariables.band),
                    ),
                )
            TuneBlockReason.UNSUPPORTED_ROUTE ->
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.tune_unavailable_route))
            TuneBlockReason.NONE -> Unit
        }
        if (block != TuneBlockReason.NONE) {
            GeneralVariables.fileLog("TUNE: refused reason=$block")
            return false
        }
        if (!controller.tryStart(GeneralVariables.tuneMaxOnSeconds)) return false // already tuning
        Thread({ play() }, "TuneTone").start()
        return true
    }

    /** Operator stop: the worker ramps the tone down and unkeys. */
    fun stop() {
        controller.requestStop(TuneController.STOP_USER)
    }

    /** The tune level (0..100) resolved from settings, as an amplitude 0..1. */
    private fun currentAmplitude(): Float = currentTuneLevel() / 100f

    private fun play() {
        val startedAt = System.currentTimeMillis()
        val offsetHz = GeneralVariables.getBaseFrequency()
        var track: AudioTrack? = null
        var keyed = false
        try {
            GeneralVariables.fileLog(
                "TUNE: start offset=${offsetHz.toInt()}Hz level=${currentTuneLevel()}% " +
                    "maxOn=${TuneController.clampMaxOnSeconds(GeneralVariables.tuneMaxOnSeconds)}s " +
                    "rate=${GeneralVariables.audioSampleRate}",
            )
            keyer.keyDown()
            keyed = true
            tuningState.postValue(true)

            val sampleRate = GeneralVariables.audioSampleRate
            val float32 = GeneralVariables.audioOutput32Bit
            val encoding = if (float32) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val fmt = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(encoding)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            // Small MODE_STREAM buffer: its depth bounds the latency from a level
            // change (or stop) to the air.
            val bytesPerSample = if (float32) 4 else 2
            val targetBufBytes = (sampleRate / 5) * bytesPerSample
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, encoding)
            val bufBytes = maxOf(targetBufBytes, if (minBuf > 0) minBuf else targetBufBytes)
            val t = AudioTrack(attrs, fmt, bufBytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
            track = t
            if (GeneralVariables.audioOutputDeviceId > 0) {
                t.preferredDevice = findOutputDevice(GeneralVariables.audioOutputDeviceId)
            }
            t.play()
            t.setVolume(1.0f)

            val generator = TuneToneGenerator(offsetHz, sampleRate, maxOf(1, sampleRate / 200)) // ~5 ms ramp
            val chunkSamples = maxOf(1, sampleRate / 20) // ~50 ms
            val chunk = FloatArray(chunkSamples)
            var lastPostedSec = -1
            while (true) {
                if (!controller.shouldContinue()) {
                    generator.requestStop() // final chunk(s) ramp to zero
                }
                // Level read fresh per chunk so a settings change lands mid-tune.
                val written = generator.nextChunk(chunk, chunkSamples, currentAmplitude())
                if (written <= 0) break
                val result = if (float32) {
                    t.write(chunk, 0, written, AudioTrack.WRITE_BLOCKING)
                } else {
                    t.write(toInt16(chunk.copyOf(written)), 0, written, AudioTrack.WRITE_BLOCKING)
                }
                if (result < 0) {
                    Log.e(TAG, "Tune playback error: $result")
                    controller.requestStop(TuneController.STOP_ERROR)
                    break
                }
                val remainingSec = Math.ceil(controller.remainingMs() / 1000.0).toInt()
                if (remainingSec != lastPostedSec) {
                    lastPostedSec = remainingSec
                    remainingState.postValue(remainingSec)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tune worker failed: $e")
            controller.requestStop(TuneController.STOP_ERROR)
        } finally {
            // Single point of teardown: whatever happened above, the carrier
            // stops and PTT drops here.
            controller.requestStop(TuneController.STOP_ERROR)
            track?.let {
                try {
                    it.stop()
                } catch (_: IllegalStateException) {
                }
                it.release()
            }
            if (keyed) {
                try {
                    keyer.keyUp()
                } catch (e: Exception) {
                    Log.e(TAG, "Tune key-up failed: $e")
                }
            }
            tuningState.postValue(false)
            val reason = controller.lastStopReason()
            GeneralVariables.fileLog(
                "TUNE: stop reason=$reason durationMs=${System.currentTimeMillis() - startedAt} offset=${offsetHz.toInt()}Hz",
            )
            if (TuneController.STOP_TIMEOUT == reason) {
                ToastMessage.show(
                    String.format(
                        GeneralVariables.getStringFromResource(R.string.tune_stopped_timeout),
                        TuneController.clampMaxOnSeconds(GeneralVariables.tuneMaxOnSeconds),
                    ),
                )
            }
        }
    }

    private fun findOutputDevice(deviceId: Int): AudioDeviceInfo? {
        val context = GeneralVariables.getMainContext() ?: return null
        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId }
    }

    private companion object {
        const val TAG = "TuneCarrier"
    }
}
