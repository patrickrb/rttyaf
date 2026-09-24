package radio.ks3ckc.ft8af.rtty

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random

/**
 * End-to-end and unit coverage for the pure-Kotlin RTTY modem
 * ([RttyEncoder] / [RttyDecoder] / [Baudot]).
 *
 * The round-trip tests modulate text to AFSK audio and demodulate it back,
 * proving the encoder, tone detection, clock recovery and ITA2 shift handling
 * all agree. Pure DSP/math — no Android types, so no Robolectric runner.
 */
class RttyModemTest {

    /** Modulate [text] with [config], demodulate, and return the decoded text. */
    private fun roundTrip(text: String, config: RttyConfig): String {
        val samples = RttyEncoder(config).encode(text)
        return RttyDecoder(config).process(samples)
    }

    @Test
    fun roundTrip_45baud_recoversCallsign() {
        val cfg = RttyConfig(baudRate = 45.45)
        val decoded = roundTrip("CQ CQ DE KS3CKC KS3CKC K", cfg)
        assertThat(decoded).contains("CQ CQ DE KS3CKC")
    }

    @Test
    fun roundTrip_75baud_provesBaudIsConfigurable() {
        val cfg = RttyConfig(baudRate = 75.0)
        val decoded = roundTrip("CQ CQ DE KS3CKC KS3CKC K", cfg)
        assertThat(decoded).contains("CQ CQ DE KS3CKC")
    }

    @Test
    fun roundTrip_figuresAndLetters_digitsSurvive() {
        val cfg = RttyConfig(baudRate = 45.45)
        val decoded = roundTrip("599 001 FN20", cfg)
        // Digits require FIGS shifts and spaces trigger USOS back to LETTERS;
        // if any shift is dropped the digits corrupt into letters.
        assertThat(decoded).contains("599 001 FN20")
    }

    @Test
    fun roundTrip_75baud_figuresAndLetters() {
        val cfg = RttyConfig(baudRate = 75.0)
        val decoded = roundTrip("RST 599 GRID FN20", cfg)
        assertThat(decoded).contains("RST 599 GRID FN20")
    }

    @Test
    fun roundTrip_shortPcm_recoversCallsign() {
        val cfg = RttyConfig(baudRate = 45.45)
        val pcm = RttyEncoder(cfg).encodeShort("DE KS3CKC")
        val decoded = RttyDecoder(cfg).process(pcm)
        assertThat(decoded).contains("DE KS3CKC")
    }

    @Test
    fun roundTrip_streamedInChunks_matchesOneShot() {
        val cfg = RttyConfig(baudRate = 45.45)
        val samples = RttyEncoder(cfg).encode("TEST DE KS3CKC")
        val decoder = RttyDecoder(cfg)
        val sb = StringBuilder()
        var i = 0
        val chunk = 137 // deliberately odd, unaligned to bit boundaries
        while (i < samples.size) {
            val end = minOf(i + chunk, samples.size)
            sb.append(decoder.process(samples.copyOfRange(i, end)))
            i = end
        }
        assertThat(sb.toString()).contains("TEST DE KS3CKC")
    }

    @Test
    fun onCharCallback_receivesEachCharacter() {
        val cfg = RttyConfig(baudRate = 45.45)
        val collected = StringBuilder()
        val decoder = RttyDecoder(cfg) { collected.append(it) }
        val returned = decoder.process(RttyEncoder(cfg).encode("HELLO"))
        assertThat(collected.toString()).isEqualTo(returned)
        assertThat(collected.toString()).contains("HELLO")
    }

    @Test
    fun lightGaussianNoise_stillRecoversCallsign() {
        val cfg = RttyConfig(baudRate = 45.45)
        val clean = RttyEncoder(cfg).encode("CQ DE KS3CKC KS3CKC")
        val rng = Random(1234L)
        val noisy = FloatArray(clean.size) { clean[it] + (rng.nextGaussian() * 0.08).toFloat() }
        val decoded = RttyDecoder(cfg).process(noisy)
        assertThat(decoded).contains("CQ DE KS3CKC")
    }

    @Test
    fun reverse_roundTripsWithMatchingConfig() {
        val cfg = RttyConfig(baudRate = 45.45, reverse = true)
        val decoded = roundTrip("DE KS3CKC", cfg)
        assertThat(decoded).contains("DE KS3CKC")
    }

    @Test
    fun ita2Table_knownLetterMappings() {
        // Standard ITA2 LETTERS codes.
        assertThat(Baudot.decode(0x01, figures = false)).isEqualTo('E')
        assertThat(Baudot.decode(0x03, figures = false)).isEqualTo('A')
        assertThat(Baudot.decode(0x10, figures = false)).isEqualTo('T')
        assertThat(Baudot.encode('E')).isEqualTo(Baudot.EncodeEntry(0x01, Baudot.Shift.LETTERS))
        assertThat(Baudot.encode('A')).isEqualTo(Baudot.EncodeEntry(0x03, Baudot.Shift.LETTERS))
    }

    @Test
    fun ita2Table_knownFigureMappings() {
        // Standard US-TTY FIGURES codes: 0x01='3', 0x10='5', 0x16='0'.
        assertThat(Baudot.decode(0x01, figures = true)).isEqualTo('3')
        assertThat(Baudot.decode(0x10, figures = true)).isEqualTo('5')
        assertThat(Baudot.decode(0x16, figures = true)).isEqualTo('0')
        assertThat(Baudot.encode('5')).isEqualTo(Baudot.EncodeEntry(0x10, Baudot.Shift.FIGURES))
        assertThat(Baudot.encode('0')).isEqualTo(Baudot.EncodeEntry(0x16, Baudot.Shift.FIGURES))
    }

