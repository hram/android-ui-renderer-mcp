package io.github.androiduirenderer.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SessionStore(
    private val root: Path,
    private val ttlMillis: Long,
    private val maxSessions: Int,
    private val json: Json,
) {
    private val sessions = ConcurrentHashMap<String, RenderSession>()
    private val random = SecureRandom()

    init { Files.createDirectories(root.resolve("sessions")) }

    fun create(generation: Long, result: WorkerRenderResult): RenderSession {
        purge()
        while (sessions.size >= maxSessions) sessions.values.minByOrNull { it.createdAtMillis }?.let { remove(it.id) }
        val id = "r-" + ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val directory = root.resolve("sessions").resolve(id)
        Files.createDirectories(directory)
        val treePath = directory.resolve("view-tree.json")
        Files.writeString(treePath, json.encodeToString(result.viewTree))
        val metadata = """{"renderId":"$id","generation":$generation,"createdAt":"${Instant.now()}"}"""
        Files.writeString(directory.resolve("metadata.json"), metadata)
        return RenderSession(id, generation, result.screenshotPath, treePath.toString(), result.viewTree, System.currentTimeMillis())
            .also { sessions[id] = it }
    }

    fun get(id: String): RenderSession {
        val session = sessions[id] ?: throw RendererException("SESSION_NOT_FOUND", "Render session not found: $id")
        if (expired(session)) {
            remove(id)
            throw RendererException("SESSION_NOT_FOUND", "Render session expired: $id")
        }
        return session
    }

    private fun purge() = sessions.values.filter(::expired).forEach { remove(it.id) }
    private fun expired(session: RenderSession) = System.currentTimeMillis() - session.createdAtMillis > ttlMillis
    private fun remove(id: String) { sessions.remove(id) }
}
