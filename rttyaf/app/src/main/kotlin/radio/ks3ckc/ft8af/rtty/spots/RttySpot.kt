package radio.ks3ckc.ft8af.rtty.spots

import kotlin.math.abs

/** Where a spot came from. RBN spots are machine decodes with an exact frequency; PSK are reception reports. */
enum class SpotSource { RBN, PSK }

/**
 * One RTTY station seen on the air by a spotting network.
 *
 * @param call        the spotted (transmitting) station
 * @param freqHz      RF frequency of the signal's MARK tone in Hz (RBN convention)
 * @param source      which network produced the freshest report
 * @param snrDb       reported signal-to-noise ratio, if any
 * @param baud        reported baud rate, if any (RBN reports "45 BPS")
 * @param spotters    distinct receivers that reported this station
 * @param lastHeardMs epoch ms of the most recent report
 * @param isCq        the skimmer flagged the station as calling CQ
 */
data class RttySpot(
    val call: String,
    val freqHz: Long,
    val source: SpotSource,
    val snrDb: Int? = null,
    val baud: Int? = null,
    val spotters: Set<String> = emptySet(),
    val lastHeardMs: Long = 0L,
    val isCq: Boolean = false,
) {
    val band: String? get() = RttySegments.bandOf(freqHz)
    fun ageSeconds(nowMs: Long): Long = ((nowMs - lastHeardMs) / 1000L).coerceAtLeast(0L)
    /** Frequency as the rail/sheet print it: "14.089.3". */
    val freqLabel: String get() = formatKHz(freqHz)
}

/** "14.089.3" style: MHz.kHz.hundreds-of-Hz. Pure — unit-tested. */
fun formatKHz(freqHz: Long): String {
    val mhz = freqHz / 1_000_000
    val khz = (freqHz / 1000) % 1000
    val hundreds = (freqHz % 1000) / 100
    return "%d.%03d.%d".format(mhz, khz, hundreds)
}

/** A sub-band where RTTY activity concentrates; the band rail shows one of these. */
data class RttySegment(val band: String, val loHz: Long, val hiHz: Long) {
    val spanHz: Long get() = hiHz - loHz
    fun contains(hz: Long): Boolean = hz in loHz..hiHz
    fun fractionOf(hz: Long): Float = ((hz - loHz).toDouble() / spanHz).toFloat()
}

/**
 * Conventional RTTY segments per band (IARU band plans / common practice).
 * [forDial] picks the segment the dial sits in, or a synthetic ±10 kHz window
 * around the dial when it is outside every known segment, so the rail always
 * has something sensible to draw.
 */
object RttySegments {
    val KNOWN: List<RttySegment> = listOf(
        RttySegment("160m", 1_800_000, 1_840_000),
        RttySegment("80m", 3_570_000, 3_600_000),
        RttySegment("40m", 7_025_000, 7_050_000),
        RttySegment("40m", 7_080_000, 7_125_000),
        RttySegment("30m", 10_130_000, 10_150_000),
        RttySegment("20m", 14_070_000, 14_099_000),
        RttySegment("17m", 18_095_000, 18_110_000),
        RttySegment("15m", 21_070_000, 21_110_000),
        RttySegment("12m", 24_910_000, 24_930_000),
        RttySegment("10m", 28_070_000, 28_150_000),
        RttySegment("6m", 50_300_000, 50_350_000),
    )

    private val BANDS: List<Triple<String, Long, Long>> = listOf(
        Triple("160m", 1_800_000, 2_000_000),
        Triple("80m", 3_500_000, 4_000_000),
        Triple("60m", 5_250_000, 5_450_000),
        Triple("40m", 7_000_000, 7_300_000),
        Triple("30m", 10_100_000, 10_150_000),
        Triple("20m", 14_000_000, 14_350_000),
        Triple("17m", 18_068_000, 18_168_000),
        Triple("15m", 21_000_000, 21_450_000),
        Triple("12m", 24_890_000, 24_990_000),
        Triple("10m", 28_000_000, 29_700_000),
        Triple("6m", 50_000_000, 54_000_000),
        Triple("2m", 144_000_000, 148_000_000),
    )

