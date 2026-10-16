package radio.ks3ckc.rttyaf

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.rttyaf.ui.components.Ft8Tab

/**
 * Unit test for [qsoPanelOverlaysContent], the rule that decides whether the
 * active QSO panel floats over the screen content or docks below it.
 *
 * The Waterfall tab must overlay the panel: docking shrinks the content area,
 * which resizes the waterfall AndroidView and wipes its accumulated history.
 * Every other (list-style) tab docks it.
 */
class QsoPanelLayoutTest {

    @Test
    fun waterfallTab_floatsThePanelOverContent() {
        assertThat(qsoPanelOverlaysContent(Ft8Tab.WATERFALL)).isTrue()
    }

    @Test
    fun allOtherTabs_dockThePanel() {
        Ft8Tab.values()
            .filter { it != Ft8Tab.WATERFALL }
            .forEach { tab ->
                assertThat(qsoPanelOverlaysContent(tab)).isFalse()
            }
    }
}
