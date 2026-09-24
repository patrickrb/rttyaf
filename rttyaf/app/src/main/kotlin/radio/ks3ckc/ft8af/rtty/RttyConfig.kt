package radio.ks3ckc.ft8af.rtty

/**
 * Parameters shared by [RttyEncoder] and [RttyDecoder].
 *
 * Frequency convention (upper-sideband, the amateur norm): the **MARK** tone
 * (logical 1 — start-of-idle, stop bit, and set data bits) is the *lower* audio
 * frequency [markHz]; the **SPACE** tone (logical 0 — start bit and cleared
 * data bits) sits [shiftHz] above it. [reverse] swaps the two audio tones for
 * lower-sideband or reversed-polarity links without changing the bit logic.
 *
 * @param baudRate        symbol rate (45.45 is the classic US amateur speed)
 * @param shiftHz         mark→space frequency separation (170 Hz standard)
 * @param markHz          mark audio frequency in Hz (2125 = "high tones")
 * @param sampleRate      audio sample rate in Hz
 * @param stopBits        stop-bit length in bit periods (1.5 is standard)
 * @param reverse         swap which audio tone represents mark vs. space
 * @param unshiftOnSpace  USOS: a decoded SPACE reverts the shift to LETTERS
 * @param squelch         gate the decoder on tone energy so an idle/noisy
 *                        channel doesn't print random characters
 * @param squelchFloor    smoothed tone-energy level below which the channel is
 *                        treated as no-signal. Calibrated for the encoder's
 *                        0.5 peak amplitude (mark energy ~0.0625) vs. band
 *                        noise (~1e-3); lower it to copy weaker signals.
 */
data class RttyConfig(
    val baudRate: Double = 45.45,
    val shiftHz: Int = 170,
    val markHz: Double = 2125.0,
    val sampleRate: Int = 12000,
    val stopBits: Double = 1.5,
    val reverse: Boolean = false,
    val unshiftOnSpace: Boolean = true,
    val squelch: Boolean = true,
    val squelchFloor: Double = 0.004,
) {
    init {
        require(baudRate > 0.0) { "baudRate must be > 0" }
        require(sampleRate > 0) { "sampleRate must be > 0" }
        require(stopBits > 0.0) { "stopBits must be > 0" }
        require(squelchFloor >= 0.0) { "squelchFloor must be >= 0" }
    }

    /** The nominal SPACE audio frequency (mark + shift), before [reverse]. */
    val spaceHz: Double get() = markHz + shiftHz

    /** Audio frequency emitted for a logical MARK (1), honouring [reverse]. */
    val markToneHz: Double get() = if (reverse) spaceHz else markHz

    /** Audio frequency emitted for a logical SPACE (0), honouring [reverse]. */
    val spaceToneHz: Double get() = if (reverse) markHz else spaceHz

    /** Audio samples in one bit period (may be fractional). */
    val samplesPerBit: Double get() = sampleRate / baudRate
}
