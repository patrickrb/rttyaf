package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for the pure gates behind {@link FT8TransmitSignal#transmitRtty}.
 *
 * <p>Background: a station with a CM108-style USB codec picked as "USB direct"
 * output pressed TX and nothing happened — the RTTY path refused that route
 * outright (a stale assumption that the libusb library had been dropped from
 * the fork). The route is now served, so the gate must <em>not</em> know about
 * it, and the one USB-direct predicate is shared with the FT8 and tune paths.
 */
public class RttyTxRouteTest {

    @Test
    public void clearStation_isNotBlocked() {
        assertThat(FT8TransmitSignal.rttyTxBlockReason(false, false, false, false))
                .isEqualTo(FT8TransmitSignal.RttyTxBlockReason.NONE);
    }

    @Test
    public void ft8SequencerArmedOrOnAir_winsOverEverything() {
        assertThat(FT8TransmitSignal.rttyTxBlockReason(true, true, true, true))
                .isEqualTo(FT8TransmitSignal.RttyTxBlockReason.FT8_ACTIVE);
    }

    @Test
    public void overAlreadyOnAir_isSingleFlight() {
        assertThat(FT8TransmitSignal.rttyTxBlockReason(false, true, false, false))
                .isEqualTo(FT8TransmitSignal.RttyTxBlockReason.RTTY_ACTIVE);
        // ...and it outranks a route complaint: the UI shows STOP, not a toast.
        assertThat(FT8TransmitSignal.rttyTxBlockReason(false, true, true, false))
                .isEqualTo(FT8TransmitSignal.RttyTxBlockReason.RTTY_ACTIVE);
    }

    @Test
    public void networkOrCatAudioRoutes_areRefused() {
        assertThat(FT8TransmitSignal.rttyTxBlockReason(false, false, true, false))
                .isEqualTo(FT8TransmitSignal.RttyTxBlockReason.UNSUPPORTED_ROUTE);
        assertThat(FT8TransmitSignal.rttyTxBlockReason(false, false, false, true))
                .isEqualTo(FT8TransmitSignal.RttyTxBlockReason.UNSUPPORTED_ROUTE);
    }

    @Test
    public void usbDirectOutput_isTheSavedUsbSoundCard() {
        // "USB direct" is output device id -1 plus a saved vendor id.
        assertThat(FT8TransmitSignal.isUsbDirectOutput(-1, 0x0D8C)).isTrue();
        // A -1 with no saved device is just "nothing picked yet" -> AudioTrack.
        assertThat(FT8TransmitSignal.isUsbDirectOutput(-1, 0)).isFalse();
        // A real Android output device id means the AudioTrack sink, even if a
        // USB VID is still remembered from an earlier pick.
        assertThat(FT8TransmitSignal.isUsbDirectOutput(0, 0x0D8C)).isFalse();
        assertThat(FT8TransmitSignal.isUsbDirectOutput(17, 0x0D8C)).isFalse();
    }
}
