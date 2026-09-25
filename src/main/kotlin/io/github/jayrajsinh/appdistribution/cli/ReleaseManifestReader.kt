package io.github.jayrajsinh.appdistribution.cli

import java.io.File
import org.json.JSONObject

class ReleaseManifestReader {

    fun read(file: File): ReleaseMetadata {

        val root = JSONObject(
            file.readText()
        )

        return ReleaseMetadata(
            projectPath = root.getString("projectPath"),
            apkPath = root.getString("apkPath"),
            applicationId = root.getString("applicationId"),
            versionName = root.getString("versionName"),
            versionCode = root.getInt("versionCode"),
            appName = root.optString("appName")
        )
    }
}