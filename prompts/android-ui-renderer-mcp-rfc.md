# RFC: Android UI Renderer MCP for Codex

**Status:** Draft  
**Version:** 0.3  
**Target:** Android XML/View projects  
**Primary client:** Codex / MCP-compatible coding agents  
**Rendering backend:** Robolectric  
**Runtime model:** Persistent MCP server with reloadable Robolectric worker JVM

---

## 1. Purpose

The goal of this project is to give a coding agent such as Codex a fast way to render and inspect Android XML-based screens without launching an emulator or using a physical device.

The expected agent workflow is:

1. Modify XML layout or Android View code.
2. Ask the MCP server to render the screen.
3. Inspect the resulting screenshot.
4. Inspect element geometry and hierarchy.
5. Detect overlaps, clipping, incorrect margins, off-screen elements, and other layout issues.
6. Modify the implementation.
7. Render again.
8. Repeat until the screen is acceptable.

The system is not intended to replace device or emulator testing. It is intended to provide a fast feedback loop during implementation.

---

## 2. Problem

Current Android UI verification usually requires one of the following:

- Android Studio Layout Preview;
- emulator;
- physical device;
- screenshot tests executed through Gradle;
- manual inspection.

These approaches are poorly suited to an autonomous coding agent.

The agent needs a machine-accessible tool with the following properties:

- callable over MCP;
- deterministic;
- fast enough for repeated use;
- capable of rendering XML/View layouts;
- capable of returning screenshots;
- capable of returning exact bounds of UI elements;
- capable of reporting common layout errors;
- independent from a running emulator.

---

## 3. Scope

### In scope

Initial implementation should support:

- Android XML layouts;
- standard Android Views;
- ConstraintLayout;
- Material Components where practical;
- Android themes and resources;
- portrait and landscape dimensions;
- configurable screen size;
- configurable density;
- layout inflation;
- measure/layout/draw cycle;
- PNG screenshot generation;
- View hierarchy export;
- lookup by `android:id`;
- bounds inspection;
- visibility inspection;
- basic layout diagnostics;
- persistent MCP server with reloadable Robolectric worker JVM.

### Out of scope for MVP

The first version does not need to support:

- real CameraX preview;
- WebView rendering fidelity;
- SurfaceView;
- TextureView;
- OpenGL;
- video;
- animations;
- complex gestures;
- full accessibility testing;
- real device font rendering;
- pixel-perfect equivalence with Android hardware rendering;
- emulator control;
- golden screenshot regression testing.

These features may be added later.

---

## 4. High-Level Architecture

```text
┌──────────────────────────────────────┐
│                Codex                 │
│          or another AI agent         │
└──────────────────┬───────────────────┘
                   │ MCP
                   ▼
┌──────────────────────────────────────┐
│ Persistent android-ui-renderer-mcp   │
│                                      │
│ MCP tool layer                       │
│ Source fingerprint / generation      │
│ Build Coordinator                    │
│ Worker Manager                       │
│ Render Session Manager               │
└──────────────────┬───────────────────┘
                   │
          source freshness check
                   │
          ┌────────┴────────┐
          │                 │
     no changes        changes found
          │                 │
          │                 ▼
          │        Allowed incremental
          │           Gradle build
          │                 │
          │                 ▼
          │          restart worker
          │                 │
          └────────┬────────┘
                   ▼
┌──────────────────────────────────────┐
│ Reloadable Renderer Worker JVM       │
│                                      │
│ Robolectric                          │
│ Android module classpath             │
│ compiled/generated resources         │
│ generated R / binding classes        │
│ application/custom View classes      │
└──────────────────┬───────────────────┘
                   │
                   ▼
        PNG + View Tree + diagnostics
```

### Architectural decision

The **MCP server is persistent**, but the Robolectric worker/runtime is **reloadable**.

The system MUST NOT assume that one permanently initialized Robolectric runtime will automatically observe edits to XML, Kotlin, Java, generated resources, or generated classes.

For MVP, use the conservative policy:

```text
No relevant changes
    → reuse worker
    → render

Any relevant changes
    → allowed incremental Gradle build
    → restart worker JVM
    → render
```

