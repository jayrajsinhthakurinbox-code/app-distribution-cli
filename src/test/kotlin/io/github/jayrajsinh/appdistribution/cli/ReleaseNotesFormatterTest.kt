package io.github.jayrajsinh.appdistribution.cli

import io.github.jayrajsinh.appdistribution.cli.ReleaseNotesFormatter.Element.Heading
import io.github.jayrajsinh.appdistribution.cli.ReleaseNotesFormatter.Element.ListItems
import io.github.jayrajsinh.appdistribution.cli.ReleaseNotesFormatter.Element.Paragraph
import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseNotesFormatterTest {

    private fun parse(notes: String) = ReleaseNotesFormatter.parse(notes)

    @Test
    fun `heading and plain lines become bullets`() {
        assertEquals(
            listOf(
                Heading("What's new?"),
                ListItems(false, listOf("Added Timeline modes.", "Developed Export Flow."))
            ),
            parse("What's new?\n\nAdded Timeline modes.\nDeveloped Export Flow.")
        )
    }

    @Test
    fun `decorations are stripped`() {
        val lines = listOf(
            "- Added X", "-Added X", "– Added X", "— Added X", "* Added X", "• Added X",
            "+ Added X", "> Added X", "-> Added X", "=> Added X", "→ Added X", "➜ Added X",
            "► Added X", "✓ Added X", "✔ Added X", "✅ Added X", "☑️ Added X", "✨ Added X",
            "🐛 Added X", "👍🏽 Added X", "[ ] Added X", "[x] Added X", "- ✅ Added X",
            "  •   Added X", "- **Added X**"
        )

        for (line in lines) {
            assertEquals(
                listOf(ListItems(false, listOf("Added X", "Added X"))),
                parse("$line\n$line"),
                "for \"$line\""
            )
        }
    }

    @Test
    fun `heading decorations are stripped`() {
        for (heading in listOf("## What's new?", "**What's new?**", "=== What's new? ===", "What's new?")) {
            assertEquals(Heading("What's new?"), parse("$heading\n- one\n- two").first(), "for \"$heading\"")
        }
    }

    @Test
    fun `minus sign is kept and dividers removed`() {
        assertEquals(
            listOf(
                Heading("Performance:"),
                ListItems(false, listOf("-5% battery usage", "Faster start"))
            ),
            parse("Performance:\n---\n-5% battery usage\n- Faster start\n=====")
        )
    }

    @Test
    fun `numbered lists stay ordered`() {
        assertEquals(
            listOf(ListItems(true, listOf("One", "Two", "Three"))),
            parse("1) One\n(2) Two\na) Three")
        )
    }

    @Test
    fun `single sentence stays paragraph`() {
        assertEquals(listOf(Paragraph("Bug fixes and improvements.")), parse("Bug fixes and improvements."))
    }

    @Test
    fun `long lists are split`() {
        val notes = (0 until 100).joinToString("\n") { "- item $it" }
        assertEquals(
            listOf(40, 40, 20),
            parse(notes).map { (it as ListItems).items.size }
        )
    }

    @Test
    fun `plain text rendering`() {
        assertEquals(
            "Fixes:\n• Crash on launch\n• Slow export",
            ReleaseNotesFormatter.plainText("## Fixes:\n- Crash on launch\n✅ Slow export")
        )
    }
}
