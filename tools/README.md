# Отладка страниц в WebView приложения

Скрипты выполняют JavaScript на странице, открытой в приложении, через протокол отладки Chrome (CDP).
Нужны Node.js 22+ (встроенный `WebSocket`) и **debug-сборка** приложения — в ней включена отладка WebView.

## Подключение

```bash
# собрать и поставить debug-сборку (с постоянным ключом — встанет поверх release, cookies сохранятся)
set -a && source ~/Documents/FoxTV-signing-key/signing.env && set +a
FOXTV_DEBUG_RELEASE_KEY=1 ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# пробросить отладочный порт WebView (после каждого перезапуска приложения — заново)
adb forward --remove-all
adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof net.hdrezka.tv | tr -d '\r')
```

## Использование

```bash
node tools/cdp.mjs "location.href"                          # выполнить JS на странице сайта
node tools/cdp.mjs "location.href='https://serialy-fox-hdrezka.net/serial'"
node tools/cdp.mjs "__foxplayer.state()"                    # состояние плеера (обёртка приложения)
node tools/cdpall.mjs "location.href"                       # то же во всех фреймах, включая чужие iframe
```

`cdp.mjs` выбирает вкладку сайта (а не временные WebView всплывающих окон).
После проверок с воспроизведением выключайте видео: `node tools/cdp.mjs "__foxplayer.exitFs()"`.