Do not optimize XML-only or Kotlin-only reload paths until measurements show that worker restart/build latency is a real problem.

The persistent MCP process is responsible for orchestration, caching metadata, source fingerprints, sessions, build coordination, and worker lifecycle. The worker is disposable and may be recreated whenever the source generation changes.

## 5. Core Concept: Render Session

Every successful render creates a render session.

Example:

```text
render_layout
      │
      ▼
renderId = "a7f32c"
      │
      ├── screenshot.png
      ├── view-tree.json
      ├── metadata.json
      └── diagnostics
```

Subsequent MCP calls should reference `renderId`.

This avoids re-rendering when the agent only wants to inspect an element.

Example:

```text
render_layout(...)
→ renderId = "a7f32c"

inspect_view(
    renderId = "a7f32c",
    viewId = "scan_button"
)
```

---

## 6. MCP API v1

The MVP should expose three required tools and one optional diagnostics tool.

---

# 6.1 `render_layout`

Renders an Android XML layout.

## Input

```json
{
  "layout": "screen_scanner",
  "widthDp": 1280,
  "heightDp": 800,
  "densityDpi": 160,
  "orientation": "landscape",
  "theme": "Theme.StaffTablet",
  "output": {
    "screenshot": true,
    "viewTree": true
  }
}
```

## Required fields

- `layout`

## Optional fields

- `widthDp`
- `heightDp`
- `densityDpi`
- `orientation`
- `theme`
- `locale`
- `nightMode`
- `fontScale`

Suggested defaults:

```text
widthDp     = project/device default
heightDp    = project/device default
densityDpi  = 160
orientation = portrait
fontScale   = 1.0
nightMode   = false
```

## Output

```json
{
  "renderId": "a7f32c",
  "layout": "screen_scanner",
  "widthPx": 1280,
  "heightPx": 800,
  "screenshotPath": "/tmp/android-ui-renderer/a7f32c/screenshot.png",
  "viewTreePath": "/tmp/android-ui-renderer/a7f32c/view-tree.json",
  "warnings": []
}
```

The MCP transport may additionally expose the screenshot as an MCP image/content result if supported by the client.

---

# 6.2 `get_view_tree`

Returns the hierarchy from an existing render.

## Input

```json
{
  "renderId": "a7f32c"
}
```

## Output

```json
{
  "root": {
    "id": "root",
    "class": "androidx.constraintlayout.widget.ConstraintLayout",
    "bounds": {
      "left": 0,
      "top": 0,
      "right": 1280,
      "bottom": 800
    },
    "children": []
  }
}
```

For large screens, the server may support:

```json
{
  "renderId": "a7f32c",
  "maxDepth": 4
}
```

---

# 6.3 `inspect_view`

Returns detailed information about one View.

## Input

```json
{
  "renderId": "a7f32c",
  "viewId": "scan_button"
}
```

The tool should accept both:

```text
scan_button
```

and:

```text
@id/scan_button
```

## Output

```json
{
  "id": "scan_button",
  "resourceName": "com.example:id/scan_button",
  "class": "com.google.android.material.button.MaterialButton",
  "text": "Сканировать",
  "visibility": "VISIBLE",
  "enabled": true,
  "bounds": {
    "left": 1016,
    "top": 692,
    "right": 1248,
    "bottom": 756,
    "width": 232,
    "height": 64
  },
  "margins": {
    "left": 0,
    "top": 0,
    "right": 32,
    "bottom": 24
  },
  "padding": {
    "left": 16,
    "top": 8,
    "right": 16,
    "bottom": 8
  }
}
```

---

# 6.4 `check_layout`

Optional for MVP, recommended shortly after.

Runs automatic geometric checks.

## Input

```json
{
  "renderId": "a7f32c",
  "rules": [
    "inside_screen",
    "no_overlaps",
    "min_touch_target"
  ]
}
```

## Suggested rules

### `inside_screen`

Detects visible Views whose bounds exceed the root bounds.

### `no_overlaps`

Detects suspicious intersection between visible sibling Views.

