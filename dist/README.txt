StickCommander — готовые файлы
==============================

APK
---
dist\StickCommander-debug.apk
Размер: 17 230 440 байт
SHA-256: 597014e7f747cc100a7ff86c3e718afda5e67523739200ecbbf1b46e77414b09

Установка на телефон (включён USB debugging):
  adb install -r dist\StickCommander-debug.apk

APK подписан debug-ключом, package: com.stickcommander.debug.

Оригинал сборки также лежит в:
  app\build\outputs\apk\debug\app-debug.apk

Что нового в приложении
-----------------------
Вкладка Tools (5-я кнопка внизу) содержит четыре инструмента:

  1. Live menu
     Зеркало реального меню Stick (заголовок, пункты, текущее выделение).
     Обновляется каждую секунду. Нажатие на пункт открывает его на Stick.
     Кнопки Back / Main дублируют Esc и возврат в главное меню.

  2. Screen
     Показывает текстовое зеркало интерфейса Stick (135x240, моноширинный
     шрифт, подсветка выделенного пункта).
     Блок «Phone → Stick display» рисует произвольный текст прямо на TFT
     Stick через CMD:SCREEN:TEXT. Есть кнопка Clear.

  3. IR
     Stick работает как внешний ИК-передатчик для телефона.
     Протоколы: NEC, SAMSUNG, SONY (12/15/20 бит).
     Поля Address/Command — по две hex-цифры.
     Пресеты NEC (адрес 04): Power 08, Vol+ 02, Vol- 03, Mute 0C.

  4. Files
     Файловый менеджер LittleFS на Stick:
       - просмотр каталогов и переход по папкам;
       - предпросмотр текстовых файлов (до 48 КБ) с копированием;
       - загрузка своего файла с телефона (до 128 КБ, чанки по 256 байт
         с прогресс-баром);
       - создание папок, удаление файлов и папок, переименование.

Прошивка Stick (Bruce 1.16.1 + StickCommander)
----------------------------------------------
Исходники:
  firmware\bruce-src\firmware-1.16.1

Изменённые/добавленные файлы:
  src\core\stickcommander.h
  src\core\stickcommander.cpp
  src\core\serialcmds.cpp
  src\modules\ble_api\services\BLESerialService.cpp
  src\main.cpp
  patch_fastled_fp.py
  platformio.ini

Собранный и уже залитый merged-образ:
  firmware\bruce-build\Bruce-m5stack-cplus2.bin
  Размер: 3 921 248 байт
  SHA-256: a696f7ae8ef3fa09a90732841b8b8862c8df5c9c0cf24344c4d462f63a4340f7

Порт: COM3 (CH9102, ESP32-PICO-V3-02, 8 MB flash)
Скорость: 115200 8N1

Повторная заливка (образ пишется с адреса 0x0):
  esptool.exe --port COM3 --baud 460800 write-flash 0x0 firmware\bruce-build\Bruce-m5stack-cplus2.bin

Проверено по COM3 (автотест firmware\tools_test.py):
  MENU:TITLE / MENU:ITEM / MENU:END             PASS
  MENU:OPEN:0 -> открылось реальное меню WiFi    PASS
  MENU:BACK                                      PASS
  CMD:SCREEN:TEXT -> OK:screen:text              PASS
  CMD:IR:NEC:04:08 -> OK:ir:NEC                  PASS
  FS:LIST / FS:MKDIR                             PASS
  FS:BEGIN + CHUNK + END -> FS:OK:upload         PASS
  FS:READ -> FS:FILE + FS:DATA                   PASS
  FS:DELETE -> FS:OK:delete                      PASS

Резервная копия исходной прошивки (8 МБ):
  firmware\backup\m5stickc_plus2_stock_8MB.bin

Восстановление исходной прошивки:
  esptool.exe --port COM3 --baud 460800 write-flash 0x0 firmware\backup\m5stickc_plus2_stock_8MB.bin

USB
---
Работает сразу: приложение подключается к Stick по USB-OTG/COM3.
В приложении выберите USB-OTG → Connect via USB-OTG.

BLE
---
На Stick: Config → Advanced → Toggle BLE API.
Приложение сканирует имя "Bruc" и сервис
4371ec0b-3d43-49f9-b731-7c72a4a7bb91.
В приложении выберите Bluetooth LE → Scan.

Протокол (USB serial/BLE)
-------------------------
Базовые команды:
  CMD:PING
  CMD:SYS:STATUS
  CMD:BTN:M5|A|B|PWR:PRESS|RELEASE
  CMD:NAV:UP|DOWN|LEFT|RIGHT|OK:PRESS|RELEASE
  CMD:TEXT:<текст>
  CMD:SCRIPT:LIST
  CMD:SCRIPT:RUN:<имя>
  CMD:SCRIPT:LOAD:<base64 имени>:<base64 исходника>
  CMD:SCRIPT:STOP

Live menu:
  CMD:MENU:LIST
  CMD:MENU:OPEN:<index>
  CMD:MENU:BACK
  CMD:MENU:HOME

Экран:
  CMD:SCREEN:TEXT:<base64 текста>
  CMD:SCREEN:CLEAR

ИК:
  CMD:IR:NEC|NECEXT|SAMSUNG:<address>:<command>
  CMD:IR:SONY:<address>:<command>:<bits>

Файловая система:
  CMD:FS:LIST:<base64 path>
  CMD:FS:READ:<base64 path>
  CMD:FS:BEGIN:<base64 path>:<size>
  CMD:FS:CHUNK:<base64 data>
  CMD:FS:END
  CMD:FS:WRITE:<base64 path>:<base64 data>
  CMD:FS:DELETE:<base64 path>
  CMD:FS:MKDIR:<base64 path>
  CMD:FS:RENAME:<base64 old>:<base64 new>

Телеметрия от Stick:
  STATUS:BAT:<мВ>:<процент>
  STATUS:UPTIME:<секунды>
  STATUS:FW:Bruce 1.16.1 + StickCommander
  LOG:I|W|E|B:<тег>:<сообщение>
  DISPLAY:<текст>
  SCRIPTS:...
  PONG
  OK/ERR

Ответы инструментов:
  MENU:TITLE:<base64>
  MENU:SEL:<index>
  MENU:ITEM:<index>:<0|1>:<base64 label>
  MENU:END
  MENU:NONE
  FS:LIST:<base64 path>
  FS:ENTRY:<D|F>:<size>:<base64 name>
  FS:LISTEND
  FS:FILE:<size>:<base64 path>
  FS:DATA:<base64 bytes>
  FS:FILEEND
  FS:OK:<action>:<base64 path>:<size>
  FS:ERR:<сообщение>

Кнопки:
  M5  -> Sel / выбор
  A   -> Up
  B   -> Down
  PWR -> Esc / назад (без выключения питания)
