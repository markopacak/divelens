package com.markopacak.divelens.ui.help

/**
 * The little Markdown the privacy policy and terms use (add-help-and-legal D4): `#` and `##` headings, paragraphs,
 * `- ` bullets (continued by indented lines) and `**bold**`. Anything else is kept as plain text.
 */
object MiniMarkdown {
    data class Span(val text: String, val bold: Boolean = false)

    sealed interface Block {
        val spans: List<Span>
        data class Heading(val level: Int, override val spans: List<Span>) : Block
        data class Paragraph(override val spans: List<Span>) : Block
        data class Bullet(override val spans: List<Span>) : Block
    }

    fun parse(text: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val pending = StringBuilder()
        var bullet = false
        fun flush() {
            if (pending.isNotEmpty()) {
                val spans = inline(pending.toString())
                blocks += if (bullet) Block.Bullet(spans) else Block.Paragraph(spans)
            }
            pending.clear()
            bullet = false
        }
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            val heading = HEADING.matchEntire(line)
            when {
                line.isBlank() -> flush()
                heading != null -> { flush(); blocks += Block.Heading(heading.groupValues[1].length, inline(heading.groupValues[2])) }
                line.startsWith("- ") -> { flush(); bullet = true; pending.append(line.removePrefix("- ").trim()) }
                else -> { if (pending.isNotEmpty()) pending.append(' '); pending.append(line.trim()) }
            }
        }
        flush()
        return blocks
    }

    /** Splits `**bold**` runs; an unmatched `**` stays as text. */
    fun inline(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        var at = 0
        for (match in BOLD.findAll(text)) {
            if (match.range.first > at) spans += Span(text.substring(at, match.range.first))
            spans += Span(match.groupValues[1], bold = true)
            at = match.range.last + 1
        }
        if (at < text.length) spans += Span(text.substring(at))
        return spans
    }

    private val HEADING = Regex("""^(#{1,2}) +(.+)$""")
    private val BOLD = Regex("""\*\*(.+?)\*\*""")
}
