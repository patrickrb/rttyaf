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
import com.k1af.rttyaf.ui.ToastMessage
import com.k1af.rttyaf.wave.UsbAudioDevice
import com.k1af.rttyaf.wave.UsbAudioNative

/**
 * Keys the rig for an RTTY over through whatever PTT path is configured
 * (CAT / RTS / DTR). On VOX this is a no-op and the audio itself keys the rig.
 */
interface RttyKeyer {
    fun keyDown()
    fun keyUp()
}

/** An opened USB-direct output stream, as the TX worker sees it. */
interface RttyUsbOutput {
    /** Stream the full-scale waveform; false on failure or STOP. */
    fun write(pcm: FloatArray, sampleRate: Int): Boolean

    fun close()
}

/** Opens the operator's USB-direct output, or null when it can't. Tests swap in a fake. */
fun interface RttyUsbOutputOpener {
    fun open(): RttyUsbOutput?
}

/** Why [RttyTransmitter.transmit] refused to key (`NONE` = go). */
enum class RttyTxBlockReason {
    NONE,

    /** Another transmission (the tune carrier) owns the rig. */
    OTHER_TX,

    /** An RTTY over is already going out (single-flight). */
    RTTY_ACTIVE,

    /** The SWR protection has locked TX out until the operator clears it. */
    SWR_LOCKED,

    /** Network (Icom LAN) or (tr)uSDX CAT audio: RTTY has no sender for those. */
    UNSUPPORTED_ROUTE,
}

/**
 * Pure gate for an RTTY over, ordered by severity. Note what is *not* here: the
 * USB-direct sound card. That route is served, not refused. Unit-tested.
 */
internal fun rttyTxBlockReason(
    otherTxActive: Boolean,
    rttyActive: Boolean,
    swrLocked: Boolean,
    networkRoute: Boolean,
    catAudioRoute: Boolean,
): RttyTxBlockReason = when {
    otherTxActive -> RttyTxBlockReason.OTHER_TX
    rttyActive -> RttyTxBlockReason.RTTY_ACTIVE
    swrLocked -> RttyTxBlockReason.SWR_LOCKED
    networkRoute || catAudioRoute -> RttyTxBlockReason.UNSUPPORTED_ROUTE
    else -> RttyTxBlockReason.NONE
}

/** A failed write warrants the "TX dropped" warning unless it was the operator's own STOP. */
internal fun shouldWarnTxDropped(success: Boolean, cancelled: Boolean): Boolean = !success && !cancelled

/**
 * Transmits a pre-modulated RTTY (Baudot-FSK) waveform: key the rig, stream the
 * whole buffer out the sound card at TX level, unkey. This is the ~200 lines the
 * RTTY app actually needs from FT8AF's 2,800-line `FT8TransmitSignal`, lifted out
 * so the FT8 sequencer can go.
 *
 * Two audio routes, chosen exactly as FT8 TX chooses them: Android's AudioTrack
 * sink, or the USB-direct (libusb) route when the operator picked a USB sound
 * card for output ([UsbAudioDevice.writeAudio]). Playback runs on a dedicated
 * worker that owns keying and audio teardown in a `finally` — a stuck carrier is
 * never acceptable.
 *
 * @param keyer PTT up/down through the configured control path.
 * @param otherTxBusy true while another transmission (the tune carrier) owns the rig.
 * @param catAudioRoute true when the rig's CAT link carries the audio ((tr)uSDX),
 *   which RTTY has no sender for.
 * @param swrLocked true while the SWR protection has TX locked out.
 * @param onOverEnd called on the worker after every over (success, failure or
 *   STOP) — the ALC auto-level hook.
 */