    @Test
    fun ita2Table_shiftAndNeutralCodes() {
        assertThat(Baudot.LTRS).isEqualTo(0x1F)
        assertThat(Baudot.FIGS).isEqualTo(0x1B)
        // Shift-neutral codes decode identically in both sets.
        assertThat(Baudot.decode(0x04, figures = false)).isEqualTo(' ')
        assertThat(Baudot.decode(0x04, figures = true)).isEqualTo(' ')
        assertThat(Baudot.encode(' ')?.shift).isNull()
    }

    @Test
    fun textToCodes_insertsShiftCodesForFigures() {
        val cfg = RttyConfig(baudRate = 45.45)
        // Leading LTRS sync, then A, then FIGS, then '1' (0x17).
        val codes = RttyEncoder(cfg).textToCodes("A1")
        assertThat(codes).containsExactly(
            Baudot.LTRS, 0x03, Baudot.FIGS, 0x17,
        ).inOrder()
    }

    @Test
    fun squelch_suppressesNoiseOnlyInput() {
        // No carrier — just band noise. With squelch on, the framer must not
        // emit random Baudot; with squelch off it decodes the noise into junk.
        val rng = Random(7L)
        val noise = FloatArray(24000) { (rng.nextGaussian() * 0.2).toFloat() }
        val gated = RttyDecoder(RttyConfig(baudRate = 45.45, squelch = true)).process(noise)
        val ungated = RttyDecoder(RttyConfig(baudRate = 45.45, squelch = false)).process(noise.copyOf())
        assertThat(gated.length).isAtMost(1)
        assertThat(ungated.length).isGreaterThan(gated.length)
    }

    @Test
    fun squelchDisabled_copiesWeakSignalBelowFloor() {
        // A low-amplitude signal whose tone energy sits under the squelch floor
        // is gated when squelch is on but still decodes with squelch off.
        val cfg = RttyConfig(baudRate = 45.45, squelch = false)
        val weak = RttyEncoder(cfg, amplitude = 0.05).encode("DE KS3CKC")
        assertThat(RttyDecoder(cfg).process(weak)).contains("DE KS3CKC")
    }

    @Test
    fun roundTrip_fractionalStopBitsBelowHalf() {
        // stopBits < 0.5 means the next start edge arrives sooner than the old
        // fixed 6.5-bit stop sample; the framer must still catch every character.
        val cfg = RttyConfig(baudRate = 45.45, stopBits = 0.3)
        assertThat(roundTrip("AB CD 599", cfg)).contains("AB CD 599")
    }

    @Test
    fun textToCodes_usosReshiftsAfterSpace() {
        val cfg = RttyConfig(baudRate = 45.45, unshiftOnSpace = true)
        // "1 2": FIGS,1,SPACE,(USOS->LETTERS)FIGS,2. The second FIGS proves the
        // space unshifted the encoder's tracked state.
        val codes = RttyEncoder(cfg).textToCodes("1 2")
        assertThat(codes).containsExactly(
            Baudot.LTRS, Baudot.FIGS, 0x17, Baudot.SPACE, Baudot.FIGS, 0x13,
        ).inOrder()
    }

    // ---- TX wiring (feat/rtty-tx) ------------------------------------------

    @Test
    fun txMessage_framesWithLeadingSpaceAndCrlf() {
        // The over is wrapped with a leading idle space and a trailing CR/LF so a
        // receiver has a resting character before the payload and a clean line end.
        assertThat(txMessage("CQ DE KS3CKC")).isEqualTo(" CQ DE KS3CKC\r\n")
        // Operator whitespace is trimmed before framing so double spacing/newlines
        // don't leak into the wrapped message.
        assertThat(txMessage("  TEST  ")).isEqualTo(" TEST\r\n")
    }

    @Test
    fun txSampleRate_prefersSoundCardRateButFallsBackWhenImplausible() {
        // Normal case: use the reported sound-card rate.
        assertThat(txSampleRate(audioRate = 48000, fallback = 12000)).isEqualTo(48000)
        assertThat(txSampleRate(audioRate = 44100, fallback = 12000)).isEqualTo(44100)
        // Misconfigured / unset audio rate falls back to the modem's own rate so
        // we never build an AudioTrack at an impossible rate.
        assertThat(txSampleRate(audioRate = 0, fallback = 12000)).isEqualTo(12000)
        assertThat(txSampleRate(audioRate = 100, fallback = 12000)).isEqualTo(12000)
    }

    @Test
    fun txWaveform_atSoundCardRate_isDecodable() {
        // Transmit modulates at the sound-card rate (e.g. 48 kHz), not the 12 kHz
        // RX rate. Prove that waveform still round-trips: same tones, more samples.
        val base = RttyConfig(baudRate = 45.45)
        val txCfg = base.copy(sampleRate = 48000)
        val samples = RttyEncoder(txCfg).encode(txMessage("CQ DE KS3CKC"))
        val decoded = RttyDecoder(txCfg).process(samples)
        assertThat(decoded).contains("CQ DE KS3CKC")
    }
}
