# StickCommander firmware integration

The repository ships the **StickCommander bridge** for Bruce 1.16.1, not a full copy of
Bruce. The complete source tree is third-party and is fetched from upstream.

| Path | Purpose |
| --- | --- |
| `integration/` | Files to copy over a Bruce 1.16.1 checkout (`src/core/stickcommander.*`, modified `src/core/serialcmds.cpp`, `src/main.cpp`, `platformio.ini`, `patch_fastled_fp.py`, BLE service fix). |
| `integration/tools_test.py` | End-to-end protocol test over COM3 (menu, screen, IR, files). |
| `bruce-build/Bruce-m5stack-cplus2.bin` | The merged image that was built and flashed (also attached to releases). |
| `backup/m5stickc_plus2_stock_8MB.bin` | Full 8 MB dump of the firmware that was on the Stick before any changes. |
| `stock/Bruce-m5stack-cplus2-stock.bin` | Stock Bruce image used as a reference. |

Both binary images are kept out of Git and attached to GitHub releases instead.

## Applying the patch

```bash
git clone https://github.com/BruceDevices/firmware.git bruce
cd bruce && git checkout 1.16.1
cp -r /path/to/stickcommander/firmware/integration/src/. src/
cp /path/to/stickcommander/firmware/integration/platformio.ini .
cp /path/to/stickcommander/firmware/integration/patch_fastled_fp.py .
pio run -e m5stack-cplus2
```

The build produces `Bruce-m5stack-cplus2.bin`, a merged image that must be written at
`0x0`.

## Changes made to Bruce

- Added `src/core/stickcommander.{h,cpp}`:
  - newline-delimited `CMD:*` protocol
  - real battery percentage via Bruce's `getBattery()` and voltage via
    `ANALOG_BAT_PIN` (GPIO 38 on Plus2)
  - uptime and firmware identity
  - D-Pad / button injection into Bruce's `UpPress`, `DownPress`, `PrevPress`,
    `NextPress`, `SelPress`, `EscPress` flags
  - device script listing from `/scripts` (plus `status_report`/`echo_demo` built-ins)
  - script execution through Bruce's JavaScript interpreter
  - phone script upload (base64) to LittleFS `/scripts` and immediate execution
  - telemetry every 5 s while the app is active
  - **live menu bridge**: reads Bruce's `options` / `menuOptionLabel` / `selected` state
    and sets `forceMenuOption`, `EscPress`, `returnToMenu`, so the phone navigates the
    real firmware menu; `MENU:*` frames expose the list to the app
  - **screen bridge**: `CMD:SCREEN:TEXT` draws wrapped text on the TFT with the active
    theme colors; `CMD:SCREEN:CLEAR` restores the idle screen
  - **IR bridge**: `CMD:IR:NEC|NECEXT|SAMSUNG|SONY` calls Bruce's own encoders
    (`sendNECCommand`, `sendSamsungCommand`, `sendSonyCommand`)
  - **LittleFS manager**: base64-framed `FS:LIST|READ|DELETE|MKDIR|RENAME|BEGIN|CHUNK|END|WRITE`
    with chunked uploads and a 384-byte read buffer
- Hooked the handler into Bruce's existing serial command task in
  `src/core/serialcmds.cpp`, before SimpleCLI, so `CMD:*` frames never reach the
  normal CLI or force a menu redraw.
- Fixed Bruce's BLE serial characteristic to consume its value after a read
  (`BLESerialService::readStringUntil`), preventing repeated command execution.

## Hardware / protocol

- Board: **M5StickC Plus2** (`ESP32-PICO-V3-02`, 8 MB flash, CH9102 USB-UART)
- USB: COM3, 115200 8N1
- BLE: Bruce's built-in serial service
  - service `4371ec0b-3d43-49f9-b731-7c72a4a7bb91`
  - characteristic `d555ed97-bf2a-4f46-b3eb-d1fcdd7325e9`
  - enable it on the Stick via **Config → Advanced → Toggle BLE API**
- The Android app supports both Nordic UART (NUS) and the Bruce service.
- Use **USB** for large script uploads. Bruce's BLE characteristic is optimized for
  short commands; long base64 script frames are reliable over the USB serial link.

## App → Stick

```
CMD:PING
CMD:SYS:STATUS
CMD:BTN:M5|A|B|PWR:PRESS|RELEASE
CMD:NAV:UP|DOWN|LEFT|RIGHT|OK:PRESS|RELEASE
CMD:TEXT:<escaped text>
CMD:SCRIPT:LIST
CMD:SCRIPT:RUN:<name>
CMD:SCRIPT:LOAD:<base64 name>:<base64 source>
CMD:SCRIPT:STOP

CMD:MENU:LIST
CMD:MENU:OPEN:<index>
CMD:MENU:BACK
CMD:MENU:HOME

CMD:SCREEN:TEXT:<base64 text>
CMD:SCREEN:CLEAR

CMD:IR:NEC|NECEXT|SAMSUNG:<address>:<command>
CMD:IR:SONY:<address>:<command>:<bits>

CMD:FS:LIST:<base64 path>
CMD:FS:READ:<base64 path>
CMD:FS:BEGIN:<base64 path>:<size>
CMD:FS:CHUNK:<base64 data>
CMD:FS:END
CMD:FS:WRITE:<base64 path>:<base64 data>
CMD:FS:DELETE:<base64 path>
CMD:FS:MKDIR:<base64 path>
CMD:FS:RENAME:<base64 old>:<base64 new>
```

Button mapping: `M5` → select, `A` → up, `B` → down, `PWR` → back/escape.
`CMD:NAV` drives the same navigation flags.

The **Live menu** in the Android Tools tab polls `CMD:MENU:LIST`. The Stick returns the
current menu options, so tapping an item in the app sends `CMD:MENU:OPEN:<index>` and
navigates the real Stick menu. The same tab renders a 135×240-style text mirror of the
menu and can draw text on the Stick display (`CMD:SCREEN:TEXT`).

The IR tool uses the Stick's IR LED through Bruce's existing encoder functions.
The file manager browses LittleFS and supports list/read/upload/delete/mkdir/rename.

## Stick → App

```
STATUS:BAT:<mV>:<percent>
STATUS:UPTIME:<seconds>
STATUS:FW:Bruce 1.16.1 + StickCommander
LOG:I|W|E|B:<tag>:<message>
DISPLAY:<text>
SCRIPTS:name.js|other.js
SCRIPTS:END
PONG
OK:...
ERR:...

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
FS:ERR:<message>
```

## Build

PlatformIO is used by Bruce. From `firmware/bruce-src/firmware-1.16.1`:

```powershell
pio run -e m5stack-cplus2
```

The release build is merged into:

```
firmware/bruce-src/firmware-1.16.1/Bruce-m5stack-cplus2.bin
```

Flash the merged image at offset `0x0`:

```powershell
esptool.exe --port COM3 --baud 460800 write-flash 0x0 Bruce-m5stack-cplus2.bin
```

## Restore the original firmware

```powershell
esptool.exe --port COM3 --baud 460800 write-flash 0x0 firmware/backup/m5stickc_plus2_stock_8MB.bin
```

The 8 MB backup restores the complete prior flash, including its NVS/FS state.
