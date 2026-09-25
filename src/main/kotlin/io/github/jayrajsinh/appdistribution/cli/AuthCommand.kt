package io.github.jayrajsinh.appdistribution.cli

/**
 * Firebase sign-in without a terminal, used by the IDE plugin:
 *
 *   appdist auth status          exit 0 and [APPDIST_ACCOUNT] <email> if signed in
 *   appdist auth start           prints [APPDIST_LOGIN_URL] / [APPDIST_LOGIN_SESSION]
 *   appdist auth complete <code> finishes sign-in with the code from the browser
 */
class AuthCommand {

    fun execute(args: List<String>): Int {

        val firebase = FirebaseCli()

        if (!firebase.isInstalled()) {

            println("✗ Firebase CLI is not installed")

            Interactive.marker("APPDIST_FIREBASE_MISSING")

            return 2
        }

        return when (args.firstOrNull()) {

            "status" -> {
                val account = firebase.account()

                if (account != null) {
                    Interactive.marker("APPDIST_ACCOUNT", account)
                    0
                } else {
                    println("Not signed in to Firebase")
                    3
                }
            }

            "start" -> {
                if (firebase.loginStart()) 0 else 1
            }

            "complete" -> {

                val code = args.getOrNull(1)

                if (code.isNullOrBlank()) {
                    println("✗ Missing authorization code")
                    return 1
                }

                if (firebase.loginComplete(code.trim())) {
                    println()
                    println("✓ Firebase login completed")
                    0
                } else {
                    println()
                    println("✗ Firebase login failed")
                    3
                }
            }

            else -> {
                println("Usage: appdist auth status|start|complete <code>")
                1
            }
        }
    }
}
