package radio.ks3ckc.rttyaf.rtty

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Shared desktop/Android fixtures; regenerate explicitly using this JVM main. */
internal object RttyGoldenVectors {
    data class Vector(val id: String, val config: RttyConfig, val text: String)

    fun parse(manifest: String): List<Vector> =
        manifest.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val p = line.split('\t')
                Vector(
                    p[0],
                    RttyConfig(
                        baudRate = p[1].toDouble(),
                        shiftHz = p[2].toInt(),
                        markHz = p[3].toDouble(),
                        stopBits = p[4].toDouble(),
                        reverse = p[5].toBooleanStrict(),
                        unshiftOnSpace = p[6].toBooleanStrict(),
                    ),
                    p[7].replace("\\r", "\r").replace("\\n", "\n"),
                )
            }.toList()

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.single())
        for (v in parse(File(dir, "vectors.tsv").readText())) {
            val samples = RttyEncoder(v.config).encodeShort(v.text)
            val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { buffer.putShort(it) }
            File(dir, "${v.id}.pcm").writeBytes(buffer.array())
            println("${v.id}: ${samples.size} samples, ${v.config.markToneHz}/${v.config.spaceToneHz} Hz")
        }
    }
}
