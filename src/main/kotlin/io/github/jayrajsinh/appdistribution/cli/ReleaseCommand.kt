package io.github.jayrajsinh.appdistribution.cli

import java.io.File

/**
 * appdist release [--build-type release|debug] [--apk <path>]
 *
 * Builds the APK with Gradle (release by default), finds the APK that was
 * actually produced, reads its package, version and build type from the APK
 * itself, and writes the release manifest (see
 * [AndroidProject.releaseManifest]) for `appdist distribute`.
 *
 * With --apk, skips the build and prepares that APK instead (an existing
 * file, or one of several flavor APKs).
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

        val buildType = argument(args, "--build-type")
            ?.let { BuildType.parse(it) ?: return fail("Unknown build type \"$it\" (use release or debug)") }
            ?: BuildType.RELEASE

        val apk = if (chosenApk != null) {

            File(chosenApk).takeIf { it.isFile }
                ?: return fail("APK not found: $chosenApk")

        } else {

            progress(15, "Checking project")

            if (!ProjectValidator(project).validate(buildType)) {
                progress(100, "Release validation failed")

                println()
                println("Release cancelled.")

                return 1
            }

            progress(30, "Project validation complete")

            build(project, buildType) ?: return 1
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
            appName = info.appName,
            buildType = if (info.debuggable) BuildType.DEBUG.id else BuildType.RELEASE.id
        )

        println()
        println("Release Details:")
        if (metadata.appName.isNotEmpty()) {
            println("App: ${metadata.appName}")
        }
        println("App ID: ${metadata.applicationId}")
        println("Version: ${metadata.versionName}")
        println("Build: ${metadata.versionCode}")
        println("Type: ${BuildType.parse(metadata.buildType)?.label ?: metadata.buildType}")
        println("APK: ${metadata.apkPath}")

        progress(95, "Creating release manifest")

        val manifest = ReleaseManifestWriter().write(metadata, project.releaseManifest)

        // Where the IDE plugin finds the release details
        Interactive.marker("APPDIST_MANIFEST", manifest.absolutePath)

        println()
        println("✓ Release manifest created")
        println("Manifest:")
        println(manifest.absolutePath)

        progress(100, "Build ready")

        println()
        println("✓ Build ready to distribute")

        return 0
    }

    /** Runs assemble<BuildType> and returns the APK it produced, or null. */
    private fun build(project: AndroidProject, buildType: BuildType): File? {

        val kind = buildType.id

        println()
        println("Building $kind APK...")

        progress(35, "Building ${buildType.label.lowercase()} APK")

        val startedAt = System.currentTimeMillis()

        val exitCode = ProcessBuilder(
            project.gradleWrapper.absolutePath,
            // Only the application module, not every module in the project
            "${project.appModulePath}:assemble${buildType.taskSuffix}",
            "--console=plain"
        )
            .directory(project.directory)
            .inheritIO()
            .start()
            .waitFor()

        if (exitCode != 0) {
            progress(100, "Build failed")

            println()
            println("✗ ${buildType.label} build failed")

            return null
        }

        progress(85, "${buildType.label} APK generated")

        return when (val result = ApkLocator(project).locate(startedAt, buildType)) {

            is ApkLocator.Result.Found -> {
                println()
                println("✓ ${buildType.label} APK generated")
                result.apk
            }

            is ApkLocator.Result.Multiple -> {
                progress(100, "Choose which APK to distribute")

                println()
                println("✗ Several $kind APKs were built (product flavors):")
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
                progress(100, "APK not found")

                println()
                println("✗ Build succeeded but no $kind APK was found in")
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
