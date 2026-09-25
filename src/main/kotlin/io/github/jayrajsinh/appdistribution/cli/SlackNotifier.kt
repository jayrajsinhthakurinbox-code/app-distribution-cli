package io.github.jayrajsinh.appdistribution.cli

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Optional Slack announcement through an Incoming Webhook
 * (https://api.slack.com/messaging/webhooks).
 *
 * The webhook URL comes only from the APPDIST_SLACK_WEBHOOK_URL environment
 * variable, never from arguments (visible in `ps`) or files. The IDE plugin
 * keeps it in the IDE password store.
 *
 * Best effort: failures are reported but never fail the release. Results are
 * printed as [APPDIST_SLACK] markers for the IDE plugin.
 */
class SlackNotifier private constructor(
    private val webhookUrl: String?
) {

    data class Release(
        val manifest: ReleaseMetadata,
        val firebaseProjectId: String,
        val releaseNotes: String,
        val consoleUrl: String?,
        val testerCount: Int,
        val uploadedBy: String
    )

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    val isConfigured: Boolean
        get() = webhookUrl != null

    fun announce(release: Release) {
        if (webhookUrl == null) {
            Interactive.marker("APPDIST_SLACK", "skipped")
            return
        }

        println()
        println("Posting release to Slack...")

        var error = post(buildMessage(release))

        if (error != null && error.startsWith("invalid_blocks")) {
            // Still announce the release, just without the layout
            error = post(plainMessage(release))
        }

        if (error == null) {
            println("✓ Posted to Slack")
            Interactive.marker("APPDIST_SLACK", "sent|Slack")
        } else {
            println("⚠ Slack notification failed: $error")
            Interactive.marker("APPDIST_SLACK", "failed|$error")
        }
    }

    /** Sends a short test message. Returns a process exit code. */
    fun sendTest(): Int {
        if (webhookUrl == null) {
            println("✗ APPDIST_SLACK_WEBHOOK_URL is not set to a Slack webhook URL")
            return 1
        }

        val error = post(
            JSONObject().put("text", "✅ App Distribution for Firebase is connected to this channel.")
        )

        return if (error == null) {
            println("✓ Test message sent")
            0
        } else {
            println("✗ $error")
            1
        }
    }

    // ---------------------------------------------------------------
    // Message
    // ---------------------------------------------------------------

    internal fun buildMessage(release: Release): JSONObject {
        val m = release.manifest
        val name = m.appName.ifEmpty { m.applicationId }
        val version = "${m.versionName} (${m.versionCode})"

        val blocks = JSONArray()

        blocks.put(
            JSONObject()
                .put("type", "section")
                .put(
                    "text", mrkdwn(
                        ":white_check_mark: *${escape(name)}* $version was uploaded " +
                            "to Firebase App Distribution"
                    )
                )
        )

        val context = buildList {
            add("`${escape(m.applicationId)}`")
            if (release.testerCount > 0) {
                add(if (release.testerCount == 1) "1 tester" else "${release.testerCount} testers")
            }
            if (release.uploadedBy.isNotBlank()) add("by ${escape(release.uploadedBy)}")
        }

        blocks.put(
            JSONObject()
                .put("type", "context")
                .put("elements", JSONArray().put(mrkdwn(context.joinToString("  ·  "))))
        )

        ReleaseNotesFormatter.slackBlocks(release.releaseNotes).forEach(blocks::put)

        release.consoleUrl?.let { url ->
            blocks.put(
                JSONObject()
                    .put("type", "actions")
                    .put(
                        "elements", JSONArray().put(
                            JSONObject()
                                .put("type", "button")
                                .put("text", JSONObject().put("type", "plain_text").put("text", "Open in Firebase"))
                                .put("url", url)
                        )
                    )
            )
        }

        return JSONObject()
            // Fallback for notifications and clients without Block Kit
            .put("text", "$name $version uploaded to Firebase App Distribution")
            .put("blocks", blocks)
            .put("unfurl_links", false)
    }

    internal fun plainMessage(release: Release): JSONObject {
        val m = release.manifest
        val name = m.appName.ifEmpty { m.applicationId }

        val text = buildString {
            append("*${escape(name)}* ${m.versionName} (${m.versionCode}) uploaded to Firebase App Distribution")

            val notes = ReleaseNotesFormatter.plainText(release.releaseNotes)
            if (notes.isNotEmpty()) append("\n\n").append(escape(notes))

            release.consoleUrl?.let { append("\n\n<$it|Open in Firebase>") }
        }

        return JSONObject().put("text", text).put("unfurl_links", false)
    }

    private fun mrkdwn(text: String) = JSONObject()
        .put("type", "mrkdwn")
        .put("text", text)

    /** Slack mrkdwn control characters. */
    private fun escape(text: String) = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    // ---------------------------------------------------------------
    // Transport
    // ---------------------------------------------------------------

    /** Returns null on success, or a human-readable error. */
    private fun post(message: JSONObject): String? = try {
        val request = HttpRequest.newBuilder(URI(webhookUrl!!))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(message.toString()))
            .build()

        var response = http.send(request, HttpResponse.BodyHandlers.ofString())

        // Respect rate limiting once
        if (response.statusCode() == 429) {
            val wait = response.headers()
                .firstValue("Retry-After")
                .map { it.toLongOrNull() ?: 1L }
                .orElse(1L)
                .coerceIn(1L, 10L)

            Thread.sleep(wait * 1000)
            response = http.send(request, HttpResponse.BodyHandlers.ofString())
        }

        if (response.statusCode() == 200) {
            null
        } else {
            when (val code = response.body().trim()) {
                "no_service", "no_team", "team_disabled", "invalid_token" ->
                    "the webhook is no longer valid; create a new one in Slack"
                "channel_is_archived" -> "the webhook's channel is archived"
                "action_prohibited" -> "a Slack admin has restricted this webhook"
                else -> code.ifEmpty { "HTTP ${response.statusCode()}" }.take(200)
            }
        }
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    companion object {

        /** Reads APPDIST_SLACK_WEBHOOK_URL; only Slack's own webhook host is accepted. */
        fun fromEnvironment(): SlackNotifier {
            val url = System.getenv("APPDIST_SLACK_WEBHOOK_URL")
                ?.trim()
                ?.takeIf { it.startsWith("https://hooks.slack.com/") }

            return SlackNotifier(url)
        }

        internal fun forTest(url: String?) = SlackNotifier(url)
    }
}
