package io.github.jayrajsinh.appdistribution.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApkLocatorTest {

    private val root: File = Files.createTempDirectory("appdist-project").toFile()
    private val project = AndroidProject(root)
    private val outputs = File(root, "app/build/outputs/apk")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun apk(path: String, modified: Long = System.currentTimeMillis()): File =
        File(outputs, path).apply {
            parentFile.mkdirs()
            writeText("apk")
            setLastModified(modified)
        }

    @Test
    fun `finds the single release apk`() {
        val release = apk("release/app-release.apk")
        apk("debug/app-debug.apk")

        assertEquals(ApkLocator.Result.Found(release), ApkLocator(project).locate(0))
    }

    @Test
    fun `finds custom output names`() {
        val release = apk("release/PassportPhoto-3.7.2-55.apk")

        assertEquals(ApkLocator.Result.Found(release), ApkLocator(project).locate(0))
    }

    @Test
    fun `reports every flavor apk`() {
        val free = apk("free/release/app-free-release.apk")
        val pro = apk("pro/release/app-pro-release.apk")

        val result = ApkLocator(project).locate(0)

        assertIs<ApkLocator.Result.Multiple>(result)
        assertEquals(listOf(free, pro), result.apks)
    }

    @Test
    fun `detects unsigned builds`() {
        apk("release/app-release-unsigned.apk")

        assertEquals(ApkLocator.Result.Unsigned, ApkLocator(project).locate(0))
    }

    @Test
    fun `ignores apks from earlier builds`() {
        val buildStart = System.currentTimeMillis()
        apk("release/app-release.apk", modified = buildStart - 60_000)

        assertEquals(ApkLocator.Result.NotFound, ApkLocator(project).locate(buildStart))
    }

    private fun metadata(dir: String, variant: String, vararg outputFiles: String) {
        val elements = outputFiles.joinToString(",") { """{"outputFile":"$it"}""" }
        File(outputs, "$dir/output-metadata.json").apply {
            parentFile.mkdirs()
            writeText("""{"variantName":"$variant","elements":[$elements]}""")
        }
    }

    @Test
    fun `up-to-date build still finds the apk via output metadata`() {
        val buildStart = System.currentTimeMillis()
        // Gradle skipped packaging, so the APK is older than the build
        val release = apk("release/app-release.apk", modified = buildStart - 600_000)
        metadata("release", "release", "app-release.apk")

        assertEquals(ApkLocator.Result.Found(release), ApkLocator(project).locate(buildStart))
    }

    @Test
    fun `metadata reflects signing change and ignores stale unsigned apk`() {
        apk("release/app-release-unsigned.apk")
        val signed = apk("release/app-release.apk")
        metadata("release", "release", "app-release.apk")

        assertEquals(ApkLocator.Result.Found(signed), ApkLocator(project).locate(0))
    }

    @Test
    fun `metadata detects unsigned output`() {
        apk("release/app-release-unsigned.apk")
        metadata("release", "release", "app-release-unsigned.apk")

        assertEquals(ApkLocator.Result.Unsigned, ApkLocator(project).locate(0))
    }

    @Test
    fun `metadata lists flavor apks and skips debug`() {
        val free = apk("free/release/app-free-release.apk")
        val pro = apk("pro/release/app-pro-release.apk")
        apk("free/debug/app-free-debug.apk")
        metadata("free/release", "freeRelease", "app-free-release.apk")
        metadata("pro/release", "proRelease", "app-pro-release.apk")
        metadata("free/debug", "freeDebug", "app-free-debug.apk")

        val result = ApkLocator(project).locate(0)

        assertIs<ApkLocator.Result.Multiple>(result)
        assertEquals(listOf(free, pro), result.apks)
    }
}
