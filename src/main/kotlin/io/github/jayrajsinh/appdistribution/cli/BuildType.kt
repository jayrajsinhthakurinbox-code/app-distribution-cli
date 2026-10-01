package io.github.jayrajsinh.appdistribution.cli

/** Which Android build type to build and distribute. */
enum class BuildType(val id: String, val label: String) {

    RELEASE("release", "Release"),
    DEBUG("debug", "Debug");

    /** Gradle task suffix, e.g. assembleRelease. */
    val taskSuffix: String
        get() = label

    /**
     * Whether a variant name (e.g. "release", "freeDebug") or APK output
     * folder belongs to this build type.
     */
    fun matches(variantOrFolder: String): Boolean =
        variantOrFolder.endsWith(id, ignoreCase = true)

    companion object {
        fun parse(value: String?): BuildType? =
            entries.firstOrNull { it.id.equals(value?.trim(), ignoreCase = true) }
    }
}
