package io.github.androiduirenderer.mcp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.measureTimeMillis

data class Freshness(
    val generation: Long,
    val fingerprint: SourceFingerprint,
    val build: BuildResult,
    val workerRestarted: Boolean,
    val timings: RenderTimings,
)

data class RenderTimings(
    val fingerprintMs: Long,
    val gradleMs: Long,
    val renderMs: Long,
    val totalMs: Long,
)

class RendererService(private val config: RendererConfig, private val json: kotlinx.serialization.json.Json) : AutoCloseable {
    private val fingerprinter = Fingerprinter(config.projectRoot)
    private val build = BuildCoordinator(config)
    private val worker = WorkerManager(config, json)
    private val sessions = SessionStore(config.outputDir, config.sessionTtlMinutes * 60_000, config.maxSessions, json)
    private var fingerprint: SourceFingerprint? = null
    private var generation = 0L

    @Synchronized
    fun render(request: RenderRequest): Pair<RenderSession, Freshness> {
        var session: RenderSession? = null
        var freshness: Freshness? = null
        val totalMs = measureTimeMillis {
            RenderRequestValidator.validate(request)
            lateinit var current: SourceFingerprint
            val fingerprintMs = measureTimeMillis { current = fingerprinter.calculate() }
            val changed = current.value != fingerprint?.value
            val buildResult = if (changed) {
                generation += 1
                build.ensureFresh()
            } else BuildResult(performed = false)
            val restarted = worker.ensure(generation)
            val result = worker.render(request)
            fingerprint = current
            session = sessions.create(generation, result)
            freshness = Freshness(
                generation,
                current,
                buildResult,
                restarted,
                RenderTimings(
                    fingerprintMs = fingerprintMs,
                    gradleMs = buildResult.durationMs + result.timings.gradleMs,
                    renderMs = result.timings.probeRenderMs,
                    totalMs = 0,
                ),
            )
        }
        val computed = freshness ?: error("render did not produce freshness")
        return (session ?: error("render did not produce session")) to computed.copy(
            timings = computed.timings.copy(totalMs = totalMs),
        )
    }

    fun tree(renderId: String): ViewNode = sessions.get(renderId).tree

    fun inspect(renderId: String, id: String): ViewNode {
        val normalized = id.removePrefix("@id/").substringAfterLast('/')
        return find(sessions.get(renderId).tree, normalized)
            ?: throw RendererException("VIEW_NOT_FOUND", "View not found: $id")
    }

    private fun find(node: ViewNode, id: String): ViewNode? = when {
        node.id == id || node.resourceName?.endsWith(":id/$id") == true -> node
        else -> node.children.firstNotNullOfOrNull { find(it, id) }
    }

    override fun close() = worker.close()
}

object RenderRequestValidator {
    fun validate(request: RenderRequest) {
        if (!request.layout.matches(Regex("[A-Za-z0-9_]+"))) throw RendererException("LAYOUT_NOT_FOUND", "Invalid layout resource name")
        if (request.widthDp != null && request.widthDp !in 1..20_000) throw RendererException("INVALID_REQUEST", "widthDp is out of range")
        if (request.heightDp != null && request.heightDp !in 1..20_000) throw RendererException("INVALID_REQUEST", "heightDp is out of range")
        if (request.widthPx != null && request.widthPx !in 1..50_000) throw RendererException("INVALID_REQUEST", "widthPx is out of range")
        if (request.heightPx != null && request.heightPx !in 1..50_000) throw RendererException("INVALID_REQUEST", "heightPx is out of range")
        if (request.widthDp != null && request.widthPx != null) throw RendererException("INVALID_REQUEST", "Specify only one of widthDp or widthPx")
        if (request.heightDp != null && request.heightPx != null) throw RendererException("INVALID_REQUEST", "Specify only one of heightDp or heightPx")
        if (request.densityDpi != null && request.densityDpi !in 72..960) throw RendererException("INVALID_REQUEST", "densityDpi is out of range")
        request.theme?.let { theme ->
            val name = theme.removePrefix("@style/")
            if (!name.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) throw RendererException("INVALID_REQUEST", "theme must be a style name or @style/name")
        }
        request.orientation?.let { orientation ->
            if (orientation !in setOf("portrait", "landscape")) throw RendererException("INVALID_REQUEST", "orientation must be portrait or landscape")
        }
        request.locale?.let { locale ->
            if (!locale.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*"))) throw RendererException("INVALID_REQUEST", "locale must be a BCP 47 language tag")
        }
        request.fontScale?.let { if (it !in 0.5f..3f) throw RendererException("INVALID_REQUEST", "fontScale is out of range") }
        if (request.fixture.size > 500) throw RendererException("INVALID_REQUEST", "fixture may contain at most 500 View overrides")
        request.background?.let { validateColor("background", it) }
        request.fixture.forEach { (selector, fixture) ->
            val id = selector.removePrefix("@id/").removePrefix("@+id/")
            if (!id.matches(Regex("[A-Za-z0-9_]+"))) {
                throw RendererException("INVALID_REQUEST", "Invalid fixture View id: $selector")
            }
            listOf(fixture.text, fixture.hint, fixture.contentDescription).forEach { value ->
                if (value != null && value.length > 10_000) throw RendererException("INVALID_REQUEST", "Fixture text is too long for $selector")
            }
            fixture.visibility?.let { visibility ->
                if (visibility !in setOf("visible", "invisible", "gone")) {
                    throw RendererException("INVALID_REQUEST", "Invalid visibility for $selector: $visibility")
                }
            }
            fixture.backgroundColor?.let { validateColor("backgroundColor for $selector", it) }
            fixture.textColor?.let { validateColor("textColor for $selector", it) }
            fixture.textSizeSp?.let { if (it !in 1f..500f) throw RendererException("INVALID_REQUEST", "textSizeSp is out of range for $selector") }
            fixture.image?.let { image ->
                if (image.value.isBlank() || image.value.length > 4_096) throw RendererException("INVALID_REQUEST", "Invalid image value for $selector")
                when (image.type) {
                    FixtureImageType.COLOR -> validateColor("image color for $selector", image.value)
                    FixtureImageType.DRAWABLE_RESOURCE -> {
                        val name = image.value.removePrefix("@drawable/")
                        if (!name.matches(Regex("[a-z][a-z0-9_]*"))) {
                            throw RendererException("INVALID_REQUEST", "drawable_resource for $selector must be a drawable name or @drawable/name")
                        }
                    }
                    FixtureImageType.LOCAL_PATH -> {
                        val imagePath = try { Path.of(image.value) } catch (_: Exception) {
                            throw RendererException("INVALID_REQUEST", "local_path for $selector is not a valid path")
                        }
                        if (!imagePath.isAbsolute || !Files.isRegularFile(imagePath)) {
                            throw RendererException("INVALID_REQUEST", "local_path for $selector must be an existing absolute file")
                        }
                        if (Files.size(imagePath) > 20L * 1024 * 1024) {
                            throw RendererException("INVALID_REQUEST", "local_path for $selector exceeds the 20 MiB limit")
                        }
                    }
                }
            }
        }
    }

    private fun validateColor(name: String, value: String) {
        if (!value.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) {
            throw RendererException("INVALID_REQUEST", "$name must be #RRGGBB or #AARRGGBB")
        }
    }
}
