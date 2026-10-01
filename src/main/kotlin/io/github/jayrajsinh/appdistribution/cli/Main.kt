package io.github.jayrajsinh.appdistribution.cli

import kotlin.system.exitProcess

private val USAGE = """
    appdist: build Android release APKs and send them to testers with
    Firebase App Distribution.

    Usage (run from the root of an Android project):
      appdist release [--build-type release|debug] [--apk <path>]
          Build the APK (release by default) or use an existing one, and
          record its details.

      appdist distribute [--testers-file <file>] [--release-notes-file <file>]
          Upload the last release to Firebase App Distribution.

      appdist firebase [--yes]
          Check for Firebase CLI and install it if missing.

      appdist auth status | start | complete <code>
          Firebase sign-in without a terminal (used by the IDE plugin).

      appdist slack test
          Send a test message to APPDIST_SLACK_WEBHOOK_URL.

      appdist --version

    Environment:
      APPDIST_SLACK_WEBHOOK_URL   Slack Incoming Webhook for release announcements (optional)
      APPDIST_NON_INTERACTIVE=1   Never prompt; report needs via markers (IDE plugin)
""".trimIndent()

fun main(args: Array<String>) {
    val rest = args.drop(1)

    val exitCode = when (args.firstOrNull()) {
        "release" -> ReleaseCommand().execute(rest)

        "distribute" -> DistributeCommand().execute(rest)

        "firebase" -> {
            if (FirebaseCli().ensureInstalled(assumeYes = "--yes" in rest)) {
                println()
                println("Firebase CLI is ready.")
                0
            } else {
                1
            }
        }

        "auth" -> AuthCommand().execute(rest)

        "slack" -> if (rest.firstOrNull() == "test") {
            SlackNotifier.fromEnvironment().sendTest()
        } else {
            println("Usage: appdist slack test")
            1
        }

        "--version", "-v" -> {
            println("appdist ${VersionAnchor::class.java.`package`?.implementationVersion ?: "dev"}")
            0
        }

        null, "help", "--help", "-h" -> {
            println(USAGE)
            0
        }

        else -> {
            println("Unknown command: ${args.first()}\n")
            println(USAGE)
            1
        }
    }

    exitProcess(exitCode)
}

/** Anchor for reading the jar's Implementation-Version. */
private object VersionAnchor