class RttyTransmitter(
    private val keyer: RttyKeyer,
    private val otherTxBusy: () -> Boolean = { false },
    private val catAudioRoute: () -> Boolean = { false },
    private val swrLocked: () -> Boolean = { false },
    private val onOverEnd: () -> Unit = {},
) {
    private val transmittingState = MutableLiveData(false)

    /** Live TX state (true while an over is on the air), for the UI to observe. */
    val transmittingLive: LiveData<Boolean> get() = transmittingState

    /** Whether an RTTY over is currently on the air. */
    @Volatile
    var isTransmitting: Boolean = false
        private set

    @Volatile
    private var cancelled = false

    @Volatile
    private var usbRoute = false

    @Volatile
    private var audioTrack: AudioTrack? = null

    /**
     * The real USB-direct output: [UsbAudioDevice.openConfiguredOutput] plus
     * [UsbAudioDevice.writeAudio]. Tests swap in a fake to exercise the worker's
     * keying / close discipline without hardware.
     */
    var usbOpener: RttyUsbOutputOpener = RttyUsbOutputOpener {
        val dev = UsbAudioDevice.openConfiguredOutput("RTTY TX") ?: return@RttyUsbOutputOpener null
        object : RttyUsbOutput {
            override fun write(pcm: FloatArray, sampleRate: Int): Boolean = dev.writeAudio(pcm, sampleRate)

            override fun close() = dev.close()
        }
    }

    /**
     * Start an over. Non-blocking. Returns false when there is nothing to send or
     * the gate refuses (an operator message explains a refusal).
     *
     * @param pcm mono float samples in -1..1 at [sampleRate]
     */
    fun transmit(pcm: FloatArray, sampleRate: Int): Boolean {
        if (pcm.isEmpty() || sampleRate <= 0) return false
        val block = rttyTxBlockReason(
            otherTxActive = otherTxBusy(),
            rttyActive = isTransmitting,
            swrLocked = swrLocked(),
            networkRoute = GeneralVariables.connectMode == ConnectMode.NETWORK,
            catAudioRoute = catAudioRoute(),
        )
        if (block != RttyTxBlockReason.NONE) {
            // Logged so a "TX did nothing" report reads straight off debug.log.
            GeneralVariables.fileLog("RTTY TX: refused reason=$block")
            when (block) {
                RttyTxBlockReason.OTHER_TX ->
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.tune_blocked_tx))
                RttyTxBlockReason.SWR_LOCKED ->
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.swr_lockout_toast))
                RttyTxBlockReason.UNSUPPORTED_ROUTE ->
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.rtty_tx_unavailable_route))
                else -> Unit // RTTY_ACTIVE: the UI already shows STOP for the over in progress
            }
            return false
        }
        val usbDirect = UsbAudioDevice.isUsbDirectOutput(
            GeneralVariables.audioOutputDeviceId, GeneralVariables.usbAudioOutputVendorId,
        )
        cancelled = false
        usbRoute = usbDirect
        isTransmitting = true
        transmittingState.postValue(true)
        Thread({ play(pcm, sampleRate, usbDirect) }, "RttyTx").start()
        return true
    }

    /** Operator STOP: cancel the over in progress; the worker unkeys. No-op when idle. */
    fun stop() {
        cancelled = true
        // USB-direct route: the worker is blocked inside UsbAudioDevice.writeAudio;
        // flip its (native + fallback) cancel flag so the write returns within
        // tens of ms and the worker's finally drops PTT.
        if (usbRoute) UsbAudioNative.cancelWrite()
        // AudioTrack route: don't release here — the worker may be blocked in
        // WRITE_BLOCKING; pause()+flush() drains it so the write returns and the
        // worker's finally does the stop()/release() + PTT drop.
        val t = audioTrack ?: return
        try {
            if (t.state != AudioTrack.STATE_UNINITIALIZED && t.playState != AudioTrack.PLAYSTATE_STOPPED) {
                t.pause()
                t.flush()
            }
        } catch (_: IllegalStateException) {
            // Worker already released the track between our read and here.
        }
    }

    /** TX worker: owns keying and the audio output for its whole life. */
    private fun play(pcm: FloatArray, sampleRate: Int, usbDirect: Boolean) {
        val startedAt = System.currentTimeMillis()
        var keyed = false
        try {
            GeneralVariables.fileLog(
                "RTTY TX: start route=${if (usbDirect) "usb-direct" else "audiotrack"} samples=${pcm.size} " +
                    "rate=$sampleRate level=${(GeneralVariables.volumePercent * 100f).toInt()}% " +
                    "audioOutputDeviceId=${GeneralVariables.audioOutputDeviceId} " +
                    "usbVidPid=%04X:%04X".format(
                        GeneralVariables.usbAudioOutputVendorId, GeneralVariables.usbAudioOutputProductId,
                    ),
            )
            keyer.keyDown()
            keyed = true
            try {
                Thread.sleep(GeneralVariables.pttDelay.toLong()) // let the rig key before audio
            } catch (_: InterruptedException) {
            }
            if (usbDirect) {
                if (!cancelled) playViaUsb(pcm, sampleRate)
            } else {
                playViaAudioTrack(pcm, sampleRate)
            }
        } catch (e: Exception) {
            Log.e(TAG, "RTTY TX worker failed: $e")
        } finally {
            audioTrack?.let { t ->
                audioTrack = null
                try {
                    t.stop()
                } catch (_: IllegalStateException) {
                }
                t.release()
            }
            if (keyed) {
                try {
                    keyer.keyUp()
                } catch (e: Exception) {
                    Log.e(TAG, "RTTY key-up failed: $e")
                }
            }
            val wasCancelled = cancelled
            usbRoute = false
            isTransmitting = false
            transmittingState.postValue(false)
            GeneralVariables.fileLog(
                "RTTY TX: stop cancelled=$wasCancelled durationMs=${System.currentTimeMillis() - startedAt}",
            )
            try {
                onOverEnd()
            } catch (e: Exception) {
                Log.e(TAG, "over-end hook failed: $e")
            }
        }
    }

    /**
     * USB-direct sound card: open → writeAudio → close. `writeAudio` takes the
     * full-scale waveform and applies the TX level live, so STOP and the level
     * slider both act within tens of ms. A failure is told to the operator because
     * the rig has already keyed on dead air.
     */
    private fun playViaUsb(pcm: FloatArray, sampleRate: Int) {
        val out = usbOpener.open()
        if (out == null) {
            ToastMessage.show(GeneralVariables.getStringFromResource(R.string.tx_audio_dropped))
            return
        }
        try {
            // STOP while the device was opening (tens of ms) must not play the
            // whole over: writeAudio resets the *shared* cancel flags on entry,
            // so only this over's own flag survives the open. Check it last.
            if (cancelled) {
                GeneralVariables.fileLog("RTTY TX: STOP during device open, nothing written")
                return
            }
            val ok = out.write(pcm, sampleRate)
            GeneralVariables.fileLog("RTTY TX: writeAudio returned ${if (ok) "OK" else "FAILED"}")
            if (shouldWarnTxDropped(ok, cancelled || UsbAudioNative.writeCancelled)) {
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.tx_audio_dropped))
            }
        } finally {
            out.close()
        }
    }

    /**
     * Android sink: stream in ~50 ms chunks, re-reading the TX level per chunk so
     * a slider change lands mid-over, then wait for the tail to drain so the
     * message end isn't truncated. STOP breaks the loop and skips the wait.
     */
    private fun playViaAudioTrack(pcm: FloatArray, sampleRate: Int) {
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
        val bytesPerSample = if (float32) 4 else 2
        val targetBufBytes = (sampleRate / 5) * bytesPerSample // ~200 ms mono
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, encoding)
        val bufBytes = maxOf(targetBufBytes, if (minBuf > 0) minBuf else targetBufBytes)
        val track = AudioTrack(attrs, fmt, bufBytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        audioTrack = track
        if (GeneralVariables.audioOutputDeviceId > 0) {
            track.preferredDevice = findOutputDevice(GeneralVariables.audioOutputDeviceId)
        }
        track.play()
        track.setVolume(1.0f) // TX level is baked into the samples below

        val chunkSamples = maxOf(1, sampleRate / 20) // ~50 ms
        var framesWritten = 0
        var offset = 0
        var writeError = false
        while (offset < pcm.size) {
            if (cancelled) break
            val len = minOf(chunkSamples, pcm.size - offset)
            val chunk = scaled(pcm, offset, len, GeneralVariables.volumePercent)
            val written = if (float32) {
                track.write(chunk, 0, len, AudioTrack.WRITE_BLOCKING)
            } else {
                track.write(toInt16(chunk), 0, len, AudioTrack.WRITE_BLOCKING)
            }
            if (written < 0) {
                Log.e(TAG, "RTTY playback error: $written")
                writeError = true
                break
            }
            framesWritten += written
            offset += len
        }
        // Blocking writes return once buffered, not played; wait for the tail.
        if (!writeError && !cancelled) {
            while (!cancelled) {
                if (track.playbackHeadPosition >= framesWritten) break
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun findOutputDevice(deviceId: Int): AudioDeviceInfo? {
        val context = GeneralVariables.getMainContext() ?: return null
        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId }
    }

    private companion object {
        const val TAG = "RttyTransmitter"
    }
}

/** [len] samples of [source] from [offset], scaled by [gain]. Pure — unit-tested. */
internal fun scaled(source: FloatArray, offset: Int, len: Int, gain: Float): FloatArray =
    FloatArray(len) { i -> source[offset + i] * gain }

/** Float -1..1 → 16-bit PCM with hard clipping. Pure — unit-tested. */
internal fun toInt16(buffer: FloatArray): ShortArray =
    ShortArray(buffer.size) { i -> (buffer[i].coerceIn(-1f, 1f) * 32767.0).toInt().toShort() }
