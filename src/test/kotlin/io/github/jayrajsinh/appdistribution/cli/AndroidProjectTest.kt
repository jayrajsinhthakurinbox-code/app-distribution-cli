package io.github.jayrajsinh.appdistribution.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidProjectTest {

    private val root: File = Files.createTempDirectory("appdist-module").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun module(path: String, buildScript: String, kts: Boolean = true) {
        File(root, "$path/${if (kts) "build.gradle.kts" else "build.gradle"}").apply {
            parentFile.mkdirs()
            writeText(buildScript)
        }
    }

    @Test
    fun `detects a module not named app`() {
        module("core", """plugins { id("com.android.library") }""")
        module("mobile", """plugins { id("com.android.application") }""")

        val project = AndroidProject(root)

        assertEquals("mobile", project.appDirectory.name)
        assertEquals(":mobile", project.appModulePath)
    }

    @Test
    fun `detects version catalog alias and groovy apply`() {
        module("phone", """plugins { alias(libs.plugins.android.application) }""")
        assertEquals("phone", AndroidProject(root).appDirectory.name)

        root.deleteRecursively()
        module("legacy", "apply plugin: 'com.android.application'", kts = false)
        assertEquals("legacy", AndroidProject(root).appDirectory.name)
    }

    @Test
    fun `prefers app when several application modules exist`() {
        module("wear", """plugins { id("com.android.application") }""")
        module("app", """plugins { id("com.android.application") }""")

        assertEquals("app", AndroidProject(root).appDirectory.name)
    }

    @Test
    fun `nested module path`() {
        module("apps/phone", """plugins { id("com.android.application") }""")

        assertEquals(":apps:phone", AndroidProject(root).appModulePath)
    }

    @Test
    fun `falls back to app and keeps manifest in build dir`() {
        val project = AndroidProject(root)

        assertEquals(File(root, "app"), project.appDirectory)
        assertEquals(
            File(root, "app/build/app-distribution/release.json"),
            project.releaseManifest
        )
    }
}
