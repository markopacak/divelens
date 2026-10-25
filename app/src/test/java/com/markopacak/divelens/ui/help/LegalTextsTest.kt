package com.markopacak.divelens.ui.help

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The app's legal texts are the repository's files, byte for byte (add-help-and-legal D4, task 2.2). */
class LegalTextsTest {
    private val module = File(System.getProperty("user.dir")!!)
    private val repo = module.parentFile

    @Test fun generatedRawResourcesEqualTheRepositoryFiles() {
        val generated = module.resolve("build/generated").walk()
            .filter { it.isFile && it.parentFile.name == "raw" && it.name.startsWith("legal_") && it.path.contains("Debug") }
            .associateBy { it.name }
        val expected = mapOf(
            "legal_license.txt" to "LICENSE",
            "legal_notice.txt" to "NOTICE",
            "legal_privacy.md" to "docs/PRIVACY.md",
            "legal_terms.md" to "docs/TERMS.md",
        )
        assertEquals(expected.keys, generated.keys)
        expected.forEach { (raw, source) -> assertArrayEquals(raw, repo.resolve(source).readBytes(), generated.getValue(raw).readBytes()) }
    }
}
