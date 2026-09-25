package io.github.jayrajsinh.appdistribution.cli

import org.json.JSONObject
import java.io.File

class ReleaseManifestWriter {

    fun write(metadata: ReleaseMetadata, manifestFile: File): File {

        manifestFile.parentFile.mkdirs()

        // JSONObject handles escaping (paths with quotes, backslashes, etc.)
        val json = JSONObject()
            .put("projectPath", metadata.projectPath)
            .put("apkPath", metadata.apkPath)
            .put("applicationId", metadata.applicationId)
            .put("versionName", metadata.versionName)
            .put("versionCode", metadata.versionCode)
            .put("appName", metadata.appName)

        manifestFile.writeText(json.toString(2))

        return manifestFile
    }
}
