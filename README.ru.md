# Android UI Renderer MCP

[English version](README.md)

Локальный MCP-сервер для AI-агентов, разрабатывающих Android-интерфейсы на XML. Агент передаёт layout, тестовые данные и размеры целевого устройства, а сервер возвращает PNG-скриншот и дерево View.

Проект Android не получает постоянную зависимость Robolectric и дополнительные тестовые исходники.

## Цикл геометрической проверки

`render_layout` выполняет inflate, применяет fixture, измеряет и раскладывает View, сериализует
дерево и рисует PNG с одного и того же экземпляра View hierarchy. В ответе есть `renderId`,
`screenshotPath`, `viewTree` и `viewTreePath`.

Для точной проверки без повторного render используйте следующие инструменты:

```text
render_layout
  → inspect_view(renderId, "scan_button")
  → inspect_view(renderId, "viewfinder")
  → сравнить их границы в пикселях
```

`get_view_tree(renderId)` возвращает полное дерево. `inspect_view(renderId, viewId)` возвращает
один View по Android ID. Узел содержит resource name, класс, текст, состояние, padding, margins и
абсолютные границы в пикселях относительно корня рендера.

Каждый render также возвращает метрики:

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

`gradleMs` — полное время временной Gradle/Robolectric test-задачи. `renderMs` — время внутри
probe от inflate до PNG и View Tree; оно входит и в `gradleMs`, и в `totalMs`.

## Подключение из Cursor

Один раз соберите MCP:

```bash
git clone https://github.com/hram/android-ui-renderer-mcp.git
cd android-ui-renderer-mcp
./gradlew installDist
```

В Android-репозитории создайте `.cursor/mcp.json`:

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

Замените путь на путь к вашему клону, перезапустите Cursor, затем откройте **Customize → MCPs**.
Настройка MCP для проекта описана в [документации Cursor](https://prod.cursor.com/docs/mcp).

## Рендер layout

Агент явно передаёт реальные или тестовые значения. MCP не пытается угадать значения по XML-атрибутам `tools:*` и не придумывает бизнес-данные.

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

Ключи fixture — ID View. Доступны переопределения текста, hint, content description, видимости, состояний enabled/selected/checked, цвета текста и фона, размера текста, зачёркивания, а также изображения из абсолютного локального пути, drawable-ресурса приложения или сплошного цвета. Локальный файл должен существовать и быть не больше 20 МиБ; для drawable используйте `@drawable/name` или `name`.

## Воспроизведение целевого устройства

Если Layout Inspector предоставляет физические границы, используйте `widthPx`/`heightPx` вместе с `densityDpi`. Для логических границ используйте `widthDp`/`heightDp`; не передавайте для одного измерения оба варианта.

Для проверенной карточки планшета:

```json
{
  "layout": "item_cart_product",
  "widthPx": 722,
  "densityDpi": 212
}
```

## Android-варианты

По умолчанию MCP использует `:app` и `debug`. Для проекта с product flavor передайте вариант в окружении MCP, например `ANDROID_UI_RENDERER_VARIANT=uiDebug`. Добавлять файлы в Android-проект не требуется: MCP создаёт временный probe в `.android-ui-renderer/`.

Временный probe-test намеренно запускается при каждом render, поэтому PNG и View Tree всегда
свежие. При этом Gradle переиспользует неизменившиеся результаты компиляции и обработки ресурсов.
Перед решением о persistent worker посмотрите `timings` на целевом проекте.
