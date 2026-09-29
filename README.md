# Android UI Renderer MCP

[Русская версия](README.ru.md)

Local MCP for an agent developing Android XML interfaces. The agent passes a layout, a visual
fixture, and the target device size; the MCP returns a screenshot and the View tree.

The Android project does not receive a permanent Robolectric dependency or test source.

To see every capability on an open project, use the [demo app](sample/README.md): a list row, a
fragment with a RecyclerView, a master-detail layout and a whole activity with a toolbar, each with
a ready render request and its PNG and View tree.

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

TextViews also carry `textLayout`: `textSizePx` (after density and font scaling), `maxLines`,
`lineCount`, `ellipsisCount` and `truncated`. A `maxLines="1"` title that ellipsizes keeps its bounds
and looks plausible in the PNG, so check `truncated` instead of relying on bounds alone:

```json
"textLayout": { "textSizePx": 31.0, "maxLines": 1, "lineCount": 1, "ellipsisCount": 27, "truncated": true }
```

Font scaling follows Android 14 (API 34) non-linear curves: at `fontScale: 1.3` a 12sp text grows
1.3×, while 24sp grows only 1.1×. Compare `textSizePx` across configs rather than assuming a linear factor.

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

## Reproduce a render

Every sidecar run directory contains the rendered `render.png`, `view-tree.json`, and two
reproducibility artifacts:

- `request.json` — the normalized `RenderRequest` used by the worker;
- `replay.json` — the MCP tool name, its arguments, and the project module, variant, and test task.

`render_layout` and `render_target` responses expose these locations as `requestPath` and
`replayPath`. To repeat a render, call the `tool` from `replay.json` with its `arguments` against
the same project revision and renderer version. The manifest may include absolute local-image paths
when such fixture images were explicitly supplied.

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
visibility, enabled/selected/checked state, text and background colors, a background drawable,
text size, strike-through, and an image from an absolute local path, an app drawable resource, or a solid color. Local images
must be regular files no larger than 20 MiB; use `@drawable/name` (or `name`) for an app drawable.

## Render RecyclerView rows

`recyclerViews` supplies deterministic, local adapter data. The renderer inflates the real
`itemLayout` for each row and applies that row's fixture to views inside the row. It does not run
fragment code, DI, or network calls. A `LinearLayoutManager` is installed automatically.

```json
{
  "layout": "fragment_catalog_groups_split",
  "widthDp": 1280,
  "heightDp": 800,
  "recyclerViews": {
    "@id/groupsRecyclerView": {
      "itemLayout": "item_catalog_group",
      "items": [
        {
          "@id/numberBadge": { "text": "31" },
          "@id/name": { "text": "Bedrooms" },
          "@id/nomenclatureGroupsBadge": { "text": "12 NG" },
          "@id/root": {
            "selected": true,
            "backgroundDrawable": "@drawable/bg_catalog_group_row_selected"
          }
        }
      ]
    },
    "@id/subgroupsRecyclerView": {
      "itemLayout": "item_catalog_subgroup",
      "items": [
        { "@id/number": { "text": "301" }, "@id/name": { "text": "Beds" } }
      ]
    }
  }
}
```

Use `orientation: "horizontal"` for horizontal lists; the default is vertical. `recyclerViews`
accepts at most 20 lists, 200 rows per list, and 500 rows in total.

## Render overlays and loading indicators

Use `overlays` to layer XML layouts above either a direct layout render or an activity-fragment
target. Each overlay has its own fixture scope. Indeterminate `ProgressBar` and
`CircularProgressIndicator` controls are rendered as a deterministic static ring in PNG output.

```json
{
  "overlays": [{
    "layout": "view_blocking_progress_overlay",
    "fixture": {
      "@id/blocking_progress_overlay_root": { "visibility": "visible" },
      "@id/blocking_progress_message": { "visibility": "visible", "text": "Please wait…" }
    }
  }]
}
```

## Render an activity host with a fragment

Use `render_target` when a fragment must be rendered inside the XML shell of its activity. The
renderer inflates the activity layout, inserts the fragment layout into `containerId`, then applies
the supplied fixtures and RecyclerView rows. It deliberately does not instantiate production
Fragment classes or execute DI, navigation, and network calls.

```json
{
  "target": {
    "kind": "activity_fragment",
    "activityLayout": "activity_main",
    "containerId": "fragmentContainer",
    "fragmentLayout": "fragment_catalog_groups_split"
  },
  "widthPx": 1280,
  "heightPx": 728,
  "densityDpi": 240,
  "orientation": "landscape"
}
```

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

The probe is a JUnit 4 test. For the render run only, MCP adds Robolectric and — when neither
`testImplementation` nor the variant's test configuration declares `junit:junit` — JUnit 4.13.2. A
JUnit version the project already declares is left as is.

The probe test is intentionally executed for every render so that a fresh PNG and View tree are
always produced. Gradle still reuses unchanged compilation and resource outputs. Check `timings`
on the target project before deciding whether a persistent renderer worker is necessary.
