package radio.ks3ckc.rttyaf.rtty

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RttyGoldenTest {
    @Test fun sharedDesktopGoldensMatchAndroidEncoderAndStreamingDecoder() {
        val loader = javaClass.classLoader!!
        val manifest = loader.getResourceAsStream("rtty/vectors.tsv")!!.bufferedReader().readText()
        for (v in RttyGoldenVectors.parse(manifest)) {
            val bytes = loader.getResourceAsStream("rtty/${v.id}.pcm")!!.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val golden = ShortArray(bytes.size / 2) { buffer.short }
            assertThat(RttyEncoder(v.config).encodeShort(v.text).toList()).isEqualTo(golden.toList())
            val decoder = RttyDecoder(v.config)
            val decoded = StringBuilder()
            // Deliberately not a bit, character or device-buffer multiple.
            for (chunk in golden.toList().chunked(137)) {
                decoded.append(decoder.process(chunk.toShortArray()))
            }
            assertThat(decoded.toString()).isEqualTo(v.text.uppercase())
        }
    }
}
