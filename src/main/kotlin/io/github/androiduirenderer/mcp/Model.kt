package io.github.androiduirenderer.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement

@Serializable
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

@Serializable
data class ViewNode(
    val id: String? = null,
    val resourceName: String? = null,
    val className: String,
    val visibility: String = "VISIBLE",
    val enabled: Boolean = true,
    val clickable: Boolean = false,
    val selected: Boolean = false,
    val checked: Boolean? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val bounds: Bounds,
    val measuredWidth: Int = bounds.width,
    val measuredHeight: Int = bounds.height,
    val margins: Insets? = null,
    val padding: Insets? = null,
    val children: List<ViewNode> = emptyList(),
)

@Serializable
data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int)

@Serializable
data class RenderRequest(
    val layout: String,
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    /** Exact measured View bounds. Cannot be combined with the matching *Dp field. */
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val densityDpi: Int? = null,
    val orientation: String? = null,
    val theme: String? = null,
    val locale: String? = null,
    val nightMode: Boolean? = null,
    val fontScale: Float? = null,
    /** Solid canvas background used before the root View is drawn. */
    val background: String? = "#FFFFFF",
    /** Explicit runtime values supplied by the agent; no values are inferred by the renderer. */
    val fixture: Map<String, ViewFixture> = emptyMap(),
)

/**
 * Properties the renderer may set on a View after inflation and before measure/layout/draw.
 * A null property means "leave the XML/runtime default untouched".
 */
@Serializable
data class ViewFixture(
    val text: String? = null,
    val hint: String? = null,
    val contentDescription: String? = null,
    val visibility: String? = null,
    val enabled: Boolean? = null,
    val selected: Boolean? = null,
    val checked: Boolean? = null,
    val image: FixtureImage? = null,
    val backgroundColor: String? = null,
    val textColor: String? = null,
    val textSizeSp: Float? = null,
    val strikeThrough: Boolean? = null,
)

@Serializable
data class FixtureImage(
    val type: FixtureImageType,
    val value: String,
)

@Serializable
enum class FixtureImageType {
    @SerialName("local_path") LOCAL_PATH,
    @SerialName("drawable_resource") DRAWABLE_RESOURCE,
    @SerialName("color") COLOR,
}

@Serializable
data class WorkerTimings(
    /** Wall time spent executing the Gradle test task that hosts the sidecar probe. */
    val gradleMs: Long = 0,
    /** Time inside the probe from entering the test to writing the PNG and View Tree. */
    val probeRenderMs: Long = 0,
)

@Serializable
data class WorkerRenderResult(
    val widthPx: Int,
    val heightPx: Int,
    val screenshotPath: String,
    val viewTree: ViewNode,
    val warnings: List<String> = emptyList(),
    val timings: WorkerTimings = WorkerTimings(),
)

data class RenderSession(
    val id: String,
    val generation: Long,
    val screenshotPath: String,
    val viewTreePath: String,
    val tree: ViewNode,
    val createdAtMillis: Long,
)

class RendererException(
    val code: String,
    override val message: String,
    val details: JsonElement? = null,
) : RuntimeException(message)
