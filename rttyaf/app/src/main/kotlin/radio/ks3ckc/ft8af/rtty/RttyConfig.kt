package radio.ks3ckc.ft8af.rtty

/**
 * Parameters shared by [RttyEncoder] and [RttyDecoder].
 *
 * Frequency convention: on the air, **MARK** (logical 1 — idle, stop bit, set
 * data bits) is the *higher* RF frequency and **SPACE** (logical 0 — start bit,
 * cleared data bits) the lower. AFSK RTTY is run with the rig in **LSB**, which
 * inverts the audio: mark becomes the *lower* audio tone [markHz] (2125 Hz with
 * the standard "high tones") and space sits [shiftHz] above it (2295 Hz). That
 * is the convention here. [reverse] swaps the two audio tones for a rig run in
 * USB (or a reversed-polarity link) without changing the bit logic.
 *
 * @param baudRate        symbol rate (45.45 is the classic US amateur speed)
 * @param shiftHz         mark→space frequency separation (170 Hz standard)
 * @param markHz          mark audio frequency in Hz (2125 = "high tones")
 * @param sampleRate      audio sample rate in Hz
 * @param stopBits        stop-bit length in bit periods (1.5 is standard)
 * @param reverse         swap which audio tone represents mark vs. space (rig in USB)
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
