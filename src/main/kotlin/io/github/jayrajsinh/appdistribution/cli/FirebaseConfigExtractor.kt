package io.github.jayrajsinh.appdistribution.cli

import org.json.JSONObject
import java.io.File

/**
 * Finds the Firebase project and app ID for an application ID by reading the
 * module's google-services.json files: the module root plus any variant or
 * flavor folders under src/ (e.g. src/release, src/prod, src/prodRelease).
 */
class FirebaseConfigExtractor(
    private val project: AndroidProject
) {

    fun configFiles(): List<File> {
        val app = project.appDirectory

        val variantFiles = File(app, "src")
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .sortedBy { it.name }
            .map { File(it, "google-services.json") }

        return (listOf(File(app, "google-services.json")) + variantFiles)
            .filter { it.isFile }
    }

    fun extract(applicationId: String): FirebaseConfig {

        val files = configFiles()

        if (files.isEmpty()) {
            error(
                "google-services.json not found in ${project.appDirectory.absolutePath} " +
                    "(or its src/<variant> folders). Download it from the Firebase console."
            )
        }

        for (file in files) {
            find(file, applicationId)?.let { return it }
        }

        error(
            "No Firebase Android app with package \"$applicationId\" in " +
                files.joinToString { it.relativeTo(project.directory).path } +
                ". Add the app in the Firebase console and download a fresh google-services.json."
        )
    }

    private fun find(file: File, applicationId: String): FirebaseConfig? {

        val root = try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            return null
        }

        val projectId = root.optJSONObject("project_info")
            ?.optString("project_id")
            ?: return null

        val clients = root.optJSONArray("client") ?: return null

        for (i in 0 until clients.length()) {

            val clientInfo = clients.optJSONObject(i)
                ?.optJSONObject("client_info")
                ?: continue

            val packageName = clientInfo
                .optJSONObject("android_client_info")
                ?.optString("package_name")

            if (packageName == applicationId) {
                return FirebaseConfig(
                    projectId = projectId,
                    appId = clientInfo.getString("mobilesdk_app_id")
                )
            }
        }

        return null
    }
}
