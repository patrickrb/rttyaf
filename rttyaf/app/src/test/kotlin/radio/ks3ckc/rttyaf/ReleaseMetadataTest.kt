package radio.ks3ckc.rttyaf

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Release plumbing that lives outside the code but breaks a release when it
 * drifts: the base semver is written in two places (Gradle's local fallback and
 * the workflow's fallback for internal builds), and Play rejects a "What's new"
 * text over 500 characters. Unit tests run from `rttyaf/app`.
 */
class ReleaseMetadataTest {

    private val repoRoot = File("../..")

    private fun gradleFallbackVersion(): String {
        val gradle = File("build.gradle").readText()
        return Regex("""versionName ciVersionName \?: '([0-9.]+)'""").find(gradle)!!.groupValues[1]
    }

    private fun workflowFallbackVersion(): String {
        val wf = File(repoRoot, ".github/workflows/android.yml").readText()
        return Regex("""base_ver="\$\{base_ver:-([0-9.]+)\}"""").find(wf)!!.groupValues[1]
    }

    @Test
    fun baseVersionIsSemverAndAgreesInGradleAndWorkflow() {
        val gradle = gradleFallbackVersion()
        assertThat(gradle).matches("""\d+\.\d+\.\d+""")
        assertThat(workflowFallbackVersion()).isEqualTo(gradle)
    }

    @Test
    fun changelogHasAnEntryForTheCurrentVersion() {
        val changelog = File(repoRoot, "CHANGELOG.md").readText()
        assertThat(changelog).contains("## ${gradleFallbackVersion()} ")
    }

    @Test
    fun playWhatsNewTextsExistAndFitPlaysLimit() {
        val files = File(repoRoot, "distribution/whatsnew").listFiles().orEmpty()
            .filter { it.name.startsWith("whatsnew-") }
        assertThat(files.map { it.name }).contains("whatsnew-en-US")
        for (f in files) {
            val text = f.readText().trimEnd()
            assertWithMessage(f.name).that(text.codePointCount(0, text.length)).isAtMost(500)
            assertWithMessage(f.name).that(text).contains(gradleFallbackVersion())
        }
    }
}
