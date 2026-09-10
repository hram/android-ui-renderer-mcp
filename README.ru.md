# Android UI Renderer MCP

[English version](README.md)

Локальный MCP-сервер для AI-агентов, разрабатывающих Android-интерфейсы на XML. Агент передаёт layout, тестовые данные и размеры целевого устройства, а сервер возвращает PNG-скриншот и дерево View.

Проект Android не получает постоянную зависимость Robolectric и дополнительные тестовые исходники.

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

Ключи fixture — ID View. Доступны переопределения текста, hint, content description, видимости, состояний enabled/selected/checked, цвета текста и фона, размера текста, зачёркивания, а также изображения из локального файла, drawable-ресурса или сплошного цвета.

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
