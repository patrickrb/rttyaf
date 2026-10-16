package radio.ks3ckc.rttyaf.rtty

import android.content.Context

/**
 * The operator's persisted RTTY mode settings — what the Settings → RTTY Mode
 * screen edits and what the modem is configured from on every cold start.
 *
 * Defaults follow amateur HF practice (see rttycontesting.com, *Introduction*):
 * 45.45 baud, 170 Hz shift, "high tones" (2125 Hz mark / 2295 Hz space), the
 * rig in **LSB** for AFSK, USOS on. AFC is *off* by default so the tone pair
 * stays where the operator put it unless they opt in to tracking; NET is on so
 * that, when AFC/tap-to-tune does move the pair, TX follows RX.
 *
 * This is a plain value type with pure (de)serialisation helpers so the whole
 * thing is unit-testable without Android; [SharedPrefsRttyStore] is the thin
 * on-device wrapper.
 *
 * @param baudRate       symbol rate (45.45 / 50 / 75)
 * @param shiftHz        mark→space separation (170 standard; 200 for some TNCs)
 * @param markHz         nominal MARK audio tone (2125 "high", 1275 "low")
 * @param usbSideband    true when the rig runs USB for AFSK. Standard practice is
 *                       LSB (mark is the *higher* RF frequency); in USB the audio
 *                       tones arrive swapped, which maps to [RttyConfig.reverse].
 * @param unshiftOnSpace USOS: a SPACE returns the shift to LETTERS
 * @param squelch        gate the decoder on tone energy
 * @param afc            start with automatic frequency control engaged
 * @param net            TX follows the RX tuning (vs. the nominal tones)
 */
