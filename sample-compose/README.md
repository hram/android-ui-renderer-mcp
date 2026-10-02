# Compose demo project

The Compose counterpart of [`../sample`](../sample): the same small library UI, the same seven
rendering situations, and one explicit `render_compose` call per screen. It is an ordinary Android
application as well — launch `MainActivity` to browse the shelf — but the renderer invokes the
top-level composable named in each request directly.

```text
sample-compose/
├── app/                 # Android application, module :app
├── render-requests/     # one render_compose request per scenario
├── renders/<scenario>/  # PNG, semantics tree, request and replay after render.py
└── render.py            # runs all requests through the locally installed MCP server
```

## Equivalent scenarios

| Request | Compose entry point | XML counterpart |
| --- | --- | --- |
| `01-item-book` | `ComposeBookItem` | one selected book row |
| `02-item-book-long-title-font-1.3` | `ComposeBookItem` | long title at `fontScale: 1.3` |
| `03-fragment-book-list` | `ComposeBookList` | book list |
| `04-fragment-library-master-detail` | `ComposeLibrary` | master-detail library |
| `05-activity-toolbar-library` | `ComposeShelfScreen` | complete toolbar screen |
| `06-activity-toolbar-library-loading` | `ComposeShelfScreen` | complete screen with loading overlay |
| `07-activity-toolbar-library-ru-night` | `ComposeShelfScreen` | Russian dark screen |

The UI deliberately retains the XML sample's blue shelf palette, book covers, availability chips,
400 dp master pane, toolbar, details panel and loading card. `AppTheme` is a project-level wrapper,
so the renderer discovers and applies the app theme just as it would for a real Compose project.

## Render it

```bash
./gradlew installDist                  # from the repository root
echo "sdk.dir=$HOME/Android/Sdk" > sample-compose/local.properties
python3 sample-compose/render.py       # or one request: python3 sample-compose/render.py 05
```

The script writes each screenshot and semantics-based `view-tree.json` to `renders/<scenario>/`.
Those files are intentionally generated output and can be recreated at any time.
