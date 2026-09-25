package io.github.jayrajsinh.appdistribution.cli

import org.json.JSONObject
import java.io.File

/**
 * Finds the release APK(s) Gradle produced, instead of assuming
 * app/build/outputs/apk/release/app-release.apk. Handles product flavors and
 * custom output names, and detects unsigned builds.
 *
 * Uses the output-metadata.json the Android Gradle Plugin writes next to
 * each variant's APK. It stays accurate when a build is up to date and the
 * APK isn't rewritten, which file timestamps don't.
 */
class ApkLocator(
    private val project: AndroidProject
) {

    sealed interface Result {
        data class Found(val apk: File) : Result
        data class Multiple(val apks: List<File>) : Result
        data object Unsigned : Result
        data object NotFound : Result
    }

    private val outputs: File
        get() = File(project.appDirectory, "build/outputs/apk")

    /**
     * [buildStartedAt] (epoch millis) is only used when there's no
     * output metadata (very old Android Gradle Plugin versions).
     */
    fun locate(buildStartedAt: Long): Result {

        val built = fromOutputMetadata()
            ?: fromTimestamps(buildStartedAt)

        val (unsigned, signed) = built.partition {
            it.name.contains("unsigned", ignoreCase = true)
        }

        return when {
            signed.size == 1 -> Result.Found(signed.single())
            signed.size > 1 -> Result.Multiple(signed.sortedBy { it.path })
            unsigned.isNotEmpty() -> Result.Unsigned
            else -> Result.NotFound
        }
    }

    /** Release APKs listed in output-metadata.json, or null if there is none. */
    private fun fromOutputMetadata(): List<File>? {

        val metadataFiles = outputs
            .walkTopDown()
            .filter { it.isFile && it.name == "output-metadata.json" }
            .toList()

        if (metadataFiles.isEmpty()) {
            return null
        }

        return metadataFiles.flatMap { metadata ->
            try {
                val json = JSONObject(metadata.readText())

                // e.g. "release", "freeRelease"
                val variant = json.optString("variantName")

                if (!variant.endsWith("release", ignoreCase = true)) {
                    return@flatMap emptyList()
                }

                val elements = json.optJSONArray("elements")
                    ?: return@flatMap emptyList()

                (0 until elements.length())
                    .mapNotNull { elements.optJSONObject(it)?.optString("outputFile") }
                    .filter { it.isNotBlank() }
                    .map { File(metadata.parentFile, it) }
                    .filter { it.isFile }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    private fun fromTimestamps(buildStartedAt: Long): List<File> =
        outputs
            .walkTopDown()
            .filter { it.isFile && it.extension == "apk" }
            // Only release variants: .../apk/<flavor?>/release/*.apk
            .filter { it.parentFile.name.endsWith("release", ignoreCase = true) }
            // Allow for coarse file timestamps
            .filter { it.lastModified() >= buildStartedAt - 2_000 }
            .toList()
}
