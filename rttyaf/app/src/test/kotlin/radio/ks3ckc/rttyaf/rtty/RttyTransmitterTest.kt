package radio.ks3ckc.rttyaf.rtty

import com.google.common.truth.Truth.assertThat
import com.k1af.rttyaf.GeneralVariables
import com.k1af.rttyaf.connector.ConnectMode
import com.k1af.rttyaf.database.ControlMode
import com.k1af.rttyaf.wave.UsbAudioDevice
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The RTTY TX worker's hardware-control discipline on the USB-direct route,
 * driven through the [RttyUsbOutputOpener] seam: the rig must be keyed before
 * audio and unkeyed on *every* exit (success, open failure, write failure, write
 * exception, STOP), and an opened device must always be closed. Robolectric only
 * because TX state is a LiveData and ToastMessage needs a main looper; no Android
 * audio is touched.
 */
@RunWith(RobolectricTestRunner::class)
class RttyTransmitterTest {

    /** Keying + USB events in the order the worker produced them. */
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val keyer = object : RttyKeyer {
        override fun keyDown() { events.add("keyDown") }
        override fun keyUp() { events.add("keyUp") }
    }

    private var ft8Busy = false
    private lateinit var tx: RttyTransmitter
    private var savedDeviceId = 0
    private var savedVid = 0
    private var savedPttDelay = 0

    @Before
    fun usbDirectStation() {
        savedDeviceId = GeneralVariables.audioOutputDeviceId
        savedVid = GeneralVariables.usbAudioOutputVendorId
        savedPttDelay = GeneralVariables.pttDelay
        // The station from the field report: "USB direct" output on a CM108 codec.
        GeneralVariables.audioOutputDeviceId = -1
        GeneralVariables.usbAudioOutputVendorId = 0x0D8C
        GeneralVariables.connectMode = ConnectMode.USB_CABLE
        GeneralVariables.controlMode = ControlMode.CAT
        GeneralVariables.pttDelay = 0
        tx = RttyTransmitter(keyer, ft8Busy = { ft8Busy })
    }

    @After
    fun restore() {
        GeneralVariables.audioOutputDeviceId = savedDeviceId
        GeneralVariables.usbAudioOutputVendorId = savedVid
        GeneralVariables.pttDelay = savedPttDelay
    }

    /** A USB output whose write result (or exception) the test chooses. */
    private inner class FakeOutput(private val result: Boolean, private val failure: RuntimeException? = null) : RttyUsbOutput {
        var written: FloatArray? = null
        var rate = 0

        override fun write(pcm: FloatArray, sampleRate: Int): Boolean {
            events.add("write")
            written = pcm
            rate = sampleRate
            failure?.let { throw it }
            return result
        }

        override fun close() {
            events.add("close")
        }
    }

    private val pcm = floatArrayOf(0.1f, -0.2f, 0.3f)

