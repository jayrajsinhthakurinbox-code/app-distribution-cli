package io.github.jayrajsinh.appdistribution.cli

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns free-form release notes into Slack rich text: bold headings and
 * bullet / numbered lists, with whatever decoration developers type
 * (dashes, arrows, emoji, checkboxes, markdown, divider lines) removed.
 *
 * Groups are separated by blank lines. In each group a heading line (ending
 * in ':' or '?', a markdown '#' heading or a fully bold line) becomes bold,
 * and the remaining lines become a list. A group that is a single
 * undecorated line stays a paragraph. Text is shown literally, so notes
 * can't trigger mentions or links.
 */
object ReleaseNotesFormatter {

    /** Items per list element, well within Slack's rich text limits. */
    private const val LIST_CHUNK = 40

    /** Rich text elements per block. */
    private const val ELEMENTS_PER_BLOCK = 10

    private const val BULLET_CHARS = "-–—*•·▪◦‣⁃+>~→⇒➜➤►▶▸✓✔☑✅"

    /** Multi-character decorations, longest first. */
    private val BULLET_TOKENS = listOf("-->", "->", "=>", ">>")

    /** "1." "2)" "(3)" "a)" "B." */
    private val NUMBER = Regex("""^\(?(?:\d{1,3}|[a-zA-Z])[.)]\s+""")

    /** "[ ]", "[x]", "[✓]" */
    private val CHECKBOX = Regex("""^\[[ xX✓✔]?]\s*""")

    /** Lines that are only decoration: "---", "===", "***", "~~~", "• • •". */
    private val DIVIDER = Regex("""^[\s\-–—=_*~#.·•]+$""")

    private val MARKDOWN_BOLD = Regex("""(\*\*|__)(.+?)\1""")

    enum class Kind { BULLET, NUMBER }

    sealed interface Element {
        data class Heading(val text: String) : Element
        data class Paragraph(val text: String) : Element
        data class ListItems(val ordered: Boolean, val items: List<String>) : Element
    }

    /** Parsed structure, independent of Slack (used for plain text too). */
    fun parse(notes: String): List<Element> {
        if (notes.isBlank()) return emptyList()

        val elements = mutableListOf<Element>()

        for (group in notes.trim().split(Regex("""\n\s*\n"""))) {
            var lines = group.lines().filter { it.isNotBlank() && !DIVIDER.matches(it) }
            if (lines.isEmpty()) continue

            val heading = headingText(lines.first())
            if (heading != null && (lines.size > 1 || elements.isEmpty())) {
                elements += Element.Heading(heading)
                lines = lines.drop(1)
            }

            if (lines.isEmpty()) continue

            val items = lines.map(::parseItem)

            if (items.size == 1 && items.single().first == null) {
                elements += Element.Paragraph(items.single().second)
                continue
            }

            val ordered = items.all { it.first == Kind.NUMBER }
            val texts = items.map { it.second }.filter { it.isNotEmpty() }

            texts.chunked(LIST_CHUNK).forEach {
                elements += Element.ListItems(ordered, it)
            }
        }

        return elements
    }

    /** Slack "rich_text" blocks. */
    fun slackBlocks(notes: String): List<JSONObject> =
        parse(notes)
            .map(::toRichText)
            .chunked(ELEMENTS_PER_BLOCK)
            .map { chunk ->
                JSONObject()
                    .put("type", "rich_text")
                    .put("elements", JSONArray(chunk))
            }

    /** Plain text rendering with "•" bullets (fallback message). */
    fun plainText(notes: String): String =
        parse(notes).joinToString("\n") { element ->
            when (element) {
                is Element.Heading -> "\n${element.text}"
                is Element.Paragraph -> element.text
                is Element.ListItems -> element.items
                    .mapIndexed { i, item -> if (element.ordered) "${i + 1}. $item" else "• $item" }
                    .joinToString("\n")
            }
        }.trim()

    private fun toRichText(element: Element): JSONObject = when (element) {
        is Element.Heading -> section(element.text, bold = true)
        is Element.Paragraph -> section(element.text)
        is Element.ListItems -> JSONObject()
            .put("type", "rich_text_list")
            .put("style", if (element.ordered) "ordered" else "bullet")
            .put("elements", JSONArray(element.items.map { section(it) }))
    }

    private fun section(text: String, bold: Boolean = false): JSONObject {
        val textElement = JSONObject()
            .put("type", "text")
            .put("text", text)

        if (bold) {
            textElement.put("style", JSONObject().put("bold", true))
        }

        return JSONObject()
            .put("type", "rich_text_section")
            .put("elements", JSONArray().put(textElement))
    }

    /** (kind, text): kind is null for an undecorated line. */
    internal fun parseItem(line: String): Pair<Kind?, String> {
        var text = line.trim()
        var kind: Kind? = null

        while (text.isNotEmpty()) {
            // "**bold**" / "__bold__" is formatting, not a "*" bullet
            if (text.startsWith("**") || text.startsWith("__")) break

            val number = NUMBER.find(text)
            val checkbox = CHECKBOX.find(text)
            val token = BULLET_TOKENS.firstOrNull { text.startsWith(it) }

            text = when {
                number != null && kind == null -> {
                    kind = Kind.NUMBER
                    text.substring(number.range.last + 1)
                }
                checkbox != null -> {
                    kind = kind ?: Kind.BULLET
                    text.substring(checkbox.range.last + 1)
                }
                token != null -> {
                    kind = kind ?: Kind.BULLET
                    text.substring(token.length)
                }
                isBulletSymbol(text) -> {
                    kind = kind ?: Kind.BULLET
                    dropSymbol(text)
                }
                else -> break
            }.trimStart()
        }

        return kind to stripMarkdown(text)
    }

    private fun isBulletSymbol(text: String): Boolean {
        val first = text[0]

        // "-5% battery" is a minus sign, not a bullet
        if (first in "-–—+" && text.length > 1 && text[1].isDigit()) return false

        if (first in BULLET_CHARS) return true

        // Leading emoji / pictographic symbol used as a bullet
        return isSymbol(text.codePointAt(0))
    }

    /** Removes the leading symbol plus any emoji modifiers attached to it. */
    private fun dropSymbol(text: String): String {
        var rest = text.substring(Character.charCount(text.codePointAt(0)))

        while (rest.isNotEmpty()) {
            val cp = rest.codePointAt(0)
            val modifier = cp == 0xFE0E || cp == 0xFE0F || cp == 0x200D ||
                cp in 0x1F3FB..0x1F3FF || (cp != ' '.code && isSymbol(cp))

            if (!modifier) break
            rest = rest.substring(Character.charCount(cp))
        }

        return rest
    }

    private fun isSymbol(codePoint: Int): Boolean =
        Character.getType(codePoint) == Character.OTHER_SYMBOL.toInt()

    private fun stripMarkdown(text: String): String =
        MARKDOWN_BOLD.replace(text) { it.groupValues[2] }.trim()

    /** Heading text without decoration, or null if the line isn't a heading. */
    private fun headingText(line: String): String? {
        val raw = line.trim()

        if (parseItem(raw).first != null && !raw.startsWith("#")) return null

        val markdownHeading = raw.startsWith("#")
        val fullyBold = Regex("""(\*\*|__).+\1:?""").matches(raw)

        val text = stripMarkdown(raw.trimStart('#').trim().trim('=').trim())
            .trim('*', '_', ' ')

        if (text.isEmpty() || text.length > 80) return null

        return if (markdownHeading || fullyBold || text.endsWith(":") || text.endsWith("?")) text else null
    }
}