This rule must support ignore lists because deliberate overlays are common.

### `min_touch_target`

Checks clickable controls against a configurable minimum size.

Default:

```text
48dp × 48dp
```

### `negative_size`

Detects invalid or zero-size visible Views.

### `clipped_text`

Best-effort detection where text dimensions exceed available View bounds.

### `edge_spacing`

Optionally checks minimum distance from screen edges.

## Output

```json
{
  "issues": [
    {
      "severity": "warning",
      "rule": "edge_spacing",
      "viewId": "scan_button",
      "message": "Right margin is 8dp; expected at least 16dp",
      "actual": 8,
      "expectedMinimum": 16
    }
  ]
}
```

---

## 7. View Tree Format

The hierarchy must be machine-readable and stable enough for AI-agent inspection.

Recommended format:

```json
{
  "id": "price_block",
  "resourceName": "com.example:id/price_block",
  "class": "android.widget.LinearLayout",
  "visibility": "VISIBLE",
  "clickable": false,
  "enabled": true,
  "bounds": {
    "left": 24,
    "top": 96,
    "right": 560,
    "bottom": 220
  },
  "children": [
    {
      "id": "price",
      "class": "android.widget.TextView",
      "text": "7 299 ₽",
      "bounds": {
        "left": 24,
        "top": 110,
        "right": 180,
        "bottom": 158
      }
    }
  ]
}
```

The serialization should include at minimum:

- ID;
- fully-qualified resource name;
- View class;
- visibility;
- enabled;
- clickable;
- selected;
- bounds;
- measured width/height;
- margins;
- padding;
- text where applicable;
- contentDescription where applicable;
- child hierarchy.

Do not dump every internal Android property by default.

The tree should remain concise enough for agent consumption.

---

## 8. Rendering Pipeline

A render request should conceptually perform:

```text
resolve project
      ↓
resolve resources
      ↓
create fresh Robolectric context/activity for this render
      ↓
create fresh LayoutInflater / View hierarchy
      ↓
apply configuration
      ↓
apply theme
      ↓
inflate XML
      ↓
set root dimensions
      ↓
measure()
      ↓
layout()
      ↓
draw(Canvas)
      ↓
serialize hierarchy
      ↓
run diagnostics
      ↓
store render session
      ↓
return result
```

---

## 9. Robolectric Integration and Worker Lifecycle

The implementation should use Robolectric to provide Android framework behavior on the JVM.

The MCP server itself should remain alive across requests, but the renderer worker must be considered valid only for the source/build generation for which it was started.

### Required invariant

```text
workerGeneration == sourceGeneration
```

A worker MUST NOT render when it is stale.

If:

```text
workerGeneration != sourceGeneration
```

the server must build the configured Android module/variant and restart the worker before rendering.

### MVP lifecycle

```text
MCP server starts
      ↓
calculate source fingerprint
      ↓
ensure allowed Gradle build outputs exist
      ↓
start renderer worker
      ↓
render requests
      ↓
source fingerprint unchanged?
  ├── yes → reuse worker → render
  └── no  → increment generation
             ↓
          allowed incremental Gradle build
             ↓
          stop old worker
             ↓
          start new worker for generation
             ↓
          render
```

### Per-render state isolation

Reusing a worker process does not mean reusing UI/application state between `render_layout` calls.

Every render request must create a fresh rendering scope, including a fresh Activity or equivalent Context, LayoutInflater, and View hierarchy.

Conceptually:

```text
same worker process
    ↓
render request A
    → fresh Activity/Context
    → fresh LayoutInflater
    → fresh View tree
    → dispose render scope
    ↓
render request B
    → fresh Activity/Context
    → fresh LayoutInflater
    → fresh View tree
```

The worker process, classpath, and validated build outputs may be reused while the source generation is unchanged, but UI state from a previous render must not leak into the next one.

The implementation should explicitly clean up or recreate state that can otherwise survive between renders, including where practical:

- Activities;
- Views and ViewModels;
- LayoutInflater instances;
- lifecycle owners;
- coroutine scopes/executors started for rendering;
- temporary resources;
- test/application containers;
- singleton/static state known to affect rendering.

