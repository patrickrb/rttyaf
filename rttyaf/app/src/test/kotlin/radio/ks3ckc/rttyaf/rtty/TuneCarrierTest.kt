package radio.ks3ckc.rttyaf.rtty

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The tune carrier's pure gate, lifted from FT8AF's `FT8TransmitSignal`
 * (issue #408 there): ordered by severity so a live transmission wins, then the
 * TX inhibits the carrier must not bypass, then route support.
 */
class TuneCarrierTest {

    private fun gate(
        txActive: Boolean = false,
        swrLocked: Boolean = false,
        wspr: Boolean = false,
        catAudio: Boolean = false,
        network: Boolean = false,
        usbDirect: Boolean = false,
    ) = tuneBlockReason(txActive, swrLocked, wspr, catAudio, network, usbDirect)

    @Test
    fun clearStation_mayTune() {
        assertThat(gate()).isEqualTo(TuneBlockReason.NONE)
    }

    @Test
    fun liveTransmission_outranksEverything() {
        assertThat(gate(txActive = true, swrLocked = true, wspr = true, usbDirect = true))
            .isEqualTo(TuneBlockReason.TX_ACTIVE)
    }

    @Test
    fun swrLockout_isNotBypassedByTune() {
        assertThat(gate(swrLocked = true, wspr = true)).isEqualTo(TuneBlockReason.SWR_LOCKED)
    }

    @Test
    fun wsprSubBand_neverGetsACarrier() {
        assertThat(gate(wspr = true, network = true)).isEqualTo(TuneBlockReason.WSPR_FREQUENCY)
    }

    @Test
    fun routesWithoutASender_areRefused() {
        assertThat(gate(catAudio = true)).isEqualTo(TuneBlockReason.UNSUPPORTED_ROUTE)
        assertThat(gate(network = true)).isEqualTo(TuneBlockReason.UNSUPPORTED_ROUTE)
        // The carrier streams through AudioTrack only; the USB-direct route is RTTY TX's.
        assertThat(gate(usbDirect = true)).isEqualTo(TuneBlockReason.UNSUPPORTED_ROUTE)
    }
}
