package radio.ks3ckc.ft8af.rtty

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/**
 * Minimal iterative radix-2 FFT used only to draw the Operate-screen waterfall.
 * Not part of the demodulator (which uses tuned I/Q tone filters, not an FFT) —
 * this is purely a spectral display helper, so a plain power-of-two transform
 * with a Hann window is plenty.
 *
 * @param size FFT length, must be a power of two.
 */
class SimpleFft(private val size: Int) {
    init {
        require(size > 0 && (size and (size - 1)) == 0) { "size must be a power of two, was $size" }
    }

    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val hann = DoubleArray(size) { 0.5 - 0.5 * cos(2.0 * PI * it / (size - 1)) }

    /**
     * Windowed magnitude spectrum of [samples] from DC up to [maxHz], each bin
     * log-compressed and normalised to 0..1 for display. Fewer than [size]
     * samples are zero-padded; more are truncated to the newest [size].
     */
    fun magnitudes(samples: FloatArray, sampleRate: Int, maxHz: Double): FloatArray {
        val offset = if (samples.size > size) samples.size - size else 0
        for (i in 0 until size) {
            val s = if (offset + i < samples.size) samples[offset + i].toDouble() else 0.0
            re[i] = s * hann[i]
            im[i] = 0.0
        }
        transform(re, im)
        val maxBin = min(size / 2, ((maxHz * size) / sampleRate).toInt().coerceAtLeast(1))
        val out = FloatArray(maxBin)
        var mx = 1e-6f
        for (i in 0 until maxBin) {
            val v = ln(1.0 + hypot(re[i], im[i])).toFloat()
            out[i] = v
            if (v > mx) mx = v
        }
        for (i in out.indices) out[i] = (out[i] / mx).coerceIn(0f, 1f)
        return out
    }

    private fun transform(re: DoubleArray, im: DoubleArray) {
        val n = size
        // bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        // Danielson-Lanczos
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang); val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0; var curIm = 0.0
                for (k in 0 until len / 2) {
                    val aRe = re[i + k]; val aIm = im[i + k]
                    val bRe = re[i + k + len / 2]; val bIm = im[i + k + len / 2]
                    val tRe = bRe * curRe - bIm * curIm
                    val tIm = bRe * curIm + bIm * curRe
                    re[i + k] = aRe + tRe; im[i + k] = aIm + tIm
                    re[i + k + len / 2] = aRe - tRe; im[i + k + len / 2] = aIm - tIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
