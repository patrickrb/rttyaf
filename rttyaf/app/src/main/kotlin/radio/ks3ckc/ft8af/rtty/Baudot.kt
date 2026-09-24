package radio.ks3ckc.ft8af.rtty

/**
 * Baudot / ITA2 (US-TTY variant) 5-bit character tables.
 *
 * RTTY sends 5-bit codes. Because five bits can only express 32 values, the
 * alphabet is split across two "shift" states selected by two reserved codes:
 *
 *  - [LTRS] (0x1F, `11111`) selects the LETTERS set (A-Z + controls).
 *  - [FIGS] (0x1B, `11011`) selects the FIGURES set (digits, punctuation).
 *
 * The tables below are the standard **US-TTY** (a.k.a. "American") figures
 * layout used on the amateur bands, not the ITU/international one — e.g. code
 * 0x05 in FIGS is BELL and 0x09 is `$`. A handful of codes (SPACE, CR, LF,
 * NUL) mean the same thing in both shifts and so are shift-neutral.
 */
internal object Baudot {

    /** Shift-set of a printable code. */
    enum class Shift { LETTERS, FIGURES }

    /** The LTRS shift code (`11111`) — switches the receiver to LETTERS. */
    const val LTRS = 0x1F

    /** The FIGS shift code (`11011`) — switches the receiver to FIGURES. */
    const val FIGS = 0x1B

    /** Shift-neutral control/space codes that print identically in both sets. */
    const val NUL = 0x00
    const val SPACE = 0x04
    const val CR = 0x08
    const val LF = 0x02

    private const val BELL = '\u0007'

    /**
     * LETTERS set indexed by 5-bit code. `null` = no printable output (NUL and
     * the two shift codes are handled by the decoder before this lookup).
     */
    val LETTERS_TABLE: Array<Char?> = arrayOfNulls<Char?>(32).also {
        it[0x00] = null            // NUL
        it[0x01] = 'E'
        it[0x02] = '\n'            // LF
        it[0x03] = 'A'
        it[0x04] = ' '             // SPACE
        it[0x05] = 'S'
        it[0x06] = 'I'
        it[0x07] = 'U'
        it[0x08] = '\r'            // CR
        it[0x09] = 'D'
        it[0x0A] = 'R'
        it[0x0B] = 'J'
        it[0x0C] = 'N'
        it[0x0D] = 'F'
        it[0x0E] = 'C'
        it[0x0F] = 'K'
        it[0x10] = 'T'
        it[0x11] = 'Z'
        it[0x12] = 'L'
        it[0x13] = 'W'
        it[0x14] = 'H'
        it[0x15] = 'Y'
        it[0x16] = 'P'
        it[0x17] = 'Q'
        it[0x18] = 'O'
        it[0x19] = 'B'
        it[0x1A] = 'G'
        it[0x1B] = null            // FIGS shift
        it[0x1C] = 'M'
        it[0x1D] = 'X'
        it[0x1E] = 'V'
        it[0x1F] = null            // LTRS shift
    }

    /** FIGURES set (US-TTY) indexed by 5-bit code; `null` as in [LETTERS_TABLE]. */
    val FIGURES_TABLE: Array<Char?> = arrayOfNulls<Char?>(32).also {
        it[0x00] = null            // NUL
        it[0x01] = '3'
        it[0x02] = '\n'            // LF
        it[0x03] = '-'
        it[0x04] = ' '             // SPACE
        it[0x05] = BELL
        it[0x06] = '8'
        it[0x07] = '7'
        it[0x08] = '\r'            // CR
        it[0x09] = '$'
        it[0x0A] = '4'
        it[0x0B] = '\''
        it[0x0C] = ','
        it[0x0D] = '!'
        it[0x0E] = ':'
        it[0x0F] = '('
        it[0x10] = '5'
        it[0x11] = '"'
        it[0x12] = ')'
        it[0x13] = '2'
        it[0x14] = '#'
        it[0x15] = '6'
        it[0x16] = '0'
        it[0x17] = '1'
        it[0x18] = '9'
        it[0x19] = '?'
        it[0x1A] = '&'
        it[0x1B] = null            // FIGS shift
        it[0x1C] = '.'
        it[0x1D] = '/'
        it[0x1E] = ';'
        it[0x1F] = null            // LTRS shift
    }

    /**
     * A single character's 5-bit encoding.
     *
     * @param code  the 5-bit ITA2 value (0..31)
     * @param shift the shift set the code must be sent in, or `null` for the
     *              shift-neutral codes (SPACE, CR, LF) that need no shift change
     */
    data class EncodeEntry(val code: Int, val shift: Shift?)

    // char -> encoding. Built from the decode tables so the two directions can
    // never disagree. Neutral codes (space/CR/LF) are inserted last with a null
    // shift so a lookup for ' ' never forces a LETTERS/FIGS switch.
    private val encodeMap: Map<Char, EncodeEntry> = buildMap {
        for (code in 0 until 32) {
            LETTERS_TABLE[code]?.let { ch ->
                if (ch != ' ' && ch != '\r' && ch != '\n') {
                    put(ch, EncodeEntry(code, Shift.LETTERS))
                }
            }
        }
        for (code in 0 until 32) {
            FIGURES_TABLE[code]?.let { ch ->
                if (ch != ' ' && ch != '\r' && ch != '\n' && !containsKey(ch)) {
                    put(ch, EncodeEntry(code, Shift.FIGURES))
                }
            }
        }
        put(' ', EncodeEntry(SPACE, null))
        put('\r', EncodeEntry(CR, null))
        put('\n', EncodeEntry(LF, null))
    }

    /**
     * Encode a single character (letters are upper-cased first). Returns `null`
     * for characters with no ITA2 representation, which the caller should skip.
     */
    fun encode(ch: Char): EncodeEntry? = encodeMap[ch.uppercaseChar()]

    /**
     * Decode a printable 5-bit [code] in the given shift. Returns `null` for
     * NUL and for the shift codes themselves (the decoder acts on those before
     * calling here).
     */
    fun decode(code: Int, figures: Boolean): Char? =
        if (figures) FIGURES_TABLE[code and 0x1F] else LETTERS_TABLE[code and 0x1F]
}
