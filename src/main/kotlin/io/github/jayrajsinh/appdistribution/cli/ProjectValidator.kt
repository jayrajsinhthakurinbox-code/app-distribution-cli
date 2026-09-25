package io.github.jayrajsinh.appdistribution.cli

import java.io.File

class ProjectValidator(
    private val project: AndroidProject
) {

    fun validate(): Boolean {

        println()
        println("Checking project...")
        println()

        // Signing can also live in convention plugins or included scripts,
        // so this only warns; an unsigned build is caught after building.
        if (checkSigningConfiguration()) {
            println("✓ Signing configuration found")
        } else {
            println("⚠ No signingConfig found in the app build file")
        }

        if (!checkFirebaseConfiguration()) {
            println("✗ google-services.json not found")
            println("Download it from the Firebase console into ${project.appDirectory.name}/")
            return false
        }

        println("✓ Firebase configuration found")

        return true
    }

    private fun checkSigningConfiguration(): Boolean {
        val buildFile = project.appGradleFile ?: return false
        return buildFile.readText().contains("signingConfig")
    }

    private fun checkFirebaseConfiguration(): Boolean =
        FirebaseConfigExtractor(project).configFiles().isNotEmpty()
}