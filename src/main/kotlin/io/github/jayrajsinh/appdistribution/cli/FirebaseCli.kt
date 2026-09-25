package io.github.jayrajsinh.appdistribution.cli

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.TimeUnit

class FirebaseCli {

    /*
     * Where App Distribution installs Firebase's standalone binary: per user, no admin
     * rights, and independent of Node/npm. Checked first because a copy we
     * installed is known to work.
     */
    private val cliBin = File(System.getProperty("user.home"), ".app-distribution/bin")
    private val localFirebase = File(cliBin, "firebase")

    private val executableCandidates = listOf(
        localFirebase.path,
        "/opt/homebrew/bin/firebase",
        "/usr/local/bin/firebase"
    )

    /*
     * Apps launched from the Dock (like Android Studio) get a minimal PATH
     * without Homebrew. An npm-installed firebase is a `#!/usr/bin/env node`
     * script, so it fails unless we add the usual locations.
     */
    private val extraPathEntries = listOf(
        "/opt/homebrew/bin",
        "/usr/local/bin",
        "${System.getProperty("user.home")}/.npm-global/bin"
    )

    private fun command(vararg args: String): ProcessBuilder =
        command(args.toList())

    private fun command(args: List<String>): ProcessBuilder {

        val builder = ProcessBuilder(args)

        val env = builder.environment()

        val current = env["PATH"]
            .orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotEmpty() }

        env["PATH"] = (current + extraPathEntries)
            .distinct()
            .joinToString(File.pathSeparator)

