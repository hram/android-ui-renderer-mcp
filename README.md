# Android UI Renderer MCP

Local stdio MCP server for rendering Android XML/View layouts without an emulator. A coding agent
supplies explicit visual state, then receives a deterministic PNG and View tree.

The server never edits the Android project's source or `build.gradle` files. Its rendering
dependency and temporary probe source belong to a separate renderer worker.

## Current status

The project provides MCP tools `render_layout`, `get_view_tree`, and `inspect_view`; source
fingerprints, build generations, session storage, and a reloadable worker protocol.

The server currently requires a compatible local Robolectric sidecar worker. That worker inflates
the layout, applies `fixture`, measures, draws a PNG, and serializes the View tree. Keeping it
outside the Android project means consumers do **not** add Robolectric as a permanent dependency.

## Requirements

- JDK 17;
- Android project with Gradle wrapper and configured Android SDK;
- compatible, trusted local renderer worker;
- network access on the first Gradle dependency resolution.

## Install

```bash
git clone https://github.com/hram/android-ui-renderer-mcp.git
cd android-ui-renderer-mcp
./gradlew installDist
```

The executable for MCP clients is:

```text
<android-ui-renderer-mcp>/build/install/android-ui-renderer-mcp/bin/android-ui-renderer-mcp
```

After updating the server, run:

```bash
./gradlew test --no-daemon --console=plain
```

## Configure the Android project

Create `.android-ui-renderer.yaml` at the Android repository root:

```yaml
android:
  module: app
  variant: uiDebug

build:
  # The MCP server may execute only these trusted tasks.
  allowedTasks:
    - :app:assembleUiDebug

worker:
  # A local executable; never supplied by an agent tool call.
  command: /absolute/path/to/android-ui-renderer-worker
  args:
    - --stdio

renderer:
  outputDir: .android-ui-renderer
  sessionTtlMinutes: 30
  maxSessions: 20
```

Add the output directory to the Android project's `.gitignore` if needed:

```gitignore
.android-ui-renderer/
```

The configuration may be committed if the worker command is portable for the team; otherwise
keep it local and distribute a template without machine-specific paths.

## Connect from Cursor

Cursor supports local stdio MCP servers in a project file `.cursor/mcp.json` or a global file
`~/.cursor/mcp.json`. Prefer the project file for an Android repository: it is shareable and
`${workspaceFolder}` resolves to the repository root.

Create `<android-project>/.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "android-ui-renderer": {
      "type": "stdio",
      "command": "/absolute/path/to/android-ui-renderer-mcp/build/install/android-ui-renderer-mcp/bin/android-ui-renderer-mcp",
      "args": ["--stdio"],
      "env": {
        "PROJECT_PATH": "${workspaceFolder}"
      }
    }
  }
}
```

Replace the server path with its checkout location. Restart Cursor, or open **Customize → MCPs**,
and verify that `android-ui-renderer` is connected. Its three tools become available to Cursor
Agent. Cursor merges global and project configurations; a project entry takes precedence when
both use the same name. For a global installation set `PROJECT_PATH` to an absolute Android
project path. See [Cursor MCP documentation](https://prod.cursor.com/docs/mcp) for current
client configuration and approval settings.

## Connect from another MCP client

The server is a normal stdio process:

```bash
PROJECT_PATH=/absolute/path/to/android-project \
  /absolute/path/to/android-ui-renderer-mcp/build/install/android-ui-renderer-mcp/bin/android-ui-renderer-mcp \
  --stdio
```

It writes protocol JSON only to stdout and diagnostics only to stderr.

## `render_layout`: explicit state and size

`render_layout` is a renderer, not an application-state inference engine. The agent supplies the
visual state in `fixture`; unspecified properties retain their XML/View values. The renderer does
not invent text, make `gone` Views visible, or treat `tools:*` attributes as runtime state.

```json
{
  "layout": "item_cart_product",
  "widthPx": 722,
  "densityDpi": 212,
  "background": "#FFFFFF",
  "fixture": {
    "@id/productName": { "text": "Пуф Руби" },
    "@id/productArticle": { "text": "Арт. 80064525" },
    "@id/productCheckbox": { "checked": true },
    "@id/quantityText": { "text": "1" },
    "@id/unitPriceText": { "text": "1 599 ₽/шт", "visibility": "visible" },
    "@id/amountFinalText": { "text": "1 599 ₽", "visibility": "visible" },
    "@id/originalPriceText": { "text": "1 999 ₽", "visibility": "visible", "strikeThrough": true },
    "@id/discountBadge": { "text": "-20%", "visibility": "visible" },
    "@id/remainsBadge": { "text": "Остаток: 1357 шт", "visibility": "visible" },
    "@id/mandatory": { "text": "Есть обязательные товары", "visibility": "visible" },
    "@id/productImage": { "image": { "type": "color", "value": "#E8E8E8" } }
  }
}
```

Fixture keys are `@id/name`, `@+id/name`, or `name`. Supported properties are:

- `text`, `hint`, `contentDescription`;
- `visibility`: `visible`, `invisible`, or `gone`;
- `enabled`, `selected`, `checked`;
- `backgroundColor`, `textColor`, `textSizeSp`, `strikeThrough`;
- `image`: `{ "type": "color" | "drawable_resource" | "local_path", "value": "..." }`.

Workers must report a diagnostic when an ID is absent or a property is unsupported by that View
class.

## Reproduce a device size

Use `widthDp`/`heightDp` for logical container bounds, or `widthPx`/`heightPx` for exact Layout
Inspector bounds. `Px` and `Dp` values for one dimension are mutually exclusive. `densityDpi`
controls Android dp/sp scaling independently of physical measure size.

For the problematic tablet, Layout Inspector reported a `722px`-wide card at `212dpi`:

```json
{
  "layout": "item_cart_product",
  "widthPx": 722,
  "densityDpi": 212
}
```

The worker must set Robolectric resource density before inflation and measure the root with the
exact requested pixel bound. This reproduces rounding and available-width errors instead of
rendering an arbitrary desktop-sized card.

## Worker protocol

A worker is long-lived for one build generation and communicates in JSON Lines:

```json
{ "type": "initialize", "generation": 7 }
{ "type": "render", "request": { "layout": "item_cart_product", "widthPx": 722, "densityDpi": 212 } }
```

It answers `{ "type": "ready" }`, then:

```json
{
  "type": "rendered",
  "result": {
    "widthPx": 722,
    "heightPx": 221,
    "screenshotPath": "/absolute/path/to/render.png",
    "viewTree": { "className": "android.widget.LinearLayout", "bounds": { "left": 0, "top": 0, "right": 722, "bottom": 221 } },
    "warnings": []
  }
}
```

Reserve stdout for JSON protocol; send all worker logging to stderr.

## Troubleshooting

- **`CONFIG_NOT_FOUND`** — create `.android-ui-renderer.yaml` in `PROJECT_PATH`.
- **`PROJECT_NOT_FOUND`** — the Android `gradlew` was not found; check Cursor `PROJECT_PATH`.
- **`BUILD_FAILED`** — inspect the returned diagnostics; only allowlisted Gradle tasks run.
- **Connected but no render** — confirm the worker writes only JSON Lines to stdout.
- **Wrong layout size** — use Layout Inspector `widthPx` and device `densityDpi`, rather than a
  screenshot width at an unknown scale.
