package io.github.jayrajsinh.appdistribution.cli

import java.io.File

/**
 * appdist release [--apk <path>]
 *
 * Builds the release APK with Gradle, finds the APK that was actually
 * produced, reads its package and version from the APK itself, and writes
 * the release manifest (see [AndroidProject.releaseManifest]) for
 * `appdist distribute`.
 *
 * With --apk, skips the build and prepares that APK instead (used when a
 * project with product flavors produced several release APKs).
 */
class ReleaseCommand {

    fun execute(args: List<String>): Int {

        progress(5, "Starting release")

        val projectDirectory = File(
            System.getProperty("user.dir")
        )

        println()
        println("Project:")
        println(projectDirectory.absolutePath)

        val project = AndroidProject(projectDirectory)

        if (!project.isAndroidProject()) {
            progress(100, "Android project not found")

            println()
            println("✗ Android project not found")
            println()
            println("Run 'appdist release' from the root of an Android project.")

            return 1
        }

        val chosenApk = argument(args, "--apk")

        val apk = if (chosenApk != null) {

            File(chosenApk).takeIf { it.isFile }
                ?: return fail("APK not found: $chosenApk")

        } else {

            progress(15, "Checking project")

            if (!ProjectValidator(project).validate()) {
                progress(100, "Release validation failed")

                println()
                println("Release cancelled.")

                return 1
            }

            progress(30, "Project validation complete")

            build(project) ?: return 1
        }

        progress(90, "Reading release details from APK")

        val info = try {
            ApkInspector(project).inspect(apk)
        } catch (e: Exception) {
            return fail(e.message ?: "Could not read APK")
        }

        val metadata = ReleaseMetadata(
            projectPath = projectDirectory.absolutePath,
            apkPath = apk.absolutePath,
            applicationId = info.applicationId,
            versionName = info.versionName,
            versionCode = info.versionCode,
            appName = info.appName
        )

        println()
        println("Release Details:")
        if (metadata.appName.isNotEmpty()) {
            println("App: ${metadata.appName}")
        }
        println("App ID: ${metadata.applicationId}")
        println("Version: ${metadata.versionName}")
        println("Build: ${metadata.versionCode}")
        println("APK: ${metadata.apkPath}")

        progress(95, "Creating release manifest")

        val manifest = ReleaseManifestWriter().write(metadata, project.releaseManifest)

        // Where the IDE plugin finds the release details
        Interactive.marker("APPDIST_MANIFEST", manifest.absolutePath)

        println()
        println("✓ Release manifest created")
        println("Manifest:")
        println(manifest.absolutePath)

        progress(100, "Release ready")

        println()
        println("✓ Release build ready")

        return 0
    }

    /** Runs assembleRelease and returns the APK it produced, or null. */
    private fun build(project: AndroidProject): File? {

        println()
        println("Building release APK...")

        progress(35, "Building release APK")

        val startedAt = System.currentTimeMillis()

        val exitCode = ProcessBuilder(
            project.gradleWrapper.absolutePath,
            // Only the application module, not every module in the project
            "${project.appModulePath}:assembleRelease",
            "--console=plain"
        )
            .directory(project.directory)
            .inheritIO()
            .start()
            .waitFor()

        if (exitCode != 0) {
            progress(100, "Release build failed")

            println()
            println("✗ Release build failed")

            return null
        }

        progress(85, "Release APK generated")

        return when (val result = ApkLocator(project).locate(startedAt)) {

            is ApkLocator.Result.Found -> {
                println()
                println("✓ Release APK generated")
                result.apk
            }

            is ApkLocator.Result.Multiple -> {
                progress(100, "Choose which APK to release")

                println()
                println("✗ Several release APKs were built (product flavors):")
                result.apks.forEach { println("  ${it.absolutePath}") }
                println()
                println("Re-run with: appdist release --apk <path>")

                Interactive.marker(
                    "APPDIST_MULTIPLE_APKS",
                    result.apks.joinToString("|") { it.absolutePath }
                )

                null
            }

            ApkLocator.Result.Unsigned -> {
                progress(100, "Release APK is not signed")

                println()
                println("✗ The release APK is unsigned")
                println()
                println(
                    "Configure a release signingConfig (and make sure the " +
                        "keystore and its passwords are available on this " +
                        "machine), then try again."
                )

                Interactive.marker("APPDIST_UNSIGNED")

                null
            }

            ApkLocator.Result.NotFound -> {
                progress(100, "Release APK not found")

                println()
                println("✗ Build succeeded but no release APK was found in")
                println(File(project.appDirectory, "build/outputs/apk").absolutePath)

                null
            }
        }
    }

    private fun fail(message: String): Int {
        progress(100, "Release failed")

        println()
        println("✗ $message")

        return 1
    }

    private fun argument(args: List<String>, name: String): String? {
        val index = args.indexOf(name)
        return if (index >= 0) args.getOrNull(index + 1) else null
    }

    private fun progress(
        percentage: Int,
        message: String
    ) {
        println(
            "[APPDIST_PROGRESS] $percentage|$message"
        )

        System.out.flush()
    }
}