    private fun awaitWorker() {
        val deadline = System.currentTimeMillis() + 5000
        while (tx.isTransmitting && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertThat(tx.isTransmitting).isFalse()
    }

    @Test
    fun success_keysThenWritesThenClosesThenUnkeys() {
        val out = FakeOutput(true)
        tx.usbOpener = RttyUsbOutputOpener { events.add("open"); out }

        assertThat(tx.transmit(pcm, 12000)).isTrue()
        awaitWorker()

        assertThat(events).containsExactly("keyDown", "open", "write", "close", "keyUp").inOrder()
        // Full-scale samples at the modem's rate: writeAudio applies the TX level live.
        assertThat(out.written).isSameInstanceAs(pcm)
        assertThat(out.rate).isEqualTo(12000)
    }

    @Test
    fun openFailure_stillUnkeys_andNeverWrites() {
        tx.usbOpener = RttyUsbOutputOpener { events.add("open"); null }

        assertThat(tx.transmit(pcm, 12000)).isTrue() // the over started; the rig keyed
        awaitWorker()

        assertThat(events).containsExactly("keyDown", "open", "keyUp").inOrder()
    }

    @Test
    fun writeFailure_closesDeviceAndUnkeys() {
        tx.usbOpener = RttyUsbOutputOpener { FakeOutput(false) }

        tx.transmit(pcm, 12000)
        awaitWorker()

        assertThat(events).containsExactly("keyDown", "write", "close", "keyUp").inOrder()
    }

    @Test
    fun writeException_closesDeviceAndUnkeys() {
        tx.usbOpener = RttyUsbOutputOpener { FakeOutput(true, IllegalStateException("USB gone")) }

        tx.transmit(pcm, 12000)
        awaitWorker()

        // A stuck carrier is never acceptable: the finally owns close + PTT.
        assertThat(events).containsExactly("keyDown", "write", "close", "keyUp").inOrder()
    }

    @Test
    fun stopDuringDeviceOpen_isNotLost() {
        // writeAudio resets the shared cancel flags on entry, so a STOP that lands
        // while the device is opening must be remembered by the over itself.
        val opening = CountDownLatch(1)
        val released = CountDownLatch(1)
        val out = FakeOutput(true)
        tx.usbOpener = RttyUsbOutputOpener {
            opening.countDown()
            released.await(5, TimeUnit.SECONDS)
            out
        }

        tx.transmit(pcm, 12000)
        assertThat(opening.await(5, TimeUnit.SECONDS)).isTrue()
        tx.stop() // operator STOP while the open is in flight
        released.countDown() // ...and the open completes afterwards
        awaitWorker()

        assertThat(events).containsExactly("keyDown", "close", "keyUp").inOrder()
        assertThat(out.written).isNull()
    }

    @Test
    fun secondOverWhileOneIsOnAir_isRefused() {
        val released = CountDownLatch(1)
        tx.usbOpener = RttyUsbOutputOpener {
            released.await(5, TimeUnit.SECONDS)
            FakeOutput(true)
        }

        assertThat(tx.transmit(pcm, 12000)).isTrue()
        assertThat(tx.transmit(pcm, 12000)).isFalse() // single-flight
        released.countDown()
        awaitWorker()

        assertThat(Collections.frequency(events, "keyDown")).isEqualTo(1)
        assertThat(Collections.frequency(events, "keyUp")).isEqualTo(1)
    }

    @Test
    fun ft8SequencerBusy_refusesWithoutKeying() {
        ft8Busy = true
        tx.usbOpener = RttyUsbOutputOpener { FakeOutput(true) }

        assertThat(tx.transmit(pcm, 12000)).isFalse()
        assertThat(events).isEmpty()
    }

    @Test
    fun emptyBuffer_isNotAnOver() {
        assertThat(tx.transmit(FloatArray(0), 12000)).isFalse()
        assertThat(events).isEmpty()
    }

    // ---- pure gates ---------------------------------------------------------

    @Test
    fun blockReason_isOrderedBySeverity() {
        assertThat(rttyTxBlockReason(false, false, false, false)).isEqualTo(RttyTxBlockReason.NONE)
        assertThat(rttyTxBlockReason(true, true, true, true)).isEqualTo(RttyTxBlockReason.FT8_ACTIVE)
        // An over already on the air outranks a route complaint: the UI shows STOP, not a toast.
        assertThat(rttyTxBlockReason(false, true, true, false)).isEqualTo(RttyTxBlockReason.RTTY_ACTIVE)
        assertThat(rttyTxBlockReason(false, false, true, false)).isEqualTo(RttyTxBlockReason.UNSUPPORTED_ROUTE)
        assertThat(rttyTxBlockReason(false, false, false, true)).isEqualTo(RttyTxBlockReason.UNSUPPORTED_ROUTE)
    }

    @Test
    fun usbDirectOutput_isTheSavedUsbSoundCard() {
        // "USB direct" is output device id -1 plus a saved vendor id.
        assertThat(UsbAudioDevice.isUsbDirectOutput(-1, 0x0D8C)).isTrue()
        // -1 with nothing saved is "nothing picked yet" -> AudioTrack.
        assertThat(UsbAudioDevice.isUsbDirectOutput(-1, 0)).isFalse()
        // A real Android device id means the AudioTrack sink even if a VID lingers.
        assertThat(UsbAudioDevice.isUsbDirectOutput(0, 0x0D8C)).isFalse()
        assertThat(UsbAudioDevice.isUsbDirectOutput(17, 0x0D8C)).isFalse()
    }

    @Test
    fun txDroppedWarning_skipsTheOperatorsOwnStop() {
        assertThat(shouldWarnTxDropped(success = false, cancelled = false)).isTrue()
        assertThat(shouldWarnTxDropped(success = false, cancelled = true)).isFalse()
        assertThat(shouldWarnTxDropped(success = true, cancelled = false)).isFalse()
    }

    @Test
    fun audioHelpers_scaleAndClip() {
        assertThat(scaled(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), 1, 2, 0.5f).toList())
            .containsExactly(0.1f, 0.15f).inOrder()
        val s = toInt16(floatArrayOf(0f, 1f, -1f, 2f, -2f, 0.5f))
        assertThat(s[0]).isEqualTo(0.toShort())
        assertThat(s[1]).isEqualTo(32767.toShort())
        assertThat(s[2]).isEqualTo((-32767).toShort())
        assertThat(s[3]).isEqualTo(32767.toShort()) // clipped
        assertThat(s[4]).isEqualTo((-32767).toShort())
        assertThat(s[5]).isEqualTo(16383.toShort())
    }
}
