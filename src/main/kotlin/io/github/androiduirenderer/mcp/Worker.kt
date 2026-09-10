package io.github.androiduirenderer.mcp

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A worker is intentionally separate from the MCP process. Its command is trusted project
 * configuration and it must be launched with the target Android module's effective classpath.
 */
interface RendererWorker : AutoCloseable {
    fun render(request: RenderRequest): WorkerRenderResult
}

class WorkerManager(private val config: RendererConfig, private val json: Json) : AutoCloseable {
    private var generation: Long? = null
    private var worker: ProcessWorker? = null

    fun ensure(generation: Long): Boolean {
        if (this.generation == generation && worker?.alive == true) return false
        close()
        worker = ProcessWorker(config, json).also { it.start(generation) }
        this.generation = generation
        return true
    }

    fun render(request: RenderRequest): WorkerRenderResult = worker?.render(request)
        ?: throw RendererException("WORKER_NOT_STARTED", "Renderer worker has not been started")

    override fun close() {
        worker?.close()
        worker = null
        generation = null
    }
}

@Serializable
private data class WorkerEnvelope(val type: String, val generation: Long? = null, val request: RenderRequest? = null)

private class ProcessWorker(private val config: RendererConfig, private val json: Json) : RendererWorker {
    private lateinit var process: Process
    private lateinit var writer: BufferedWriter
    private lateinit var reader: java.io.BufferedReader
    val alive: Boolean get() = ::process.isInitialized && process.isAlive

    fun start(generation: Long) {
        val command = listOf(config.workerCommand) + config.workerArgs
        process = ProcessBuilder(command)
            .directory(config.projectRoot.toFile())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        writer = BufferedWriter(OutputStreamWriter(process.outputStream))
        reader = process.inputStream.bufferedReader()
        send(WorkerEnvelope(type = "initialize", generation = generation))
        val reply = readReply()
        if (reply["type"]?.jsonPrimitive?.content != "ready") {
            close()
            throw RendererException("WORKER_START_FAILED", "Worker did not acknowledge initialize")
        }
    }

    override fun render(request: RenderRequest): WorkerRenderResult {
        if (!alive) throw RendererException("WORKER_EXITED", "Renderer worker exited unexpectedly")
        send(WorkerEnvelope(type = "render", request = request))
        val reply = readReply()
        if (reply["type"]?.jsonPrimitive?.content == "error") {
            throw RendererException(reply["code"]?.jsonPrimitive?.content ?: "RENDER_FAILED", reply["message"]?.jsonPrimitive?.content ?: "Worker rendering failed")
        }
        if (reply["type"]?.jsonPrimitive?.content != "rendered") {
            throw RendererException("WORKER_PROTOCOL_ERROR", "Expected rendered response from worker")
        }
        return json.decodeFromJsonElement(WorkerRenderResult.serializer(), reply["result"] ?: throw RendererException("WORKER_PROTOCOL_ERROR", "Worker response has no result"))
    }

    private fun send(message: WorkerEnvelope) {
        writer.write(json.encodeToString(WorkerEnvelope.serializer(), message))
        writer.newLine()
        writer.flush()
    }

    private fun readReply(): JsonObject {
        val line = reader.readLine() ?: throw RendererException("WORKER_EXITED", "Renderer worker closed stdout")
        return try { json.parseToJsonElement(line).jsonObject } catch (error: Exception) {
            throw RendererException("WORKER_PROTOCOL_ERROR", "Worker returned invalid JSON: ${error.message}")
        }
    }

    override fun close() {
        if (::process.isInitialized && process.isAlive) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }
}