If project-level singletons or static state cannot be reliably reset inside the same worker, the renderer should prefer restarting the worker rather than returning a potentially nondeterministic result.

Determinism is more important than avoiding a worker restart.

### Why restart the whole worker?

For MVP, any relevant source change should trigger a full worker restart after the incremental build.

This is intentionally conservative. It avoids fragile logic attempting to determine whether a resource, classloader, Robolectric sandbox, theme, generated `R`, binding class, or custom View can be safely hot-reloaded.

Fine-grained invalidation may be investigated only after the basic system is correct and latency has been measured.

### Worker classpath

The renderer worker MUST start with the effective classpath/build outputs of the selected Android module and variant.

Direct access to `src/main/res` is not sufficient for a real application.

The worker may require:

- compiled Android resources;
- generated `R`;
- compiled Kotlin/Java classes;
- generated classes;
- Data Binding / View Binding outputs where applicable;
- Material Components dependencies;
- ConstraintLayout and other Android dependencies;
- application custom Views;
- variant-specific resources and classes;
- transitive runtime dependencies required by rendering.

The exact integration mechanism depends on the Android Gradle Plugin and project structure and should be isolated behind the Build Coordinator.

## 10. Rendering a View

Conceptually:

```kotlin
val view = LayoutInflater.from(context)
    .inflate(layoutId, null)

val widthPx = dpToPx(widthDp)
val heightPx = dpToPx(heightDp)

view.measure(
    View.MeasureSpec.makeMeasureSpec(
        widthPx,
        View.MeasureSpec.EXACTLY
    ),
    View.MeasureSpec.makeMeasureSpec(
        heightPx,
        View.MeasureSpec.EXACTLY
    )
)

view.layout(
    0,
    0,
    widthPx,
    heightPx
)

val bitmap = Bitmap.createBitmap(
    widthPx,
    heightPx,
    Bitmap.Config.ARGB_8888
)

val canvas = Canvas(bitmap)

view.draw(canvas)
```

The final implementation may require Robolectric-specific adaptations.

---

## 11. Project Detection

The MCP server should ideally be started from the Android repository root.

Example configuration:

```json
{
  "projectRoot": "/home/user/projects/stafftablet",
  "module": "app"
}
```

The server should detect:

- Gradle root;
- Android application/library module;
- `src/main/res`;
- package namespace;
- compiled resources;
- theme resources.

For MVP it is acceptable to require explicit module configuration.

---

## 12. MCP Server Configuration

Suggested config file:

```text
.android-ui-renderer.yaml
```

Example:

```yaml
module: app

defaultDevice:
  widthDp: 1280
  heightDp: 800
  densityDpi: 160
  orientation: landscape

theme: Theme.StaffTablet

renderer:
  sessionTtlMinutes: 30
  maxSessions: 20
  outputDir: .android-ui-renderer
```

---

## 13. Multiple Device Profiles

Later versions should support named device profiles.

Example:

```yaml
devices:
  lenovo_tb_8505x:
    widthDp: 1280
    heightDp: 800
    densityDpi: 213

  samsung_sm_t220:
    widthDp: 1340
    heightDp: 800
    densityDpi: 213
```

Then the agent can call:

```json
{
  "layout": "screen_scanner",
  "device": "samsung_sm_t220"
}
```

This is especially useful for tablet-focused applications.

---

## 14. Layout State Injection

Static XML inflation is not enough for many screens.

A later API should allow simple state manipulation before screenshot capture.

Example:

```json
{
  "layout": "screen_product",
  "state": {
    "price_text": {
      "text": "7 299 ₽"
    },
    "old_price_text": {
      "visibility": "GONE"
    },
    "scan_button": {
      "enabled": true
    }
  }
}
```

The server may implement this through generic View operations.

Possible supported properties:

- `text`;
- `visibility`;
- `enabled`;
- `checked`;
- `selected`;
- image placeholder;
- background placeholder.

This avoids the need to bootstrap the entire application business layer.

---

## 15. CameraX and Other Unsupported Surfaces

Robolectric cannot provide a meaningful real camera preview.

