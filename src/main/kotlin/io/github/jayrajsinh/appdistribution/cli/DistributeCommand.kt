package io.github.jayrajsinh.appdistribution.cli

import java.io.File

class DistributeCommand {

    fun execute(args: List<String>): Int {

        println("🚀 App Distribution")

        Interactive.progress(5, "Checking release")

        val projectDirectory = File(
            System.getProperty("user.dir")
        )

        println()
        println("Project:")
        println(projectDirectory.absolutePath)

        val project = AndroidProject(
            projectDirectory
        )

        if (!project.isAndroidProject()) {

            println()
            println("✗ Android project not found")
            println()
            println(
                "Run 'appdist distribute' from the root of an Android project."
            )

            return 1
        }

        val manifestFile = project.releaseManifest

        if (!manifestFile.exists()) {

            println()
            println("✗ Release manifest not found")
            println()
            println(
                "Run 'appdist release' before distributing."
            )

            return 1
        }

        val manifest = try {

            ReleaseManifestReader()
                .read(manifestFile)

        } catch (e: Exception) {

            println()
            println("✗ Invalid release manifest")
            println()
            println(e.message)

            return 1
        }

        val apk = File(
            manifest.apkPath
        )

        if (!apk.exists()) {

            println()
            println("✗ Release APK not found")
            println()
            println(apk.absolutePath)

            return 1
        }

        println()
        println("✓ Release APK found")

        println()
        println("Release Details:")
        println("App ID: ${manifest.applicationId}")
        println("Version: ${manifest.versionName}")
        println("Build: ${manifest.versionCode}")
        println("APK: ${manifest.apkPath}")

        /*
         * Firebase CLI
         */

        val firebase = FirebaseCli()

        println()
        println("Checking Firebase CLI...")

        Interactive.progress(15, "Checking Firebase")

        if (!firebase.ensureInstalled()) {

            println()
            println("✗ Firebase CLI is required")

            return 2
        }

        /*
         * Firebase authentication
         */

        println()
        println("Checking Firebase authentication...")

        if (!firebase.isAuthenticated()) {

            if (!Interactive.isInteractive) {

                // The IDE plugin handles sign-in and retries
                println()
                println("✗ Firebase authentication is required")

                Interactive.marker("APPDIST_AUTH_REQUIRED")

                return 3
            }

            println()
            println("Firebase authentication is required.")
            println()
            println(
                "Opening Firebase login now."
            )
            println()

            if (!firebase.login()) {

                println()
                println("✗ Firebase login failed")

                return 3
            }

            println()
            println("✓ Firebase login completed")
        } else {

            println()
            println("✓ Firebase authentication found")
        }

        /*
         * Firebase configuration
         */

        val firebaseConfig = try {

            FirebaseConfigExtractor(
                project
            ).extract(
                manifest.applicationId
            )

        } catch (e: Exception) {

            println()
            println("✗ Firebase configuration error")
            println()
            println(e.message)

            return 1
        }

        println()
        println("✓ Firebase configuration found")

        println()
        println("Firebase Project:")
        println(firebaseConfig.projectId)

        println()
        println("Firebase App ID:")
        println(firebaseConfig.appId)

        /*
         * Distribution arguments
         */

        val testersFile = getArgument(
            args,
            "--testers-file"
        )

        val releaseNotesFile = getArgument(
            args,
            "--release-notes-file"
        )

        if (testersFile != null) {

            val file = File(testersFile)

            if (!file.exists()) {

                println()
                println("✗ Testers file not found")
                println()
                println(file.absolutePath)

                return 1
            }
        }

        if (releaseNotesFile != null) {

            val file = File(releaseNotesFile)

            if (!file.exists()) {

                println()
                println("✗ Release notes file not found")
                println()
                println(file.absolutePath)

                return 1
            }
        }

        /*
         * Firebase App Distribution
         */

        Interactive.progress(25, "Uploading APK to Firebase")

        val result = firebase.distribute(
            apkPath = apk.absolutePath,
            firebaseAppId = firebaseConfig.appId,
            testersFile = testersFile,
            releaseNotesFile = releaseNotesFile
        )

        if (result.exitCode != 0) {

            println()
            println("✗ Firebase distribution failed")

            return result.exitCode
        }

        println()
        println(
            "✓ Release submitted to Firebase App Distribution"
        )

        result.consoleUrl?.let {
            Interactive.marker("APPDIST_CONSOLE_URL", it)
        }

        /*
         * Optional Slack announcement — best effort, never fails the release
         */

        val slack = SlackNotifier.fromEnvironment()

        if (slack.isConfigured) {
            Interactive.progress(95, "Posting to Slack")
        }

        slack.announce(
            SlackNotifier.Release(
                manifest = manifest,
                firebaseProjectId = firebaseConfig.projectId,
                releaseNotes = releaseNotesFile
                    ?.let { File(it).readText().trim() }
                    .orEmpty(),
                consoleUrl = result.consoleUrl,
                testerCount = testersFile
                    ?.let { countTesters(File(it)) }
                    ?: 0,
                uploadedBy = uploaderName(projectDirectory)
            )
        )

        Interactive.progress(100, "Done")

        return 0
    }

    private fun countTesters(file: File): Int =
        file.readText()
            .split(',', '\n')
            .count { it.isNotBlank() }

    /** git user.name for the project, falling back to the OS user. */
    private fun uploaderName(projectDirectory: File): String {

        val gitName = try {
            val process = ProcessBuilder("git", "config", "user.name")
                .directory(projectDirectory)
                .redirectErrorStream(true)
                .start()

            val name = process.inputStream
                .bufferedReader()
                .readText()
                .trim()

            if (process.waitFor() == 0) name else ""
        } catch (e: Exception) {
            ""
        }

        return gitName.ifEmpty { System.getProperty("user.name") }
    }

    private fun getArgument(
        args: List<String>,
        name: String
    ): String? {

        val index = args.indexOf(name)

        if (index == -1) {
            return null
        }

        if (index + 1 >= args.size) {

            println()
            println("✗ Missing value for $name")

            return null
        }

        return args[index + 1]
    }
}