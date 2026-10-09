package radio.ks3ckc.ft8af.rtty.spots

/**
 * Parses Reverse Beacon Network telnet spot lines (CW/RTTY feed, port 7000).
 *
 * A line looks like:
 * ```
 * DX de W3LPL-#:   14083.2  K1AF           RTTY  24 dB  45 BPS  CQ      1842Z
 * ```
 * Only RTTY lines yield a spot; CW and anything unparsable return null.
 * Pure — unit-tested.
 */
object RbnLineParser {
    private val HEAD = Regex("""^DX de\s+([A-Za-z0-9/\-]+?)(?:-#)?:\s+(\d+(?:\.\d+)?)\s+([A-Za-z0-9/]+)\s+(.*?)\s*(\d{4})Z\s*$""")
    private val DB = Regex("""(-?\d+)\s*dB""", RegexOption.IGNORE_CASE)
    private val BPS = Regex("""(\d+)\s*BPS""", RegexOption.IGNORE_CASE)

    fun parse(line: String, nowMs: Long): RttySpot? {
        // A spot can arrive glued to the tail of the un-terminated login prompt
        // ("…your call: DX de …"), so start parsing at the "DX de" marker.
        val start = line.indexOf("DX de")
        if (start < 0) return null
        val m = HEAD.find(line.substring(start).trim()) ?: return null
        val spotter = m.groupValues[1].uppercase()
        val khz = m.groupValues[2].toDoubleOrNull() ?: return null
        val call = m.groupValues[3].uppercase()
        val rest = m.groupValues[4]
        val tokens = rest.trim().split(Regex("\\s+"))
        if (tokens.isEmpty() || !tokens[0].equals("RTTY", ignoreCase = true)) return null
        val snr = DB.find(rest)?.groupValues?.get(1)?.toIntOrNull()
        val baud = BPS.find(rest)?.groupValues?.get(1)?.toIntOrNull()
        val isCq = tokens.any { it.equals("CQ", ignoreCase = true) }
        return RttySpot(
            call = call,
            freqHz = Math.round(khz * 1000.0),
            source = SpotSource.RBN,
            snrDb = snr,
            baud = baud,
            spotters = setOf(spotter),
            lastHeardMs = nowMs,
            isCq = isCq,
        )
    }
}

/**
 * Turns a telnet byte stream into lines, and notices the un-terminated login
 * prompt (`Please enter your call:`) that never arrives with a newline.
 * Feed it text as it comes; it returns completed lines and reports whether a
 * login prompt is currently pending. Pure — unit-tested.
 */
class RbnLineAssembler {
    private val buffer = StringBuilder()

    /** True once a callsign prompt has been seen and not yet answered. */
    var loginPromptPending: Boolean = false
        private set

    fun feed(text: String): List<String> {
        val out = ArrayList<String>()
        for (ch in text) {
            when (ch) {
                '\n' -> {
                    out.add(buffer.toString().trimEnd('\r'))
                    buffer.setLength(0)
                }
                else -> buffer.append(ch)
            }
        }
        val tail = buffer.toString().trimEnd()
        if (tail.isNotEmpty() && isLoginPrompt(tail)) loginPromptPending = true
        // The prompt can also be swallowed into a completed line when the server
        // sends data right behind it; still answer it.
        if (out.any { containsLoginPrompt(it) }) loginPromptPending = true
        return out
    }

    /** Call after answering the prompt. */
    fun loginSent() {
        loginPromptPending = false
        buffer.setLength(0)
    }

    companion object {
        fun isLoginPrompt(text: String): Boolean {
            val t = text.lowercase()
            return t.endsWith("call:") || t.endsWith("callsign:") || t.endsWith("login:")
        }

        fun containsLoginPrompt(line: String): Boolean {
            val t = line.lowercase()
            return t.contains("enter your call") || t.contains("your callsign:") || t.startsWith("login:")
        }
    }
}
