package io.github.jayrajsinh.appdistribution.cli

data class ReleaseMetadata(
    val projectPath: String,
    val apkPath: String,
    val applicationId: String,
    val versionName: String,
    val versionCode: Int,
    /** App label from the APK, e.g. "My App"; empty if unknown. */
    val appName: String = "",
    /** "release" or "debug", read from the APK. */
    val buildType: String = BuildType.RELEASE.id
)