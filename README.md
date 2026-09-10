# Android UI Renderer MCP

[Русская версия](README.ru.md)

Local MCP for an agent developing Android XML interfaces. The agent passes a layout, a visual
fixture, and the target device size; the MCP returns a screenshot and the View tree.

The Android project does not receive a permanent Robolectric dependency or test source.

## Geometry feedback loop

`render_layout` inflates, applies the fixture, measures, lays out, serializes the View tree, and
draws the PNG from the same View hierarchy. Its result contains `renderId`, `screenshotPath`,
`viewTree`, and `viewTreePath`.

Use the follow-up tools for exact checks without another render:

```text
render_layout
  → inspect_view(renderId, "scan_button")
  → inspect_view(renderId, "viewfinder")
  → compare their bounds in pixels
```

`get_view_tree(renderId)` returns the complete hierarchy. `inspect_view(renderId, viewId)` returns
one node by Android ID. Nodes include resource name, class, text, state, padding, margins and
absolute bounds in pixels relative to the rendered root.

Every render also returns timing metadata:

```json
{
  "timings": {
    "fingerprintMs": 35,
    "gradleMs": 840,
    "renderMs": 410,
    "totalMs": 1065
  }
}
```

`gradleMs` is the wall time of the temporary Gradle/Robolectric test task. `renderMs` is the
inflate-to-PNG/View-tree portion inside that probe; it is included in `gradleMs` and `totalMs`.

## Production inflation context

The sidecar inflates from a themed Android `Activity`, not the application context. Before the
Activity lifecycle and inflation it applies the requested resource configuration:

- `theme`: an app style name or `@style/name`; omitted means the application manifest theme;
- `nightMode`: selects night or not-night resources;
- `fontScale`: `0.5` through `3.0`;
- `locale`: a BCP 47 tag such as `ru-RU`;
- `orientation`: `portrait` or `landscape`, including layout/resource qualifiers.

For example:

```json
{
  "layout": "screen_scanner",
  "theme": "@style/AppTheme",
  "nightMode": true,
  "fontScale": 1.3,
  "locale": "ru-RU",
  "orientation": "landscape"
}
```

## Connect from Cursor

Build the MCP once:

```bash
git clone https://github.com/hram/android-ui-renderer-mcp.git
cd android-ui-renderer-mcp
./gradlew installDist
```

In the Android repository create `.cursor/mcp.json`:

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

Replace the path with your checkout location, restart Cursor, then check **Customize → MCPs**.
Cursor documents project MCP configuration in its [MCP guide](https://prod.cursor.com/docs/mcp).

## Render a layout

The agent supplies real or test values explicitly. MCP does not guess values from `tools:*` XML
attributes and does not invent business data.

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
    "@id/amountFinalText": { "text": "1 599 ₽", "visibility": "visible" },
    "@id/mandatory": { "text": "Есть обязательные товары", "visibility": "visible" }
  }
}
```

Fixture keys are View IDs. The available overrides are text, hint, content description,
visibility, enabled/selected/checked state, text and background colors, text size, strike-through,
and an image from an absolute local path, an app drawable resource, or a solid color. Local images
must be regular files no larger than 20 MiB; use `@drawable/name` (or `name`) for an app drawable.

## Reproduce the target device

Use `widthPx`/`heightPx` when Layout Inspector gives physical bounds, together with
`densityDpi`. For logical bounds use `widthDp`/`heightDp` instead; do not pass both units for one
dimension.

For the inspected tablet card:

```json
{
  "layout": "item_cart_product",
  "widthPx": 722,
  "densityDpi": 212
}
```

## Android variants

MCP uses `:app` and `debug` by default. For a project with product flavors, pass the variant in
the MCP environment, for example `ANDROID_UI_RENDERER_VARIANT=uiDebug`. No file needs to be added
to the Android project; MCP creates its temporary probe under `.android-ui-renderer/`.

The probe test is intentionally executed for every render so that a fresh PNG and View tree are
always produced. Gradle still reuses unchanged compilation and resource outputs. Check `timings`
on the target project before deciding whether a persistent renderer worker is necessary.
