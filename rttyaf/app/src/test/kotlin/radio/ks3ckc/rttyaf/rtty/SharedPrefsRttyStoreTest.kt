package radio.ks3ckc.rttyaf.rtty

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The on-device store actually writes and re-reads SharedPreferences. */
@RunWith(RobolectricTestRunner::class)
class SharedPrefsRttyStoreTest {

    @Test
    fun freshInstall_loadsDefaults() {
        val store = SharedPrefsRttyStore(ApplicationProvider.getApplicationContext())
        assertThat(store.load()).isEqualTo(RttyModePrefs())
    }

    @Test
    fun save_thenLoadFromANewInstance_roundTrips() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val p = RttyModePrefs(baudRate = 75.0, shiftHz = 200, markHz = 1275.0, usbSideband = true, afc = true, net = false)
        SharedPrefsRttyStore(ctx).save(p)
        assertThat(SharedPrefsRttyStore(ctx).load()).isEqualTo(p)
    }
}