For screens containing CameraX `PreviewView`, the renderer should support placeholders.

Example:

```yaml
placeholders:
  androidx.camera.view.PreviewView:
    background: "#202020"
    label: "CAMERA PREVIEW"
```

Rendered result:

```text
┌─────────────────────────────────────┐
│                                     │
│          CAMERA PREVIEW             │
│                                     │
│             ┌───────┐               │
│             │ VISOR │               │
│             └───────┘               │
│                                     │
│                         [ Scan ]     │
└─────────────────────────────────────┘
```

The purpose is to validate the overlay UI, not camera functionality.

---

## 16. Agent Workflow

Recommended instructions for Codex:

```markdown
## Android UI verification

When changing Android XML/View UI:

1. Implement the requested change.
2. Call the Android UI Renderer MCP.
3. Render the affected screen using the target tablet/device profile.
4. Inspect the screenshot.
5. Inspect important Views using `inspect_view`.
6. Check:
   - alignment;
   - margins;
   - overlap;
   - clipping;
   - elements outside the screen;
   - touch target size;
   - orientation-specific problems.
7. If the rendered result is incorrect, modify the code and repeat.
8. Do not mark the UI task complete until the rendered result has been checked.

Do not rely only on the screenshot when exact geometry matters.
Use the View Tree or `inspect_view` for coordinate verification.
```

---

## 17. Example Agent Session

User request:

```text
Move the scan button 16dp farther from the right edge.
```

Codex changes XML:

```xml
android:layout_marginEnd="16dp"
```

Then:

```text
Codex
  ↓
render_layout(screen_scanner)
```

Result:

```json
{
  "renderId": "42"
}
```

Then:

```text
inspect_view(
  renderId = "42",
  viewId = "scan_button"
)
```

Result:

```json
{
  "bounds": {
    "right": 1264
  }
}
```

For a screen width of `1280px` at mdpi:

```text
1280 - 1264 = 16dp
```

The agent can therefore prove that the requested margin is correct.

---

## 18. Screenshot Storage

Suggested structure:

```text
.android-ui-renderer/
    sessions/
        a7f32c/
            screenshot.png
            view-tree.json
            metadata.json
```

`metadata.json`:

```json
{
  "renderId": "a7f32c",
  "timestamp": "2026-09-10T10:00:00+03:00",
  "layout": "screen_scanner",
  "widthDp": 1280,
  "heightDp": 800,
  "densityDpi": 160,
  "theme": "Theme.StaffTablet"
}
```

Session files are temporary artifacts and should normally be excluded from Git:

```gitignore
.android-ui-renderer/
```

---

## 19. Error Model

Errors should be structured.

Example:

```json
{
  "error": {
    "code": "LAYOUT_NOT_FOUND",
    "message": "Layout resource 'screen_scanner2' was not found",
    "details": {
      "module": "app"
    }
  }
}
```

Suggested error codes:

```text
PROJECT_NOT_FOUND
MODULE_NOT_FOUND
LAYOUT_NOT_FOUND
RESOURCE_ERROR
THEME_NOT_FOUND
INFLATION_FAILED
RENDER_FAILED
VIEW_NOT_FOUND
SESSION_NOT_FOUND
UNSUPPORTED_VIEW
TIMEOUT
```

---

## 20. Performance Goals

Measure the complete edit-to-render path rather than only Robolectric draw time.

At minimum record:

- fingerprint calculation time;
- incremental Gradle build time;
- worker shutdown/startup time;
- render time;
- total `render_layout` latency.

Two paths must be measured separately.

### Unchanged-source warm render

```text
fingerprint unchanged
→ reuse worker
→ render
```

This should be the fastest path.

### Changed-source render

```text
fingerprint changed
→ incremental Gradle build
→ restart worker
→ render
```

This is the primary development-loop metric.

Do not introduce XML-only hot reload or classloader-level optimization until real measurements justify the additional complexity.

## 21. Cache Strategy

Safe MVP caches include:

- parsed MCP/project configuration;
- device profiles;
- source fingerprint metadata;
- render session metadata;
- paths to validated build outputs.

