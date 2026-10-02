package io.github.androiduirenderer.mcp

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ComposeCallGeneratorTest {
    @Test
    fun `generates a typed direct call with enum data class mutable state and callbacks`() {
        val root = Files.createTempDirectory("compose-call")
        val source = root.resolve("app/src/main/kotlin/demo/ReportItem.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, """
            package demo

            @Composable
            internal fun AppTheme(content: @Composable () -> Unit) {}

            @Composable
            fun ReportItem(role: Role, model: ReportModel, onCheckedChange: (Boolean) -> Unit, onOpen: () -> Unit = {}) {}

            enum class Role { VIEWER, ADMIN }

            data class ReportModel(val title: String, val active: Boolean, private val initialChecked: Boolean = false) {
                var checked by mutableStateOf(initialChecked)
            }
        """.trimIndent())
        val arguments = Json.parseToJsonElement("""{
          "role": "ADMIN",
          "model": { "title": "Clean Code", "active": true, "checked": true }
        }""").jsonObject

        val generated = ComposeCallGenerator(root).generate(ComposeRender("demo.ReportItem", arguments))

        assertContains(generated.preamble, "demo.ReportModel(title = \"Clean Code\", active = true)")
        assertContains(generated.preamble, ".checked = true")
        assertContains(generated.invocation, "role = demo.Role.ADMIN")
        assertContains(generated.invocation, "onCheckedChange = { _ -> }")
        assertContains(generated.invocation, "onOpen = {}")
        assertContains(generated.invocation, "demo.ReportItem(")
        assertContains(generated.theme ?: "", "demo.AppTheme")
    }
}
