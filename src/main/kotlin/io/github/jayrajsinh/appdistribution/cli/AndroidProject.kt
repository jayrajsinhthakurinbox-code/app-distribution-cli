package io.github.jayrajsinh.appdistribution.cli

import java.io.File

/**
 * An Android Gradle project and its application module.
 *
 * The application module is the one applying the Android application plugin
 * (`com.android.application`, or a version catalog alias for it). `app` is
 * preferred when several qualify, and assumed when none can be detected.
 */
class AndroidProject(
    val directory: File
) {

    val gradleWrapper: File
        get() = File(directory, "gradlew")

    val appDirectory: File by lazy {
        detectApplicationModule() ?: File(directory, "app")
    }

    /** Gradle path of the application module, e.g. ":app". */
    val appModulePath: String
        get() = ":" + appDirectory.relativeTo(directory).path.replace(File.separatorChar, ':')

    val appGradleFile: File?
        get() = buildFileIn(appDirectory)

    /** Written by `appdist release`, read by `appdist distribute` and the IDE plugin. */
    val releaseManifest: File
        get() = File(appDirectory, "build/app-distribution/release.json")

    fun isAndroidProject(): Boolean {
        return gradleWrapper.exists() && appGradleFile != null
    }

    private fun detectApplicationModule(): File? {
        val candidates = directory
            .walkTopDown()
            .maxDepth(3)
            .onEnter { it == directory || (it.name !in IGNORED_DIRECTORIES && !it.name.startsWith(".")) }
            .filter { it.isDirectory && it != directory }
            .filter { dir ->
                buildFileIn(dir)
                    ?.readText()
                    ?.let { script -> APPLICATION_PLUGIN_PATTERNS.any { it.containsMatchIn(script) } }
                    ?: false
            }
            .toList()

        return candidates.firstOrNull { it.name == "app" }
            ?: candidates.minByOrNull { it.relativeTo(directory).path.length }
    }

    private fun buildFileIn(dir: File): File? =
        listOf("build.gradle.kts", "build.gradle")
            .map { File(dir, it) }
            .firstOrNull { it.isFile }

    private companion object {
        val IGNORED_DIRECTORIES = setOf("build", "gradle", "buildSrc", "node_modules")

        val APPLICATION_PLUGIN_PATTERNS = listOf(
            // id("com.android.application"), apply plugin: 'com.android.application'
            Regex("""com\.android\.application"""),
            // alias(libs.plugins.android.application)
            Regex("""plugins\.android\.application\b""")
        )
    }
}