The renderer worker may be reused only while the source fingerprint/generation remains unchanged.

Do not attempt to preserve inflated View instances, Android resource objects, custom View classes, or Robolectric sandboxes across a source generation change.

Gradle's own daemon and incremental build mechanisms should provide the first level of build optimization.

## 22. Source Freshness, Fingerprints, and Generations

The MCP server must explicitly track whether the current worker represents the current source/build state.

### Source fingerprint

Maintain a deterministic `sourceFingerprint` over relevant project inputs.

Relevant categories should include at least:

```text
resources
    res/layout/
    res/values/
    res/drawable/
    res/color/
    res/font/
    other resource directories

kotlin/java
    src/*/java/
    src/*/kotlin/

config
    AndroidManifest.xml
    module Gradle configuration
    root build.gradle / build.gradle.kts
    settings.gradle / settings.gradle.kts
    gradle.properties
    version catalogs
    convention plugins / build-logic
    buildSrc
    Gradle wrapper configuration
    dependency lock files, when used
    other build inputs that can affect classpath, generated code, resources, or variant configuration
```

The exact file set may be configurable.

The fingerprint must cover not only module-local source files but also external Gradle/build inputs capable of changing the effective Android variant, classpath, generated sources, generated resources, or dependency graph.

The fingerprint does not need to understand whether a particular edit affects the requested screen. Correctness is more important than fine-grained optimization in V1.

### Generation

The server maintains a monotonically increasing source generation.

Example:

```text
generation = 17
workerGeneration = 17
```

After any relevant source fingerprint change:

```text
generation = 18
workerGeneration = 17
```

The old worker is stale.

Before rendering:

```text
incremental Gradle build
→ restart worker
→ workerGeneration = 18
→ render
```

Even if the changed Kotlin/XML file does not actually affect the requested screen, V1 may safely create a new generation and restart the worker.

### Change categories

The server should record detected categories for diagnostics:

```json
{
  "changes": [
    "resources",
    "kotlin"
  ]
}
```

These categories SHOULD NOT control fine-grained reload behavior in MVP.

They are primarily useful for:

- observability;
- debugging;
- future optimization;
- agent feedback.

### `render_layout` freshness metadata

A successful render should expose freshness information:

```json
{
  "renderId": "r-143",
  "generation": 18,
  "sourceFingerprint": "sha256:91ac...",
  "changes": [
    "resources",
    "kotlin"
  ],
  "build": {
    "performed": true,
    "status": "success",
    "incremental": true,
    "durationMs": 1240
  },
  "worker": {
    "restarted": true,
    "generation": 18
  }
}
```

A subsequent render without changes may return:

```json
{
  "renderId": "r-144",
  "generation": 18,
  "sourceFingerprint": "sha256:91ac...",
  "changes": [],
  "build": {
    "performed": false
  },
  "worker": {
    "restarted": false,
    "generation": 18
  }
}
```

### Build Coordinator

The MCP server must contain a Build Coordinator responsible for producing the build outputs consumed by the worker.

The agent MUST NOT be allowed to provide arbitrary Gradle commands.

Configuration should define a controlled module/variant and an allowlisted build pipeline.

Conceptual configuration:

```yaml
android:
  module: app
  variant: debug

build:
  strategy: gradle
  allowedTasks:
    - :app:<configured-resource-task>
    - :app:<configured-compile-task>
```

The concrete task names depend on the project and Android Gradle Plugin version and must be validated during implementation.

The important contract is:

> `render_layout` asks the server to ensure a fresh renderable build; it does not ask Codex to construct or execute arbitrary Gradle commands.

If the incremental build fails, the server MUST NOT render using the stale worker. It should return a structured build error.

## 23. Security

The MCP server operates on a local source repository and may execute project code through Robolectric.

Therefore it should be treated as a development tool with repository-level trust.

The server should:

- bind locally only;
- never accept arbitrary Gradle tasks or shell commands from MCP callers;
- execute only the configured/allowlisted build pipeline;
- avoid arbitrary shell execution through MCP parameters;
- restrict file access to the configured repository/output directory;
- sanitize layout/resource names;
- avoid exposing arbitrary filesystem reads.

