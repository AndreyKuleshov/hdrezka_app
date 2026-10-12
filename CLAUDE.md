# Fox TV — заметки для Claude

Android TV приложение: обёртка над сайтом serialy-fox-hdrezka.net в WebView, удобная для пульта.
Видео играет плеер самого сайта; приложение добавляет навигацию стрелками и TV-управление плеером.
Подробности для пользователя — в README.md, отладка страниц — в tools/README.md, история решений — в `git log`.

## Устройство

- `MainActivity.kt` — WebView, порядок обработки кнопок пульта, «Назад», жизненный цикл.
  Порядок в `dispatchKeyEvent`: плеер (`PlayerController.handleKey`) → долгое OK (панель) → навигация стрелками → медиакнопки.
- `SpatialNav.kt` — внедряемый JS `window.__foxnav`: обход кликабельных элементов, рамка `.__foxfocus`,
  выпадающие списки/меню как «всплывающие окна» (`POPUP`), исключения (`SKIP`, `SKIP_AREAS`), перенос по рядам (`wrap`),
  объединение карточек (`mergeCards`), плавная прокрутка (`reveal`).
- `PlayerController.kt` — обёртка над PlayerJS (`window.__foxplayer`, API `pljssglobal[0].api(...)`):
  режимы PAGE / FULLSCREEN / CONTROLS, свой «весь экран» через CSS-класс, контролы, перемотка по полосе,
  качество, реклама, автопереход к следующей серии. `REORDER_JS` перестраивает страницу сериала и скрывает «Поделиться».
- `MenuPanel.kt` — боковая панель разделов (пункты из меню сайта). `CursorLayout.kt` — запасной экранный курсор,
  `tapAt()` — имитация касания. `JsInstall.kt` — внедрение скриптов один раз на документ (`evalInstalled`).

## Сборка

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
set -a && source ~/Documents/FoxTV-signing-key/signing.env && set +a   # постоянный ключ подписи
./gradlew assembleRelease
FOXTV_DEBUG_RELEASE_KEY=1 ./gradlew assembleDebug   # debug (отладка WebView) поверх release, cookies сохраняются
```

- Ключ подписи: `~/Documents/FoxTV-signing-key/` (`release.jks`, `signing.env`). Не коммитить, не выводить пароль.
  В CI ключ берётся из GitHub Secrets; релиз по тегу без ключа намеренно падает.
- `local.properties` (`sdk.dir=…`) не в git — создать локально.

## Проверка

- Эмулятор Android TV: AVD `FoxTV_TV` (API 34, arm64, 1920×1080). Клавиатура Mac = стрелки; Enter с клавиатуры
  считается полноценной клавиатурой и отдаётся странице — для OK использовать `adb shell input keyevent DPAD_CENTER`.
- Состояние страницы смотреть через `node tools/cdp.mjs "<js>"` (только debug-сборка; после перезапуска приложения
  заново `adb forward` на `webview_devtools_remote_<pid>`).
- Сайт может показать проверку «Confirm you're human» — её проходит пользователь, не я.
- Потоки некоторых серий в эмуляторе зависают (сетевые таймауты эмулятора) — это не баг приложения.
- **После каждой проверки с воспроизведением выключать видео** (`node tools/cdp.mjs "__foxplayer.exitFs()"`).

## Особенности сайта (на них завязан код)

- Плеер — PlayerJS: `#player-container`, `pljssglobal[0].api('play'|'pause'|'seek'|'time'|'duration'|'quality'|'qualities')`.
  Смена качества сбрасывает позицию — `setQuality` её восстанавливает. Конец серии ловится хуком `window.PlayerjsEvents`.
- Реклама — VAST внутри PlayerJS; распознаётся по надписям «Реклама N/M», «Пропустить через N», «Пропустить».
- Серии: `.episodes_list a[data-episode]`, сезоны: `.seasons_list a[data-season]`, текущая — класс `active`.
- Фильтры — SlimSelect (`.ss-main`, `.ss-content`, `select.slim.close()`). Меню сайта — `#main_menu`, `body.menu_opened`.
- «Смотреть онлайн» — `button.to_view` (перехватывается, сразу запускает плеер).

## Правила

- Коммиты от `Andrey Kuleshov <greenolls180885@yandex.ru>`, пушить в `main`; CI (`.github/workflows/build.yml`) собирает APK.
- Релиз: поднять `versionCode`/`versionName` в `app/build.gradle.kts`, тег `vX.Y` → GitHub Release с `FoxTV-vX.Y.apk`.
- Интерфейс приложения — на английском; комментарии в коде и README — на русском.
- minSdk 21: не использовать атрибуты только с API 26 (`paddingHorizontal`, `layout_marginHorizontal` и т.п.).
- Не извлекать прямые ссылки на видеопотоки и не делать свой плеер в обход сайта — только управление плеером сайта.
- Общение с пользователем — по-русски, кратко.
