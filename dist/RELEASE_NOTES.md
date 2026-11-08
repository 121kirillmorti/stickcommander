## StickCommander 1.0.0

Android app + Bruce 1.16.1 firmware for the **M5StickC Plus 2**. Control the Stick over
USB-OTG or Bluetooth LE and use it as a remote display, menu navigator, IR blaster and
file server.

### Downloads

| Asset | Description |
| --- | --- |
| `StickCommander-debug.apk` | Debug-signed APK, Android 8.0+ (`com.stickcommander.debug`). |
| `Bruce-m5stack-cplus2.bin` | Merged firmware image, write at `0x0`. Bruce 1.16.1 + StickCommander bridge. |
| `StickCommander-1.0.0-source.zip` | Full repository snapshot (app + firmware integration + site). |
| `stickcommander-firmware-integration.zip` | Only the files that differ from upstream Bruce 1.16.1. |

### Flash

```bash
esptool --port COM3 --baud 460800 write-flash 0x0 Bruce-m5stack-cplus2.bin
adb install -r StickCommander-debug.apk
```

### What's inside

- **Live menu** — mirrors the real Bruce option list and opens items remotely.
- **Screen** — text mirror of the UI plus phone-to-Stick text drawing on the TFT.
- **IR blaster** — NEC / Samsung / Sony, hex address+command, presets.
- **Files** — LittleFS browse, preview, chunked upload (128 KB), mkdir, rename, delete.
- **Remote / Dashboard / Terminal / Scripts** — D-pad and hardware buttons, battery and
  link telemetry, filtered logs and raw commands, phone-side scripts.

### Verified

Tested on hardware over COM3: `PONG`, battery telemetry, live menu open/back, screen text,
`OK:ir:NEC`, and LittleFS list / mkdir / upload / read / delete — all pass
(`firmware/integration/tools_test.py`).

### Русский

Патч прошивки Bruce 1.16.1 и Android-приложение для M5StickC Plus 2: живое меню, зеркало
экрана и рисование текста на Stick, ИК-передатчик (NEC/Samsung/Sony), менеджер файлов
LittleFS с загрузкой своих файлов, пульт, терминал и скрипты.

Прошивка: `Bruce-m5stack-cplus2.bin`, писать с адреса `0x0`.
Приложение: `StickCommander-debug.apk`, устанавливается через `adb install -r`.

Исходники прошивки и приложения — в архивах релиза. Лицензия AGPL-3.0 (на основе Bruce).
