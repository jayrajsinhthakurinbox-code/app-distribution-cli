package io.github.jayrajsinh.appdistribution.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlackNotifierTest {

    private val release = SlackNotifier.Release(
        manifest = ReleaseMetadata(
            projectPath = "/p",
            apkPath = "/p/app.apk",
            applicationId = "com.example.app",
            versionName = "1.2.3",
            versionCode = 45,
            appName = "Example <App>"
        ),
        firebaseProjectId = "example",
        releaseNotes = "What's new?\n- Faster\n- <!channel> fixed",
        consoleUrl = "https://console.firebase.google.com/project/example/releases/1",
        testerCount = 3,
        uploadedBy = "Jay"
    )

    private val notifier = SlackNotifier.forTest("https://hooks.slack.com/services/T/B/x")

    @Test
    fun `message has summary, notes and button`() {
        val message = notifier.buildMessage(release)
        val blocks = message.getJSONArray("blocks")
        val types = (0 until blocks.length()).map { blocks.getJSONObject(it).getString("type") }

        assertEquals(listOf("section", "context", "rich_text", "actions"), types)
        assertEquals("Example <App> 1.2.3 (45) uploaded to Firebase App Distribution", message.getString("text"))

        val summary = blocks.getJSONObject(0).getJSONObject("text").getString("text")
        assertTrue("Example &lt;App&gt;" in summary)
        assertTrue("3 testers" in blocks.getJSONObject(1).toString())
    }

    @Test
    fun `notes are literal rich text`() {
        val json = notifier.buildMessage(release).toString()
        // Shown as text, not parsed as a mention
        assertTrue("\"text\":\"<!channel> fixed\"" in json)
    }

    @Test
    fun `plain fallback escapes and formats`() {
        val text = notifier.plainMessage(release).getString("text")
        assertTrue("• Faster" in text)
        assertTrue("&lt;!channel&gt;" in text)
        assertFalse("<!channel>" in text)
    }
}
