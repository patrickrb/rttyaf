package com.k1af.rttyaf.wave;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.rttyaf.wave.UsbAudioDevice.ControlClaimRegistry;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests the two guards around detaching the kernel USB-audio driver (PR #20 review):
 * {@link UsbAudioDevice#otherDirectionAllowsDetach} refuses to retire the card from Android
 * while the other audio direction may still be using it through Android, and
 * {@link ControlClaimRegistry} lets exactly one of several open connections to the same
 * card hold the AudioControl claim, so RX and TX never steal it from each other.
 */
public class UsbAudioDetachRoutingTest {

    private static final int CM108_VID = 0x0D8C;

    // --- otherDirectionAllowsDetach ---

    @Test
    public void otherDirectionUsbDirect_allowsDetach() {
        assertThat(UsbAudioDevice.otherDirectionAllowsDetach(-1, CM108_VID, false, false))
                .isTrue();
    }

    @Test
    public void otherDirectionUsbDirectWithoutSavedVid_refuses() {
        // -1 with no VID isn't usable as USB direct; that direction falls back to Android.
        assertThat(UsbAudioDevice.otherDirectionAllowsDetach(-1, 0, false, false)).isFalse();
    }

    @Test
    public void otherDirectionUsbDirectThatFellBackToAndroid_refuses() {
        // RX's USB-direct open failed and MicRecorder fell back to AudioRecord; a TX
        // detach would take the card away from that fallback.
        assertThat(UsbAudioDevice.otherDirectionAllowsDetach(-1, CM108_VID, true, false))
                .isFalse();
    }

    @Test
    public void otherDirectionSystemDefault_refuses() {
        // Android's default route goes to an attached USB card.
        assertThat(UsbAudioDevice.otherDirectionAllowsDetach(0, 0, false, false)).isFalse();
    }

    @Test
    public void otherDirectionExplicitUsbAndroidDevice_refuses() {
        // Review bug 1/2: RX USB direct + TX AudioTrack on the CM108's Android device
        // (or the reverse) — detaching would make that device vanish.
        assertThat(UsbAudioDevice.otherDirectionAllowsDetach(42, 0, false, true)).isFalse();
    }

    @Test
    public void otherDirectionExplicitNonUsbAndroidDevice_allowsDetach() {
        // e.g. TX audio to a Bluetooth or built-in device: the USB card isn't involved.
        assertThat(UsbAudioDevice.otherDirectionAllowsDetach(42, 0, false, false)).isTrue();
    }

    // --- ControlClaimRegistry ---

    private static final String DEV = "/dev/bus/usb/001/004";

    @Test
    public void firstOwnerClaims_secondOwnerIsSkippedWithoutClaiming() {
        ControlClaimRegistry<Object> reg = new ControlClaimRegistry<>();
        Object rx = new Object();
        Object tx = new Object();
        AtomicInteger claims = new AtomicInteger();

        assertThat(reg.claimUnlessHeld(DEV, rx, () -> { claims.incrementAndGet(); return true; }))
                .isTrue();
        assertThat(reg.claimUnlessHeld(DEV, tx, () -> { claims.incrementAndGet(); return true; }))
                .isNull();
        assertThat(claims.get()).isEqualTo(1);
        assertThat(reg.holder(DEV)).isSameInstanceAs(rx);
    }

    @Test
    public void txOpenedFirst_rxIsSkipped_noMatterWhichDirection() {
        // The old check only looked at the RX session, so a TX holder was invisible to RX.
        ControlClaimRegistry<Object> reg = new ControlClaimRegistry<>();
        Object tx = new Object();
        Object rx = new Object();
        reg.claimUnlessHeld(DEV, tx, () -> true);

        assertThat(reg.claimUnlessHeld(DEV, rx, () -> true)).isNull();
    }

    @Test
    public void afterHolderReleases_nextOwnerClaims() {
        // The RX reinit window: the old session closes, then a TX or the new RX claims.
        ControlClaimRegistry<Object> reg = new ControlClaimRegistry<>();
        Object oldRx = new Object();
        Object next = new Object();
        reg.claimUnlessHeld(DEV, oldRx, () -> true);
        reg.release(DEV, oldRx);

        assertThat(reg.claimUnlessHeld(DEV, next, () -> true)).isTrue();
        assertThat(reg.holder(DEV)).isSameInstanceAs(next);
    }

    @Test
    public void releaseByNonHolder_keepsTheHolder() {
        ControlClaimRegistry<Object> reg = new ControlClaimRegistry<>();
        Object rx = new Object();
        Object tx = new Object();
        reg.claimUnlessHeld(DEV, rx, () -> true);
        reg.release(DEV, tx);

        assertThat(reg.holder(DEV)).isSameInstanceAs(rx);
    }

    @Test
    public void refusedClaim_isNotRecorded() {
        ControlClaimRegistry<Object> reg = new ControlClaimRegistry<>();
        Object rx = new Object();
        Object tx = new Object();

        assertThat(reg.claimUnlessHeld(DEV, rx, () -> false)).isFalse();
        assertThat(reg.holder(DEV)).isNull();
        assertThat(reg.claimUnlessHeld(DEV, tx, () -> true)).isTrue();
    }

    @Test
    public void differentDevices_areTrackedSeparately() {
        ControlClaimRegistry<Object> reg = new ControlClaimRegistry<>();
        Object a = new Object();
        Object b = new Object();

        assertThat(reg.claimUnlessHeld(DEV, a, () -> true)).isTrue();
        assertThat(reg.claimUnlessHeld("/dev/bus/usb/001/005", b, () -> true)).isTrue();
    }

    @Test
    public void holderReclaiming_runsTheClaimAgain() {
        ControlClaimRegistry<Object> reg = new ControlClaimRegistry<>();
        Object rx = new Object();
        AtomicInteger claims = new AtomicInteger();
        reg.claimUnlessHeld(DEV, rx, () -> { claims.incrementAndGet(); return true; });

        assertThat(reg.claimUnlessHeld(DEV, rx, () -> { claims.incrementAndGet(); return true; }))
                .isTrue();
        assertThat(claims.get()).isEqualTo(2);
    }
}
