# Reddit post — r/M5Stack

## Title

I built an Android app that mirrors the real Bruce menu on an M5StickC Plus 2 (live menu, IR, files, screen) — open source

## Body

Hey everyone,

I have an M5StickC Plus 2 running Bruce and kept wishing I could drive it from my phone
without squinting at the 135×240 screen. So I built **StickCommander**: a Material 3
Android app plus a small bridge compiled into Bruce 1.16.1.

It is not a pixel-streaming hack. The bridge exposes Bruce's own state over a line-based
ASCII protocol (USB-OTG serial or BLE), so the app talks to the firmware, not to a picture
of it.

**What it does**

- **Live menu** — reads the actual `options` vector (title, labels, disabled items, current
  selection), refreshes every second, and tapping an item sets `forceMenuOption` so the real
  menu opens on the Stick. Back/Main buttons work too.
- **Screen** — text mirror of the UI on the phone, plus the other direction: send text and
  the Stick draws it on its own TFT.
- **IR blaster** — NEC / Samsung / Sony (12, 15, 20 bit) with hex address/command and
  presets. Uses Bruce's existing encoders, so timings are unchanged.
- **Files** — LittleFS browser with text preview, chunked upload (256-byte chunks, 128 KB
  limit), mkdir, rename, delete.
- Plus the basics: D-pad and M5/A/B/PWR press-and-hold, battery telemetry from the AXP192,
  filtered logs, raw terminal and phone-side scripts.

**How it works**

A `stickcommander.cpp` bridge is compiled into Bruce. Lines like `CMD:MENU:LIST`,
`CMD:SCREEN:TEXT:<base64>`, `CMD:IR:NEC:04:08`, `CMD:FS:LIST:<base64 path>` go in;
`MENU:ITEM:...`, `FS:ENTRY:...`, `STATUS:BAT:...` come back. That is the whole trick.

**Status**

Tested on real hardware over COM3: PONG, battery, live menu open/back, screen text,
`OK:ir:NEC`, and LittleFS list/mkdir/upload/read/delete all pass. The firmware image and
the APK are in the release.

**Links**

- Repo: https://github.com/121kirillmorti/stickcommander
- Release (APK + firmware + source): https://github.com/121kirillmorti/stickcommander/releases/latest
- Site: https://121kirillmorti.github.io/stickcommander/

Firmware is AGPL-3.0 because it is built on Bruce; the bridge source and a build recipe
are included. Feedback and feature ideas are welcome — especially from people with other
Stick variants (Plus, S3) who want to test.

---

# Вариант на русском (r/M5Stack или r/ru_esp32)

## Заголовок

Сделал Android-приложение, которое зеркалит настоящее меню Bruce на M5StickC Plus 2 — открытый исходный код

## Текст

Привет! У меня M5StickC Plus 2 на Bruce, и постоянно хотелось управлять им с телефона,
не вглядываясь в экран 135×240. Сделал **StickCommander**: Android-приложение на Material 3
плюс небольшой мост, вкомпилированный в Bruce 1.16.1.

Это не стриминг пикселей. Мост отдаёт состояние самой прошивки по построчному ASCII-протоколу
(USB-OTG serial или BLE), поэтому приложение работает с железом, а не с картинкой.

**Что умеет**

- **Живое меню** — читает реальный вектор `options` (заголовок, пункты, недоступные,
  текущее выделение), обновляется раз в секунду; тап по пункту выставляет
  `forceMenuOption`, и на Stick открывается настоящее меню.
- **Экран** — текстовое зеркало интерфейса на телефоне и обратно: отправляешь текст —
  Stick рисует его на своём TFT.
- **ИК-передатчик** — NEC / Samsung / Sony, hex-адрес и команда, пресеты. Используются
  штатные кодировщики Bruce, тайминги не меняются.
- **Файлы** — LittleFS: просмотр, предпросмотр текста, загрузка чанками по 256 байт
  (до 128 КБ), создание папок, переименование, удаление.
- Плюс пульт (D-pad и M5/A/B/PWR с удержанием), телеметрия AXP192, логи с фильтрами,
  терминал и скрипты.

**Ссылки**

- Репозиторий: https://github.com/121kirillmorti/stickcommander
- Релиз (APK + прошивка + исходники): https://github.com/121kirillmorti/stickcommander/releases/latest
- Сайт: https://121kirillmorti.github.io/stickcommander/ru/

Прошивка под AGPL-3.0, потому что это Bruce; исходники моста и инструкция по сборке
внутри. Буду рад фидбеку и идеям.
