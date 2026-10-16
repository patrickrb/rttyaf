package radio.ks3ckc.rttyaf

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Guards the app's launcher/brand name across every translation.
 *
 * RTTYAF was forked from FT8AF and the translated `strings.xml` files were
 * copied along. A phone set to French, Russian or Chinese resolved
 * `app_name` from its locale file and showed the launcher as "FT8AF" — the
 * English default was fixed but the overrides weren't. Resource overrides are
 * invisible to the compiler, so parse the XML and assert every locale that
 * defines a brand string agrees with the default.
 *
 * Unit tests run with the working directory at the module root (rttyaf/app).
 */
class AppNameLocalizationTest {

    private val resDir = File("src/main/res")

    /** All `values*` string files, keyed by their qualifier ("" for the default). */
    private fun stringFiles(): Map<String, File> =
        resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }
            .orEmpty()
            .mapNotNull { dir ->
                val f = File(dir, "strings.xml")
                if (f.isFile) dir.name.removePrefix("values").removePrefix("-") to f else null
            }
            .toMap()

    /** Value of `<string name="[name]">` in [file], or null if the file doesn't define it. */
    private fun stringValue(file: File, name: String): String? {
        val re = Regex("""<string name="$name">(.*?)</string>""")
        return re.find(file.readText())?.groupValues?.get(1)
    }

    /**
     * Every string resource file in every locale, not just `strings.xml`: the
     * Compose UI reads `strings_compose.xml`, which is where "EXIT FT8AF?" hid
     * from the first version of this test and reached a tester.
     */
    private fun allStringFiles(): List<Pair<String, File>> =
        resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }
            .orEmpty()
            .flatMap { dir ->
                dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }.orEmpty()
                    .map { "${dir.name}/${it.name}" to it }
            }

    @Test
    fun noUserFacingStringInAnyLocaleSaysFt8af() {
        // The upstream author's bug-report address is the one deliberate holdout
        // until RTTYAF has its own contact; it is not shown as a brand.
        val allowed = setOf("bug_report_email")
        val re = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        for ((where, file) in allStringFiles()) {
            for (m in re.findAll(file.readText())) {
                val (name, value) = m.destructured
                if (name in allowed) continue
                assertWithMessage("$name in $where").that(value).doesNotContain("FT8AF")
            }
        }
    }

    @Test
    fun defaultLocaleDefinesTheRttyafName() {
        val default = stringFiles().getValue("")
        assertThat(stringValue(default, "app_name")).isEqualTo("RTTYAF")
    }

    @Test
    fun everyLocaleOverrideOfAppNameSaysRttyaf() {
        val files = stringFiles()
        assertThat(files.size).isGreaterThan(1) // sanity: translations are present
        for ((qualifier, file) in files) {
            val name = stringValue(file, "app_name") ?: continue
            assertWithMessage("app_name in values-$qualifier").that(name).isEqualTo("RTTYAF")
        }
    }

    @Test
    fun noLocaleStillBrandsAboutOrVersionAsFt8af() {
        // These surface in the About screen and the legacy welcome banner.
        for ((qualifier, file) in stringFiles()) {
            for (key in listOf("about_me", "logo_image", "version_info")) {
                val v = stringValue(file, key) ?: continue
                assertWithMessage("$key in values-$qualifier").that(v).doesNotContain("FT8AF")
            }
        }
    }
}
