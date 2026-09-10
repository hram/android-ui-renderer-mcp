# Android UI Renderer MCP

Local MCP for an agent developing Android XML interfaces. The agent passes a layout, a visual
fixture, and the target device size; the MCP returns a screenshot and the View tree.

The Android project does not receive a permanent Robolectric dependency or test source.

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
and an image from a local file, drawable resource, or a solid color.

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
