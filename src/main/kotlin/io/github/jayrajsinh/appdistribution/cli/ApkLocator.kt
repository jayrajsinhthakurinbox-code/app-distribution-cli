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
     * APKs of [buildType]. [buildStartedAt] (epoch millis) is only used when
     * there's no output metadata (very old Android Gradle Plugin versions).
     */
    fun locate(buildStartedAt: Long, buildType: BuildType = BuildType.RELEASE): Result {

        val built = fromOutputMetadata(buildType)
            ?: fromTimestamps(buildStartedAt, buildType)

        val (unsigned, signed) = built.partition {
            it.name.contains("unsigned", ignoreCase = true)
        }

        return when {
            signed.size == 1 -> Result.Found(signed.single())
            signed.size > 1 -> Result.Multiple(signed.sortedBy { it.path })
            // Debug builds are always signed with the debug key
            unsigned.isNotEmpty() && buildType == BuildType.RELEASE -> Result.Unsigned
            unsigned.size == 1 -> Result.Found(unsigned.single())
            unsigned.size > 1 -> Result.Multiple(unsigned.sortedBy { it.path })
            else -> Result.NotFound
        }
    }

    /** APKs of [buildType] listed in output-metadata.json, or null if there is none. */
    private fun fromOutputMetadata(buildType: BuildType): List<File>? {

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

                // e.g. "release", "freeRelease", "debug"
                val variant = json.optString("variantName")

                if (!buildType.matches(variant)) {
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

    private fun fromTimestamps(buildStartedAt: Long, buildType: BuildType): List<File> =
        outputs
            .walkTopDown()
            .filter { it.isFile && it.extension == "apk" }
            // .../apk/<flavor?>/<buildType>/*.apk
            .filter { buildType.matches(it.parentFile.name) }
            // Allow for coarse file timestamps
            .filter { it.lastModified() >= buildStartedAt - 2_000 }
            .toList()
}
