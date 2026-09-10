package io.github.androiduirenderer.mcp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

data class RendererConfig(
    val projectRoot: Path,
    val module: String,
    val variant: String,
    val allowedTasks: List<String>,
    val workerCommand: String? = null,
    val workerArgs: List<String> = emptyList(),
    val outputDir: Path,
    val sessionTtlMinutes: Long,
    val maxSessions: Int,
)

/** A deliberately small YAML reader for the checked-in, trusted project configuration. */
object ConfigLoader {
    fun load(projectRoot: Path): RendererConfig {
        val file = projectRoot.resolve(".android-ui-renderer.yaml")
        if (!file.exists()) {
            return automatic(projectRoot)
        }
        val values = linkedMapOf<String, String>()
        val lists = linkedMapOf<String, MutableList<String>>()
        var section = ""
        var activeList: String? = null

        Files.readAllLines(file).forEachIndexed { index, raw ->
            val line = raw.substringBefore('#').trimEnd()
            if (line.isBlank()) return@forEachIndexed
            val indent = raw.takeWhile { it == ' ' }.length
            val trimmed = line.trim()
            if (trimmed.startsWith("- ")) {
                val key = activeList ?: throw RendererException("CONFIG_INVALID", "List item without key at line ${index + 1}")
                lists.getOrPut(key) { mutableListOf() }.add(unquote(trimmed.removePrefix("- ").trim()))
                return@forEachIndexed
            }
            val separator = trimmed.indexOf(':')
            if (separator < 1) throw RendererException("CONFIG_INVALID", "Expected key: value at line ${index + 1}")
            val key = trimmed.substring(0, separator).trim()
            val value = trimmed.substring(separator + 1).trim()
            if (indent == 0 && value.isEmpty()) {
                section = key
                activeList = null
            } else {
                val qualified = if (indent > 0 && section.isNotEmpty()) "$section.$key" else key
                if (value.isEmpty()) {
                    activeList = qualified
                    lists.getOrPut(qualified) { mutableListOf() }
                } else {
                    values[qualified] = unquote(value)
                    activeList = null
                }
            }
        }

        fun required(key: String): String = values[key]?.takeIf { it.isNotBlank() }
            ?: throw RendererException("CONFIG_INVALID", "Missing required configuration: $key")
        val module = required("android.module")
        val variant = values["android.variant"] ?: "debug"
        val tasks = lists["build.allowedTasks"]?.toList().orEmpty()
        if (tasks.isEmpty()) throw RendererException("CONFIG_INVALID", "build.allowedTasks must contain at least one task")
        val output = values["renderer.outputDir"] ?: ".android-ui-renderer"
        return RendererConfig(
            projectRoot = projectRoot,
            module = module,
            variant = variant,
            allowedTasks = tasks,
            workerCommand = values["worker.command"]?.takeIf { it.isNotBlank() },
            workerArgs = lists["worker.args"]?.toList().orEmpty(),
            outputDir = projectRoot.resolve(output).normalize(),
            sessionTtlMinutes = values["renderer.sessionTtlMinutes"]?.toLongOrNull() ?: 30,
            maxSessions = values["renderer.maxSessions"]?.toIntOrNull() ?: 20,
        )
    }

    private fun automatic(projectRoot: Path): RendererConfig {
        val module = System.getenv("ANDROID_UI_RENDERER_MODULE")?.takeIf { it.isNotBlank() } ?: ":app"
        val variant = System.getenv("ANDROID_UI_RENDERER_VARIANT")?.takeIf { it.isNotBlank() } ?: "debug"
        val taskVariant = variant.replaceFirstChar { it.uppercase() }
        return RendererConfig(
            projectRoot = projectRoot,
            module = module,
            variant = variant,
            allowedTasks = listOf("${module}:test${taskVariant}UnitTest"),
            outputDir = projectRoot.resolve(".android-ui-renderer"),
            sessionTtlMinutes = 30,
            maxSessions = 20,
        )
    }

    private fun unquote(value: String): String = value.removeSurrounding("\"").removeSurrounding("'")
}
