package io.github.androiduirenderer.mcp

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Path
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
    if (args.isNotEmpty() && args.singleOrNull() != "--stdio") {
        System.err.println("Usage: android-ui-renderer-mcp --stdio")
        return
    }
    val projectRoot = Path.of(System.getenv("PROJECT_PATH") ?: System.getProperty("user.dir")).toAbsolutePath().normalize()
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    try {
        val config = ConfigLoader.load(projectRoot)
        RendererService(config, json).use { service ->
            StdioMcpServer(service, BufferedReader(InputStreamReader(System.`in`)), BufferedWriter(OutputStreamWriter(System.out)), json).run()
        }
    } catch (error: RendererException) {
        System.err.println("${error.code}: ${error.message}")
    }
}