---

## 24. Proposed Kotlin Project Structure

```text
android-ui-renderer-mcp/
├── build.gradle.kts
├── settings.gradle.kts
├── README.md
├── src/main/kotlin/
│   ├── mcp/
│   │   ├── McpServer.kt
│   │   ├── RenderLayoutTool.kt
│   │   ├── GetViewTreeTool.kt
│   │   ├── InspectViewTool.kt
│   │   └── CheckLayoutTool.kt
│   │
│   ├── build/
│   │   ├── BuildCoordinator.kt
│   │   ├── GradleBuildCoordinator.kt
│   │   ├── SourceFingerprint.kt
│   │   └── BuildGeneration.kt
│   │
│   ├── worker/
│   │   ├── RendererWorkerManager.kt
│   │   ├── RendererWorkerProcess.kt
│   │   └── WorkerClasspath.kt
│   │
│   ├── renderer/
│   │   ├── AndroidRenderer.kt
│   │   ├── RobolectricRuntime.kt
│   │   ├── RenderRequest.kt
│   │   ├── RenderResult.kt
│   │   └── BitmapWriter.kt
│   │
│   ├── hierarchy/
│   │   ├── ViewTreeSerializer.kt
│   │   ├── ViewNode.kt
│   │   └── ViewInspector.kt
│   │
│   ├── diagnostics/
│   │   ├── LayoutDiagnostics.kt
│   │   ├── OverlapRule.kt
│   │   ├── ScreenBoundsRule.kt
│   │   └── TouchTargetRule.kt
│   │
│   └── session/
│       ├── RenderSession.kt
│       └── RenderSessionManager.kt
└── src/test/kotlin/
```

---

## 25. MVP Milestones

### Stage 1 — Proof of Concept

Goal:

Render one XML layout into PNG from the command line.

Requirements:

- hard-coded Android project;
- hard-coded layout;
- Robolectric;
- fixed dimensions;
- PNG output.

Success criterion:

```text
XML → PNG
```

---

### Stage 2 — View Tree

Add hierarchy serialization.

Success criterion:

```text
XML
 ↓
PNG
+
JSON View Tree
```

The JSON must include bounds and IDs.

---

### Stage 3 — MCP

Wrap renderer into MCP tools:

```text
render_layout
get_view_tree
inspect_view
```

Success criterion:

Codex can invoke the renderer directly.

---

### Stage 4 — Persistent MCP + Reloadable Worker

Keep the MCP orchestration process persistent.

Implement:

- source fingerprint;
- source generation;
- Build Coordinator;
- allowlisted incremental Gradle build;
- renderer worker lifecycle;
- full worker restart after any relevant source change.

Measure separately:

```text
unchanged-source render
changed-source build + restart + render
```

Do not implement fine-grained hot reload yet.

---

### Stage 5 — Diagnostics

Add:

```text
inside_screen
no_overlaps
min_touch_target
```

---

### Stage 6 — Device Profiles

Add configuration for target tablets.

Example:

```text
Lenovo TB-8505X
Lenovo TB-8504X
Lenovo TB-8505F
Samsung SM-T220
Samsung SM-X110
Samsung SM-T225
```

---

## 26. MVP Acceptance Criteria

The first useful version is complete when Codex can perform this loop without an emulator:

```text
edit XML
   ↓
MCP render_layout
   ↓
receive PNG
   ↓
inspect screenshot
   ↓
MCP inspect_view
   ↓
verify coordinates
   ↓
edit XML
   ↓
render again
```

Required capabilities:

- render an XML layout;
- output PNG;
- output View hierarchy;
- inspect View by ID;
- configurable screen dimensions;
- persistent MCP orchestration process;
- source fingerprint and generation tracking;
- allowlisted incremental Gradle build after relevant source changes;
- full renderer-worker restart after a source generation change;
- worker reuse when the source generation is unchanged;
- fresh Activity/Context/LayoutInflater/View tree for every render request;
- no UI/application state leakage between render sessions;
- no manual Gradle restart/build orchestration required from Codex.