    /** Amateur band name for [hz], or null outside the bands we know. */
    fun bandOf(hz: Long): String? = BANDS.firstOrNull { hz >= it.second && hz <= it.third }?.first

    const val FALLBACK_HALF_SPAN_HZ = 10_000L

    fun forDial(dialHz: Long): RttySegment {
        KNOWN.firstOrNull { it.contains(dialHz) }?.let { return it }
        // Prefer a known segment on the same band whose edge is within 20 kHz;
        // otherwise a window around the dial.
        val band = bandOf(dialHz)
        KNOWN.filter { it.band == band }
            .minByOrNull { minOf(abs(it.loHz - dialHz), abs(it.hiHz - dialHz)) }
            ?.takeIf { minOf(abs(it.loHz - dialHz), abs(it.hiHz - dialHz)) <= 20_000 }
            ?.let { return it }
        return RttySegment(band ?: "?", dialHz - FALLBACK_HALF_SPAN_HZ, dialHz + FALLBACK_HALF_SPAN_HZ)
    }
}

/**
 * Pure tuning arithmetic for "tap a spot, land on it". Upper sideband: audio
 * offset = RF − dial, so to put a spotted mark tone at [markToneHz] we set
 * dial = RF − markToneHz.
 */
object SpotTuner {
    const val PASSBAND_LO_HZ = 300L
    const val PASSBAND_HI_HZ = 2_800L

    fun dialFor(spotHz: Long, markToneHz: Int): Long = spotHz - markToneHz

    /** Audio offset (Hz) of a spot relative to the current dial; negative when below the dial. */
    fun audioOffsetHz(spotHz: Long, dialHz: Long): Long = spotHz - dialHz

    /** True when the spot's mark tone falls in the usable audio passband at the current dial. */
    fun isInPassband(spotHz: Long, dialHz: Long): Boolean {
        val off = audioOffsetHz(spotHz, dialHz)
        return off >= PASSBAND_LO_HZ && off <= PASSBAND_HI_HZ
    }

    /** True when going to the spot would change amateur band (needs operator confirmation). */
    fun isCrossBand(spotHz: Long, dialHz: Long): Boolean {
        val a = RttySegments.bandOf(spotHz)
        val b = RttySegments.bandOf(dialHz)
        return a != b
    }

    /** What "go to this spot" has to do from the current dial. */
    sealed class Plan {
        /** Already in the passband: just move the decoder cursor to [audioHz]. */
        data class TuneOnly(val audioHz: Int) : Plan()
        /** Same band: set the dial to [dialHz] so the spot lands at the mark tone, then tune [audioHz]. */
        data class Qsy(val dialHz: Long, val audioHz: Int) : Plan()
        /** Different band: same as [Qsy] but the operator must confirm first (antenna/ATU). */
        data class CrossBand(val dialHz: Long, val audioHz: Int, val band: String) : Plan()
    }

    fun plan(spotHz: Long, dialHz: Long, markToneHz: Int): Plan {
        if (dialHz > 0 && isInPassband(spotHz, dialHz)) return Plan.TuneOnly(audioOffsetHz(spotHz, dialHz).toInt())
        val newDial = dialFor(spotHz, markToneHz)
        return if (dialHz > 0 && isCrossBand(spotHz, dialHz)) {
            Plan.CrossBand(newDial, markToneHz, RttySegments.bandOf(spotHz) ?: "?")
        } else {
            Plan.Qsy(newDial, markToneHz)
        }
    }
}

/** "14.084 MHz · 20m" for the band bar. Pure — unit-tested. */
fun formatDialLabel(dialHz: Long): String {
    if (dialHz <= 0) return "—"
    val mhz = dialHz / 1_000_000
    val khz = (dialHz / 1000) % 1000
    val band = RttySegments.bandOf(dialHz)
    return "%d.%03d MHz".format(mhz, khz) + (band?.let { " · $it" } ?: "")
}
