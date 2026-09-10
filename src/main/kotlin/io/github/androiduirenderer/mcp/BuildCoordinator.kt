package io.github.androiduirenderer.mcp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.system.measureTimeMillis

data class BuildResult(val performed: Boolean, val durationMs: Long = 0, val incremental: Boolean = true)

class BuildCoordinator(private val config: RendererConfig) {
    fun ensureFresh(): BuildResult {
        val gradlew = config.projectRoot.resolve(if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew")
        if (!gradlew.exists()) throw RendererException("PROJECT_NOT_FOUND", "Gradle wrapper not found at $gradlew")
        val command = listOf(gradlew.toAbsolutePath().toString()) + config.allowedTasks + "--console=plain"
        val output = StringBuilder()
        val elapsed = measureTimeMillis {
            val process = ProcessBuilder(command)
                .directory(config.projectRoot.toFile())
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { output.appendLine(it) } }
            if (process.waitFor() != 0) {
                throw RendererException("BUILD_FAILED", "Allowed Gradle build failed", kotlinx.serialization.json.JsonPrimitive(output.toString().takeLast(16_000)))
            }
        }
        return BuildResult(performed = true, durationMs = elapsed)
    }
}