---

## 27. Future Extensions

Possible later additions:

### Render Activity

```text
render_activity
```

Allows `Activity.onCreate()` to execute and render the resulting content.

### Render Fragment

```text
render_fragment
```

Useful for applications heavily based on fragments.

### Automated screenshots for changed layouts

The agent could detect modified XML files and automatically render affected screens.

### Golden screenshot comparison

```text
compare_render
```

Result:

```json
{
  "changedPixelsPercent": 2.7,
  "diffImage": ".../diff.png"
}
```

### Constraint inspection

Expose resolved ConstraintLayout relations.

Example:

```json
{
  "viewId": "scan_button",
  "constraints": {
    "endToEnd": "parent",
    "bottomToBottom": "parent"
  }
}
```

### Natural-language diagnostics

Example:

```text
The scan button overlaps the camera visor by 12dp.
The right margin is 8dp.
The other screen controls use 16dp margins.
```

This can be generated from deterministic geometry rather than image interpretation alone.

---

## 28. Design Principle

The MCP should not be merely a screenshot generator.

Its real purpose is to expose Android layout rendering as a machine-inspectable environment for an AI agent.

The preferred model is:

```text
              screenshot
                  │
                  ▼
            visual inspection

XML → Robolectric renderer
                  │
                  ▼
              View Tree
                  │
                  ▼
          exact geometry checks
```

The screenshot answers:

> Does the screen look correct?

The View Tree answers:

> Where exactly is every element?

Diagnostics answer:

> What is objectively wrong with the layout?

Combining all three gives Codex a much more reliable UI feedback loop than screenshot inspection alone.

---

## 29. Recommended Initial Implementation

Start deliberately small.

Implement only:

```text
render_layout
get_view_tree
inspect_view
```

with:

- Kotlin/JVM;
- Robolectric;
- one configured Android module;
- XML/View rendering;
- PNG output;
- JSON hierarchy;
- persistent MCP orchestration process;
- reloadable Robolectric worker;
- source fingerprint/generation tracking;
- allowlisted Gradle build pipeline.

Do not begin with:

- golden screenshots;
- CI integration;
- automatic screen discovery;
- Activity navigation;
- animation support;
- accessibility analysis;
- complex state mocking.

The first milestone should prove that Codex can independently execute:

```text
modify → render → inspect → fix → render
```

Once that loop is fast and reliable, the remaining features can be added incrementally.

---

## 30. Final Decision

For an Android XML/View project and an MCP-based Codex workflow, the recommended architecture is:

```text
Codex
  ↓ MCP
Persistent Android UI Renderer MCP
  ↓
Source Fingerprint / Generation
  ↓
┌──────────────────────────────────┐
│ source unchanged                 │
│   → reuse renderer worker        │
│                                  │
│ source changed                   │
│   → allowed incremental Gradle   │
│   → restart renderer worker JVM  │
└──────────────────────────────────┘
  ↓
Robolectric Worker
  ↓
Android module build outputs
  ↓
XML/View
  ├── PNG
  ├── View Tree
  └── Diagnostics
```

The core lifecycle rule for V1 is deliberately simple:

> **No changes → reuse worker → render.**  
> **Any relevant changes → allowed incremental Gradle build → restart worker → render.**

The persistent component is the MCP orchestration server, not an immortal Robolectric runtime.

The worker is bound to a specific source/build generation and must never render when stale.

Even when the same worker process is reused, every `render_layout` request must start from a fresh rendering scope. Process reuse is an optimization for JVM/classpath startup, not permission to preserve Activity, Context, View hierarchy, or application UI state across renders.

This architecture provides:

- no emulator;
- no physical device;
- controlled and reproducible Android builds;
- correct visibility of changed XML/Kotlin/generated resources;
- fast reuse when nothing changed;
- visual feedback;
- deterministic geometry;
- machine-readable UI structure;
- natural integration with Codex through MCP.

Optimization of XML-only reloads, selective classloader invalidation, or partial Robolectric runtime reuse should be considered only after the MVP is correct and performance measurements show that such complexity is justified.

