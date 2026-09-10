package io.github.androiduirenderer.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo

data class SourceFingerprint(val value: String, val categories: Set<String>)

class Fingerprinter(private val projectRoot: Path) {
    private val excludedDirectories = setOf(".git", ".gradle", "build", ".android-ui-renderer")

    fun calculate(): SourceFingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val categories = sortedSetOf<String>()
        Files.walk(projectRoot).use { stream ->
            stream.filter { path -> include(path, categories) }
                .sorted()
                .forEach { path ->
                    val relative = path.relativeTo(projectRoot).toString().replace('\\', '/')
                    digest.update(relative.toByteArray())
                    digest.update(0)
                    digest.update(Files.readAllBytes(path))
                    digest.update(0)
                }
        }
        return SourceFingerprint("sha256:" + digest.digest().joinToString("") { "%02x".format(it) }, categories)
    }

    private fun include(path: Path, categories: MutableSet<String>): Boolean {
        if (path.isDirectory()) return false
        val relative = path.relativeTo(projectRoot).toString().replace('\\', '/')
        if (relative.split('/').any { it in excludedDirectories }) return false
        val category = when {
            relative.contains("/res/") || relative.startsWith("res/") -> "resources"
            relative.contains("/kotlin/") || relative.contains("/java/") -> "kotlin-java"
            relative.endsWith("AndroidManifest.xml") || relative.endsWith(".gradle") || relative.endsWith(".gradle.kts") ||
                relative in setOf("settings.gradle", "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml") ||
                relative.startsWith("buildSrc/") || relative.startsWith("build-logic/") || relative.startsWith("gradle/wrapper/") ||
                relative.endsWith(".lockfile") -> "config"
            else -> null
        }
        if (category != null) categories += category
        return category != null
    }
}
