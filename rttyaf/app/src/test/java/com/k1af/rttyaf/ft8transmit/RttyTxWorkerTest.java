package com.k1af.rttyaf.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.rttyaf.Ft8Message;
import com.k1af.rttyaf.GeneralVariables;
import com.k1af.rttyaf.connector.ConnectMode;
import com.k1af.rttyaf.database.ControlMode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The RTTY TX worker's hardware-control discipline on the USB-direct route,
 * driven through the {@link FT8TransmitSignal.RttyUsbOutputOpener} seam: the
 * rig must be keyed before audio and unkeyed on <em>every</em> exit (success,
 * open failure, write failure, write exception, STOP), and an opened device
 * must always be closed. Robolectric only because keying state is a LiveData
 * and ToastMessage needs a main looper; no Android audio is touched.
 */
@RunWith(RobolectricTestRunner.class)
public class RttyTxWorkerTest {

    /** Keying + USB events in the order the worker produced them. */
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    private final OnDoTransmitted keyer = new OnDoTransmitted() {
        @Override public void onBeforeTransmit(Ft8Message message, int functionOder) {}
        @Override public void onAfterTransmit(Ft8Message message, int functionOder) {}
        @Override public void onTransmitByWifi(Ft8Message message) {}
        @Override public boolean supportTransmitOverCAT() { return false; }
        @Override public void onTransmitOverCAT(Ft8Message message) {}
        @Override public void onTuneKeyDown() { events.add("keyDown"); }
        @Override public void onTuneKeyUp() { events.add("keyUp"); }
    };

    private FT8TransmitSignal tx;
    private int savedDeviceId;
    private int savedVid;
    private int savedPttDelay;

    @Before
    public void usbDirectStation() {
        savedDeviceId = GeneralVariables.audioOutputDeviceId;
        savedVid = GeneralVariables.usbAudioOutputVendorId;
        savedPttDelay = GeneralVariables.pttDelay;
        // The station from the field report: "USB direct" output on a CM108 codec.
        GeneralVariables.audioOutputDeviceId = -1;
        GeneralVariables.usbAudioOutputVendorId = 0x0D8C;
        GeneralVariables.connectMode = ConnectMode.USB_CABLE;
        GeneralVariables.controlMode = ControlMode.CAT;
        GeneralVariables.pttDelay = 0;
        tx = new FT8TransmitSignal(null, keyer, null);
    }

    @After
    public void restore() {
        GeneralVariables.audioOutputDeviceId = savedDeviceId;
        GeneralVariables.usbAudioOutputVendorId = savedVid;
        GeneralVariables.pttDelay = savedPttDelay;
    }

    /** A USB output whose write result (or exception) the test chooses. */
    private final class FakeOutput implements FT8TransmitSignal.RttyUsbOutput {
        final boolean result;
        final RuntimeException failure;
        float[] written;
        int rate;

        FakeOutput(boolean result, RuntimeException failure) {
            this.result = result;
            this.failure = failure;
        }

        @Override
        public boolean write(float[] pcm, int sampleRate) {
            events.add("write");
            written = pcm;
            rate = sampleRate;
            if (failure != null) throw failure;
            return result;
        }

        @Override
        public void close() {
            events.add("close");
        }
    }

    private static final float[] PCM = {0.1f, -0.2f, 0.3f};

    private void awaitWorker() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (tx.isRttyTransmitting() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(tx.isRttyTransmitting()).isFalse();
    }

    @Test
    public void success_keysThenWritesThenClosesThenUnkeys() throws Exception {
        FakeOutput out = new FakeOutput(true, null);
        tx.rttyUsbOpener = () -> { events.add("open"); return out; };

        assertThat(tx.transmitRtty(PCM, 12000)).isTrue();
        awaitWorker();

        assertThat(events).containsExactly("keyDown", "open", "write", "close", "keyUp").inOrder();
        // Full-scale samples at the modem's rate: writeAudio applies the TX level live.
        assertThat(out.written).isSameInstanceAs(PCM);
        assertThat(out.rate).isEqualTo(12000);
    }

    @Test
    public void openFailure_stillUnkeys_andNeverWrites() throws Exception {
        tx.rttyUsbOpener = () -> { events.add("open"); return null; };

        assertThat(tx.transmitRtty(PCM, 12000)).isTrue(); // the over started; the rig keyed
        awaitWorker();

        assertThat(events).containsExactly("keyDown", "open", "keyUp").inOrder();
    }

    @Test
    public void writeFailure_closesDeviceAndUnkeys() throws Exception {
        tx.rttyUsbOpener = () -> new FakeOutput(false, null);

        tx.transmitRtty(PCM, 12000);
        awaitWorker();

        assertThat(events).containsExactly("keyDown", "write", "close", "keyUp").inOrder();
    }

    @Test
    public void writeException_closesDeviceAndUnkeys() throws Exception {
        tx.rttyUsbOpener = () -> new FakeOutput(true, new IllegalStateException("USB gone"));

        tx.transmitRtty(PCM, 12000);
        awaitWorker();

        // A stuck carrier is never acceptable: the finally blocks own close + PTT.
        assertThat(events).containsExactly("keyDown", "write", "close", "keyUp").inOrder();
    }

    @Test
    public void stopDuringDeviceOpen_isNotLost() throws Exception {
        // Copilot review (PR #14): writeAudio resets the shared cancel flags on
        // entry, so a STOP that lands while the device is opening used to be
        // erased and the whole over played with PTT down.
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        FakeOutput out = new FakeOutput(true, null);
        tx.rttyUsbOpener = () -> {
            opening.countDown();
            try {
                released.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return out;
        };

        tx.transmitRtty(PCM, 12000);
        assertThat(opening.await(5, TimeUnit.SECONDS)).isTrue();
        tx.stopRttyTx();          // operator STOP while the open is in flight
        released.countDown();     // ...and the open completes afterwards
        awaitWorker();

        assertThat(events).containsExactly("keyDown", "close", "keyUp").inOrder();
        assertThat(out.written).isNull();
    }

    @Test
    public void secondOverWhileOneIsOnAir_isRefused() throws Exception {
        CountDownLatch released = new CountDownLatch(1);
        tx.rttyUsbOpener = () -> {
            try {
                released.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return new FakeOutput(true, null);
        };

        assertThat(tx.transmitRtty(PCM, 12000)).isTrue();
        assertThat(tx.transmitRtty(PCM, 12000)).isFalse(); // single-flight
        released.countDown();
        awaitWorker();

        assertThat(Collections.frequency(events, "keyDown")).isEqualTo(1);
        assertThat(Collections.frequency(events, "keyUp")).isEqualTo(1);
    }
}
