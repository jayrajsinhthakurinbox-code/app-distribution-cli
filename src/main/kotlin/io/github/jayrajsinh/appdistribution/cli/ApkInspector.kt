package io.github.jayrajsinh.appdistribution.cli

import java.io.File
import java.util.Properties

/**
 * Reads the package name and version straight from a built APK using the
 * Android SDK's aapt2, so it works regardless of how the project declares
 * them (Groovy, Kotlin DSL, version catalogs, variables, flavors).
 */
class ApkInspector(
    private val project: AndroidProject
) {

    data class ApkInfo(
        val applicationId: String,
        val versionName: String,
        val versionCode: Int,
        val appName: String,
        /** Debug builds are marked debuggable; release builds normally aren't. */
        val debuggable: Boolean
    )

    fun inspect(apk: File): ApkInfo {

        val aapt2 = findAapt2()
            ?: error(
                "aapt2 not found. Install Android SDK Build-Tools " +
                    "(SDK Manager > SDK Tools) or set ANDROID_HOME."
            )

        val process = ProcessBuilder(
            aapt2.absolutePath,
            "dump",
            "badging",
            apk.absolutePath
        )
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream
            .bufferedReader()
            .readText()

        if (process.waitFor() != 0) {
            error("aapt2 could not read the APK:\n${output.take(500)}")
        }

        // package: name='com.x' versionCode='55' versionName='3.7.2' ...
        val packageLine = output
            .lineSequence()
            .firstOrNull { it.startsWith("package:") }
            ?: error("aapt2 output has no package line")

        fun attribute(name: String) =
            Regex("""\b$name='([^']*)'""")
                .find(packageLine)
                ?.groupValues
                ?.get(1)
                ?: error("APK is missing $name")

        // application-label:'My App'
        val appName = Regex("""^application-label:'(.*)'$""", RegexOption.MULTILINE)
            .find(output)
            ?.groupValues
            ?.get(1)
            .orEmpty()

        return ApkInfo(
            applicationId = attribute("name"),
            versionName = attribute("versionName"),
            versionCode = attribute("versionCode").toInt(),
            appName = appName,
            debuggable = output.lineSequence().any { it.trim() == "application-debuggable" }
        )
    }

    private fun findAapt2(): File? {

        val buildTools = sdkDirectory()
            ?.resolve("build-tools")
            ?.listFiles { file -> file.isDirectory }
            ?: return null

        // Newest build-tools first
        return buildTools
            .sortedWith { a, b -> compareVersions(b.name, a.name) }
            .map { File(it, "aapt2") }
            .firstOrNull { it.canExecute() }
    }

    private fun sdkDirectory(): File? {

        val localProperties = File(project.directory, "local.properties")

        val fromLocalProperties = if (localProperties.exists()) {
            Properties()
                .apply { localProperties.inputStream().use { load(it) } }
                .getProperty("sdk.dir")
        } else {
            null
        }

        return listOfNotNull(
            fromLocalProperties,
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            "${System.getProperty("user.home")}/Library/Android/sdk"
        )
            .map { File(it) }
            .firstOrNull { it.isDirectory }
    }

    /** Compares "34.0.0"-style names; non-numeric parts (rc) sort lower. */
    private fun compareVersions(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: -1 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: -1 }

        for (i in 0 until maxOf(x.size, y.size)) {
            val diff = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }

        return 0
    }
}
