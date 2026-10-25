package com.markopacak.divelens.ui.help

import com.markopacak.divelens.ui.help.MiniMarkdown.Block
import com.markopacak.divelens.ui.help.MiniMarkdown.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class MiniMarkdownTest {
    @Test fun headingsParagraphsBulletsAndBold() {
        val blocks = MiniMarkdown.parse(
            """
            # Title
            First line
            continues here.

            ## Section
            - **One:** first
              continued
            - Two
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Block.Heading(1, listOf(Span("Title"))),
                Block.Paragraph(listOf(Span("First line continues here."))),
                Block.Heading(2, listOf(Span("Section"))),
                Block.Bullet(listOf(Span("One:", bold = true), Span(" first continued"))),
                Block.Bullet(listOf(Span("Two"))),
            ),
            blocks,
        )
    }

    @Test fun otherSyntaxStaysPlainText() {
        assertEquals(
            listOf(Block.Paragraph(listOf(Span("### deep [link](x) **open")))),
            MiniMarkdown.parse("### deep [link](x) **open"),
        )
    }

    @Test fun thePublishedTextsLeaveNoMarkup() {
        val repo = File(System.getProperty("user.dir")!!).parentFile
        for (name in listOf("docs/PRIVACY.md", "docs/TERMS.md")) {
            val blocks = MiniMarkdown.parse(repo.resolve(name).readText())
            val text = blocks.flatMap { it.spans }.joinToString("") { it.text }
            assertFalse("$name shows **", text.contains("**"))
            assertFalse("$name shows a heading mark", blocks.any { block -> block.spans.firstOrNull()?.text?.startsWith("#") == true })
        }
    }
}