        return builder
    }

    fun findExecutable(): String? {

        // Our own copy, then Homebrew / known locations
        for (path in executableCandidates) {
            if (File(path).canExecute()) {
                return path
            }
        }

        // PATH
        return try {
            val process = command(
                "which",
                "firebase"
            )
                .redirectErrorStream(true)
                .start()

            val output = process
                .inputStream
                .bufferedReader()
                .readText()
                .trim()

            val exitCode = process.waitFor()

            if (exitCode == 0 && output.isNotEmpty()) {
                output
            } else {
                null
            }

        } catch (e: Exception) {
            null
        }
    }

    /** Installed and actually runnable (a broken npm install doesn't count). */
    fun isInstalled(): Boolean {
        return version() != null
    }

    fun version(): String? {

        val executable = findExecutable()
            ?: return null

        return run(executable, "--version")
            ?.takeIf { it.first == 0 }
            ?.second
            ?.lines()
            ?.lastOrNull { it.isNotBlank() }
            ?.trim()
    }

    /** Runs a firebase command, returning (exitCode, output) or null. */
    private fun run(
        vararg args: String,
        timeoutSeconds: Long = 120
    ): Pair<Int, String>? {

        return try {
            val process = command(*args)
                .redirectErrorStream(true)
                .start()

            process.outputStream.close()

            val output = StringBuilder()

            val reader = Thread {
                output.append(process.inputStream.bufferedReader().readText())
            }.apply { start() }

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }

            reader.join(5_000)

            process.exitValue() to output.toString().trim()

        } catch (e: Exception) {
            null
        }
    }

    /**
     * Installs Firebase's standalone binary into ~/.app-distribution/bin. Needs no
     * Node, npm or admin rights and doesn't touch the rest of the system.
     */
    fun install(assumeYes: Boolean = false): Boolean {

        println()
        println("Firebase CLI is not installed.")
        println()

        if (!assumeYes && !Interactive.isInteractive) {

            // Can't prompt; let the caller (IDE plugin) ask instead
            Interactive.marker("APPDIST_FIREBASE_MISSING")

            return false
        }

        println("App Distribution can download Firebase CLI (standalone, ~270 MB) to:")
        println(localFirebase.path)
        println()

        val answer = if (assumeYes) {
            "y"
        } else {
            print("Install Firebase CLI now? [y/N]: ")

            readlnOrNull()
                ?.trim()
                ?.lowercase()
        }

        if (answer != "y" && answer != "yes") {

            println()
            println("Firebase CLI installation cancelled.")

            return false
        }

        println()
        println("Downloading Firebase CLI...")

        val temp = File(cliBin, "firebase.download")

        try {
            cliBin.mkdirs()

            download(STANDALONE_URL, temp)

            temp.setExecutable(true, true)

            Files.move(
                temp.toPath(),
                localFirebase.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: Exception) {

            temp.delete()

            println()
            println("✗ Firebase CLI download failed")
            println(e.message ?: e.javaClass.simpleName)

            return false
        }

        println()
        println("Verifying Firebase CLI (first run can take a minute)...")

        val check = run(localFirebase.path, "--version", timeoutSeconds = 300)

        if (check == null || check.first != 0) {

            val output = check?.second.orEmpty()

            localFirebase.delete()

            println()

            if (output.contains("Bad CPU type", ignoreCase = true)) {
                println("✗ Firebase CLI needs Rosetta on this Mac. Install it with:")
                println("  softwareupdate --install-rosetta --agree-to-license")
            } else {
                println("✗ Firebase CLI was downloaded but does not run")
                println(output.take(500))
            }

            return false
        }

        println()
        println("✓ Firebase CLI installed (${check.second.lines().last().trim()})")

        return true
    }

    private fun download(url: String, target: File) {

        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build()

        val request = HttpRequest.newBuilder(URI(url))
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

        if (response.statusCode() != 200) {
            response.body().close()
            error("HTTP ${response.statusCode()} from $url")
        }

        val total = response.headers()
            .firstValueAsLong("Content-Length")
            .orElse(-1L)

        var written = 0L
        var lastReported = -1L

        response.body().use { input ->
            target.outputStream().use { output ->

                val buffer = ByteArray(1 shl 16)

                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break

                    output.write(buffer, 0, read)
                    written += read

                    if (total > 0) {
                        val percent = written * 100 / total

                        if (percent / 10 != lastReported / 10) {
                            lastReported = percent
                            println("  ${percent}% of ${total / (1024 * 1024)} MB")
                            System.out.flush()
                        }
                    }
                }
            }
        }

        if (total > 0 && written != total) {
            error("Download incomplete ($written of $total bytes)")
        }
    }

    fun ensureInstalled(assumeYes: Boolean = false): Boolean {

        val version = version()

        if (version != null) {

            println()
            println("✓ Firebase CLI found")
            println("Version: $version")

            return true
        }

        return install(assumeYes)
    }

    /** Email of the signed-in Firebase account, or null. */
    fun account(): String? {

        val executable = findExecutable()
            ?: return null

        val (exitCode, output) = run(executable, "login:list")
            ?: return null

        if (exitCode != 0) {
            return null
        }

        return Regex("""Logged in as (\S+)""")
            .find(output)
            ?.groupValues
            ?.get(1)
    }

    fun isAuthenticated(): Boolean {
        return account() != null
    }

    /**
     * Interactive browser login. Only works with a real terminal; the IDE
     * plugin uses [loginStart] / [loginComplete] instead.
     */
    fun login(): Boolean {

        val executable = findExecutable()
            ?: return false

        if (!Interactive.isInteractive) {
            return false
        }

        println()
        println(
            "Opening Firebase authentication..."
        )
        println()

        return try {

            val process = command(
                executable,
                "login"
            )
                .inheritIO()
                .start()

            val exitCode = process.waitFor()

            // `firebase login` can exit 0 without logging in, so verify
            exitCode == 0 && isAuthenticated()

        } catch (e: Exception) {

            println()
            println(
                "✗ Firebase login failed"
            )

            println()
            println(
                e.message
            )

            false
        }
    }

    data class DistributionResult(
        val exitCode: Int,
        val consoleUrl: String?,
        val testerUrl: String?
    )

    fun distribute(
        apkPath: String,
        firebaseAppId: String,
        testersFile: String?,
        releaseNotesFile: String?
    ): DistributionResult {

        val executable = findExecutable()
            ?: return DistributionResult(1, null, null)

        val command = mutableListOf(
            executable,
            "appdistribution:distribute",
            apkPath,
            "--app",
            firebaseAppId
        )

        if (!testersFile.isNullOrBlank()) {

            command += listOf(
                "--testers-file",
                testersFile
            )
        }

        if (!releaseNotesFile.isNullOrBlank()) {

            command += listOf(
                "--release-notes-file",
                releaseNotesFile
            )
        }

        println()
        println(
            "Uploading release to Firebase App Distribution..."
        )
        println()

        val process = command(command)
            .redirectErrorStream(true)
            .start()

        // Echo output as it arrives, and keep it to pick out release links
        val output = mutableListOf<String>()

        process.inputStream
            .bufferedReader()
            .forEachLine { line ->
                println(line)
                output += line
                reportUploadProgress(line)
            }

        return DistributionResult(
            exitCode = process.waitFor(),
            consoleUrl = findUrl(output, "Firebase console"),
            testerUrl = findUrl(output, "testers who have access")
        )
    }

    /** Maps firebase-tools output to progress for the IDE plugin. */
    private fun reportUploadProgress(line: String) {
        val text = line.lowercase()

        when {
            "uploading binary" in text ->
                Interactive.progress(40, "Uploading APK to Firebase")
            "uploaded new release" in text || "uploaded update" in text ->
                Interactive.progress(70, "APK uploaded")
            "added release notes" in text ->
                Interactive.progress(80, "Release notes added")
            "distributed to testers" in text ->
                Interactive.progress(90, "Sent to testers")
        }
    }

    private fun findUrl(
        output: List<String>,
        label: String
    ): String? = output
        .firstOrNull { it.contains(label, ignoreCase = true) }
        ?.let { Regex("""https://\S+""").find(it)?.value }
        ?.substringBefore("?utm_source")

    /**
     * Step 1 of the non-interactive login: asks Firebase for a login URL
     * and prints it as markers. Firebase keeps the pending session in its
     * own config, so [loginComplete] can run in a separate process.
     */
    fun loginStart(): Boolean {

        val executable = findExecutable()
            ?: return false

        val output = try {

            val process = command(
                executable,
                "login",
                "--non-interactive"
            )
                .redirectErrorStream(true)
                .start()

            val text = process
                .inputStream
                .bufferedReader()
                .readText()

            if (process.waitFor() != 0) {
                println(text)
                return false
            }

            text

        } catch (e: Exception) {

            println("✗ Unable to start Firebase login")
            println(e.message)

            return false
        }

        val url = Regex(
            """https://\S+/login\?\S+"""
        ).find(output)?.value

        if (url == null) {
            println(output)
            println("✗ Firebase did not return a login URL")
            return false
        }

        // Session prefix is the first non-empty line after "session ID"
        val session = output
            .lineSequence()
            .dropWhile { !it.contains("session ID", ignoreCase = true) }
            .drop(1)
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()

        Interactive.marker("APPDIST_LOGIN_URL", url)
        Interactive.marker("APPDIST_LOGIN_SESSION", session)

        return true
    }

    /** Step 2 of the non-interactive login. */
    fun loginComplete(authorizationCode: String): Boolean {

        val executable = findExecutable()
            ?: return false

        return try {

            val process = command(
                executable,
                "login",
                authorizationCode
            )
                .redirectErrorStream(true)
                .inheritIO()
                .start()

            process.waitFor() == 0 && isAuthenticated()

        } catch (e: Exception) {

            println("✗ Firebase login failed")
            println(e.message)

            false
        }
    }

    private companion object {
        // Redirects to the latest firebase-tools-macos GitHub release asset
        const val STANDALONE_URL = "https://firebase.tools/bin/macos/latest"
    }
}