data class RttyModePrefs(
    val baudRate: Double = 45.45,
    val shiftHz: Int = 170,
    val markHz: Double = 2125.0,
    val usbSideband: Boolean = false,
    val unshiftOnSpace: Boolean = true,
    val squelch: Boolean = true,
    val afc: Boolean = false,
    val net: Boolean = true,
) {
    /** Nominal SPACE tone. */
    val spaceHz: Double get() = markHz + shiftHz

    /**
     * The same settings with the mark tone clamped so that *both* tones fit the
     * passband for the chosen shift (see [maxMarkHz]). Applied on load and on
     * every edit so a wide shift can never push the space tone past the
     * waterfall / decoder range, which would otherwise make the next tune or
     * nudge silently drag the pair down.
     */
    fun normalized(): RttyModePrefs {
        val clamped = markHz.coerceIn(MIN_MARK_HZ.toDouble(), maxMarkHz(shiftHz))
        return if (clamped == markHz) this else copy(markHz = clamped)
    }

    /** Build the modem config these settings describe (12 kHz RX rate). */
    fun toConfig(sampleRate: Int = DEFAULT_SAMPLE_RATE): RttyConfig = RttyConfig(
        baudRate = baudRate,
        shiftHz = shiftHz,
        markHz = markHz,
        sampleRate = sampleRate,
        reverse = usbSideband,
        unshiftOnSpace = unshiftOnSpace,
        squelch = squelch,
    )

    /** Flatten to string key/values for any key-value store. */
    fun toStore(): Map<String, String> = mapOf(
        KEY_BAUD to baudRate.toString(),
        KEY_SHIFT to shiftHz.toString(),
        KEY_MARK to markHz.toString(),
        KEY_USB to usbSideband.toString(),
        KEY_USOS to unshiftOnSpace.toString(),
        KEY_SQUELCH to squelch.toString(),
        KEY_AFC to afc.toString(),
        KEY_NET to net.toString(),
    )

    companion object {
        const val DEFAULT_SAMPLE_RATE = 12000

        const val KEY_BAUD = "rtty_baud"
        const val KEY_SHIFT = "rtty_shift_hz"
        const val KEY_MARK = "rtty_mark_hz"
        const val KEY_USB = "rtty_usb_sideband"
        const val KEY_USOS = "rtty_usos"
        const val KEY_SQUELCH = "rtty_squelch"
        const val KEY_AFC = "rtty_afc"
        const val KEY_NET = "rtty_net"

        /** Standard HF baud rates (45.45 is the amateur norm; 75 is used on HF too). */
        val BAUD_OPTIONS: List<Double> = listOf(45.45, 50.0, 75.0)

        /** Common shifts: 170 Hz is the HF standard; 200 Hz for KAM/PK-232-style TNCs; wide shifts for VHF/legacy. */
        val SHIFT_OPTIONS: List<Int> = listOf(170, 200, 425, 850)

        /** Named mark-tone presets. */
        val TONE_PRESETS: List<Double> = listOf(2125.0, 1275.0)

        /** Lowest / highest mark tone the custom entry accepts (both tones must stay in the passband). */
        const val MIN_MARK_HZ = 500
        const val MAX_MARK_HZ = 2800

        /** Highest mark tone that keeps `mark + shift` inside the passband ([RttyEngine.DISPLAY_MAX_HZ]). */
        fun maxMarkHz(shiftHz: Int): Double =
            minOf(MAX_MARK_HZ.toDouble(), RttyEngine.DISPLAY_MAX_HZ - shiftHz).coerceAtLeast(MIN_MARK_HZ.toDouble())

        /**
         * Rebuild from a key-value store, falling back to the default for any
         * missing or unparseable value so a corrupt/partial store never crashes
         * startup or yields an invalid [RttyConfig]. Values outside the
         * supported ranges are clamped the same way.
         */
        fun fromStore(get: (String) -> String?): RttyModePrefs {
            val d = RttyModePrefs()
            val baud = get(KEY_BAUD)?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: d.baudRate
            val shift = get(KEY_SHIFT)?.toIntOrNull()?.takeIf { it > 0 } ?: d.shiftHz
            val mark = get(KEY_MARK)?.toDoubleOrNull() ?: d.markHz
            return RttyModePrefs(
                baudRate = baud,
                shiftHz = shift,
                markHz = mark,
                usbSideband = get(KEY_USB)?.toBooleanStrictOrNull() ?: d.usbSideband,
                unshiftOnSpace = get(KEY_USOS)?.toBooleanStrictOrNull() ?: d.unshiftOnSpace,
                squelch = get(KEY_SQUELCH)?.toBooleanStrictOrNull() ?: d.squelch,
                afc = get(KEY_AFC)?.toBooleanStrictOrNull() ?: d.afc,
                net = get(KEY_NET)?.toBooleanStrictOrNull() ?: d.net,
            ).normalized()
        }
    }
}

/** Where [RttyModePrefs] live. One implementation per platform; in-memory for tests/previews. */
interface RttyPrefsStore {
    fun load(): RttyModePrefs
    fun save(prefs: RttyModePrefs)
}

/** Non-persistent store for unit tests and Compose previews. */
class InMemoryRttyPrefsStore(initial: RttyModePrefs = RttyModePrefs()) : RttyPrefsStore {
    var current: RttyModePrefs = initial
        private set
    var saveCount: Int = 0
        private set

    override fun load(): RttyModePrefs = current
    override fun save(prefs: RttyModePrefs) {
        current = prefs
        saveCount++
    }
}

/**
 * On-device store backed by a small SharedPreferences file. Kept separate from
 * the app's SQLite config (DatabaseOpr) so it can be read synchronously before
 * `setContent` and the modem starts with the right tones on the first chunk.
 */
class SharedPrefsRttyStore(context: Context) : RttyPrefsStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): RttyModePrefs = RttyModePrefs.fromStore { key -> prefs.getString(key, null) }

    override fun save(prefs: RttyModePrefs) {
        val editor = this.prefs.edit()
        for ((k, v) in prefs.toStore()) editor.putString(k, v)
        editor.apply()
    }

    private companion object {
        const val PREFS_NAME = "rttyaf_mode"
    }
}
