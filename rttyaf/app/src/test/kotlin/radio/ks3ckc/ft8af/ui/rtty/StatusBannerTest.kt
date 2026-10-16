package radio.ks3ckc.ft8af.ui.rtty

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The status banner is how the backend's `ToastMessage.show` calls (a LiveData,
 * not an Android toast) finally reach the Compose UI. Without it a refused TX
 * tap was indistinguishable from a dead button.
 */
class StatusBannerTest {

    @Test
    fun hidesOnNullOrBlank() {
        assertThat(statusBannerText(null)).isNull()
        assertThat(statusBannerText("")).isNull()
        assertThat(statusBannerText(" \n ")).isNull() // ToastMessage's empty join after expiry
    }

    @Test
    fun showsTrimmedMessage() {
        assertThat(statusBannerText("RTTY TX is not available over network or CAT audio\n"))
            .isEqualTo("RTTY TX is not available over network or CAT audio")
    }
}
