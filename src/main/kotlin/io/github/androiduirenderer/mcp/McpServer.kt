package io.github.androiduirenderer.mcp

import java.io.BufferedReader
import java.io.BufferedWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

class StdioMcpServer(
    private val service: RendererService,
    private val input: BufferedReader,
    private val output: BufferedWriter,
    private val json: Json,
) {
    fun run() {
        input.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val request = try { json.parseToJsonElement(line).jsonObject } catch (_: Exception) {
                write(error(null, -32700, "Parse error")); return@forEach
            }
            val id = request["id"]
            val method = request["method"]?.jsonPrimitive?.content
            if (method.isNullOrBlank()) {
                if (id != null) write(error(id, -32600, "Invalid request"))
                return@forEach
            }
            if (id == null) return@forEach // MCP notifications never receive responses.
            try {
                write(response(id, dispatch(method, request["params"]?.jsonObject ?: buildJsonObject {})))
            } catch (error: RendererException) {
                write(response(id, toolError(error.code, error.message, error.details)))
            } catch (error: Exception) {
                System.err.println("Unhandled MCP error: ${error.stackTraceToString()}")
                write(response(id, toolError("INTERNAL_ERROR", error.message ?: "Unexpected renderer failure")))
            }
        }
    }

    private fun dispatch(method: String, params: JsonObject): JsonObject = when (method) {
        "initialize" -> buildJsonObject {
            put("protocolVersion", "2024-11-05")
            putJsonObject("capabilities") { putJsonObject("tools") {} }
            putJsonObject("serverInfo") { put("name", "android-ui-renderer-mcp"); put("version", "0.1.0") }
        }
        "tools/list" -> buildJsonObject { put("tools", toolDefinitions()) }
        "tools/call" -> callTool(params)
        else -> throw RendererException("METHOD_NOT_FOUND", "Unsupported MCP method: $method")
    }

    private fun callTool(params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.content ?: throw RendererException("INVALID_REQUEST", "Tool name is required")
        val args = params["arguments"]?.jsonObject ?: buildJsonObject {}
        return when (name) {
            "render_layout" -> render(args)
            "get_view_tree" -> textResult(json.encodeToString(ViewNode.serializer(), service.tree(requiredString(args, "renderId"))))
            "inspect_view" -> textResult(json.encodeToString(ViewNode.serializer(), service.inspect(requiredString(args, "renderId"), requiredString(args, "viewId"))))
            else -> throw RendererException("TOOL_NOT_FOUND", "Unknown tool: $name")
        }
    }

    private fun render(args: JsonObject): JsonObject {
        val request = RenderRequest(
            layout = requiredString(args, "layout"),
            widthDp = args.int("widthDp"), heightDp = args.int("heightDp"),
            widthPx = args.int("widthPx"), heightPx = args.int("heightPx"), densityDpi = args.int("densityDpi"),
            orientation = args.string("orientation"), theme = args.string("theme"), locale = args.string("locale"),
            nightMode = args.bool("nightMode"), fontScale = args.float("fontScale"),
            background = args.string("background") ?: "#FFFFFF",
            fixture = fixture(args),
        )
        val (session, freshness) = service.render(request)
        val result = buildJsonObject {
            put("renderId", session.id)
            put("generation", freshness.generation)
            put("sourceFingerprint", freshness.fingerprint.value)
            put("screenshotPath", session.screenshotPath)
            put("viewTreePath", session.viewTreePath)
            putJsonArray("changes") { freshness.fingerprint.categories.forEach { add(JsonPrimitive(it)) } }
            putJsonObject("build") {
                put("performed", freshness.build.performed); put("incremental", freshness.build.incremental); put("durationMs", freshness.build.durationMs)
            }
            putJsonObject("worker") { put("restarted", freshness.workerRestarted); put("generation", freshness.generation) }
        }
        return textResult(json.encodeToString(JsonObject.serializer(), result))
    }

    private fun toolDefinitions() = buildJsonArray {
        add(tool("render_layout", "Render an Android XML layout using the configured Robolectric worker. fixture is applied after inflation; it is explicit agent-provided state, never inferred from tools:* attributes.", buildJsonObject {
            put("type", "object"); putJsonObject("properties") {
                putJsonObject("layout") { put("type", "string"); put("description", "Layout resource name without @layout/") }
                putJsonObject("widthDp") { put("type", "integer"); put("description", "Measured root width in dp; mutually exclusive with widthPx") }
                putJsonObject("heightDp") { put("type", "integer"); put("description", "Measured root height in dp; mutually exclusive with heightPx") }
                putJsonObject("widthPx") { put("type", "integer"); put("description", "Exact measured root width in physical pixels; mutually exclusive with widthDp") }
                putJsonObject("heightPx") { put("type", "integer"); put("description", "Exact measured root height in physical pixels; mutually exclusive with heightDp") }
                putJsonObject("densityDpi") { put("type", "integer"); put("description", "Device density used for Android dp/sp resource resolution") }; putJsonObject("orientation") { put("type", "string") }
                putJsonObject("theme") { put("type", "string") }; putJsonObject("locale") { put("type", "string") }
                putJsonObject("nightMode") { put("type", "boolean") }; putJsonObject("fontScale") { put("type", "number") }
                putJsonObject("background") { put("type", "string"); put("description", "Opaque canvas color in #RRGGBB or #AARRGGBB; defaults to #FFFFFF") }
                put("fixture", fixtureSchema())
            }; putJsonArray("required") { add(JsonPrimitive("layout")) }
        }))
        add(tool("get_view_tree", "Return the machine-readable View hierarchy for a prior render.", idSchema("renderId")))
        add(tool("inspect_view", "Return bounds, visibility, text, padding and margins for one View.", buildJsonObject {
            put("type", "object"); putJsonObject("properties") {
                putJsonObject("renderId") { put("type", "string") }; putJsonObject("viewId") { put("type", "string") }
            }; putJsonArray("required") { add(JsonPrimitive("renderId")); add(JsonPrimitive("viewId")) }
        }))
    }

    private fun fixture(args: JsonObject): Map<String, ViewFixture> {
        val value = args["fixture"] ?: return emptyMap()
        return try {
            json.decodeFromJsonElement(MapSerializer(String.serializer(), ViewFixture.serializer()), value)
        } catch (error: Exception) {
            throw RendererException("INVALID_REQUEST", "fixture must be an object mapping View ids to supported properties: ${error.message}")
        }
    }

    private fun fixtureSchema() = buildJsonObject {
        put("type", "object")
        put("description", "Map of @id/name (or name) to explicit View properties. Values not supplied retain their inflated XML state.")
        putJsonObject("additionalProperties") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") { put("type", "string") }
                putJsonObject("hint") { put("type", "string") }
                putJsonObject("contentDescription") { put("type", "string") }
                putJsonObject("visibility") { put("type", "string"); putJsonArray("enum") { add(JsonPrimitive("visible")); add(JsonPrimitive("invisible")); add(JsonPrimitive("gone")) } }
                putJsonObject("enabled") { put("type", "boolean") }
                putJsonObject("selected") { put("type", "boolean") }
                putJsonObject("checked") { put("type", "boolean") }
                putJsonObject("backgroundColor") { put("type", "string") }
                putJsonObject("textColor") { put("type", "string") }
                putJsonObject("textSizeSp") { put("type", "number") }
                putJsonObject("strikeThrough") { put("type", "boolean") }
                putJsonObject("image") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("type") { put("type", "string"); putJsonArray("enum") { add(JsonPrimitive("local_path")); add(JsonPrimitive("drawable_resource")); add(JsonPrimitive("color")) } }
                        putJsonObject("value") { put("type", "string") }
                    }
                    putJsonArray("required") { add(JsonPrimitive("type")); add(JsonPrimitive("value")) }
                }
            }
        }
    }

    private fun tool(name: String, description: String, schema: JsonObject) = buildJsonObject { put("name", name); put("description", description); put("inputSchema", schema) }
    private fun idSchema(name: String) = buildJsonObject { put("type", "object"); putJsonObject("properties") { putJsonObject(name) { put("type", "string") } }; putJsonArray("required") { add(JsonPrimitive(name)) } }
    private fun textResult(text: String) = buildJsonObject { putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) } }
    private fun toolError(code: String, message: String, details: JsonElement? = null) = buildJsonObject {
        put("isError", true); putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", json.encodeToString(JsonObject.serializer(), buildJsonObject { put("code", code); put("message", message); if (details != null) put("details", details) })) }) }
    }
    private fun response(id: JsonElement, result: JsonObject) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }
    private fun error(id: JsonElement?, code: Int, message: String) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id ?: kotlinx.serialization.json.JsonNull); putJsonObject("error") { put("code", code); put("message", message) } }
    private fun write(value: JsonObject) { output.write(json.encodeToString(JsonObject.serializer(), value)); output.newLine(); output.flush() }
    private fun requiredString(args: JsonObject, key: String) = args.string(key) ?: throw RendererException("INVALID_REQUEST", "$key is required")
    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content
    private fun JsonObject.int(key: String) = string(key)?.toIntOrNull()
    private fun JsonObject.float(key: String) = string(key)?.toFloatOrNull()
    private fun JsonObject.bool(key: String) = string(key)?.toBooleanStrictOrNull()
}
