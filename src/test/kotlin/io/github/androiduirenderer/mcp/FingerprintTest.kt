package io.github.androiduirenderer.mcp

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FingerprintTest {
    @Test
    fun `fingerprint changes for resources and excludes renderer artifacts`() {
        val root = Files.createTempDirectory("renderer-fingerprint")
        Files.createDirectories(root.resolve("app/src/main/res/layout"))
        Files.writeString(root.resolve("app/src/main/res/layout/screen.xml"), "<View/>")
        val fingerprinter = Fingerprinter(root)
        val first = fingerprinter.calculate()
        Files.createDirectories(root.resolve(".android-ui-renderer/sessions/a"))
        Files.writeString(root.resolve(".android-ui-renderer/sessions/a/view-tree.json"), "ignored")
        assertEquals(first.value, fingerprinter.calculate().value)
        Files.writeString(root.resolve("app/src/main/res/layout/screen.xml"), "<TextView/>")
        val second = fingerprinter.calculate()
        assertTrue("resources" in second.categories)
        assertTrue(first.value != second.value)
    }
}
