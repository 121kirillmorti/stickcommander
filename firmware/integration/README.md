# Applying the StickCommander bridge to Bruce 1.16.1

This folder contains only the files that differ from upstream Bruce 1.16.1.

## Files

| File | What changed |
| --- | --- |
| `src/core/stickcommander.h` | Bridge API (`stickcommanderSetup`, `stickcommanderLoop`, `stickcommanderHandleLine`). |
| `src/core/stickcommander.cpp` | USB/BLE command protocol: status, buttons, scripts, live menu, screen, IR, LittleFS. |
| `src/core/serialcmds.cpp` | `CMD:*` lines are routed to the bridge before SimpleCLI sees them. |
| `src/main.cpp` | `Serial.setRxBufferSize(4096)` so long upload frames survive. |
| `src/modules/ble_api/services/BLESerialService.cpp` | Consume the characteristic value after reading (prevents repeat execution). |
| `platformio.ini` | Adds `patch_fastled_fp.py` to `extra_scripts`. |
| `patch_fastled_fp.py` | Renames FastLED's `FP` macro and drops LTO/long-calls for FastLED (Bruce builds with `-DFP=1`). |
| `tools_test.py` | End-to-end test over COM3; prints PASS/FAIL for menu, screen, IR and files. |

## Steps

```bash
git clone https://github.com/BruceDevices/firmware.git bruce
cd bruce
git checkout 1.16.1

# copy the patched files over the tree
cp -r <this-repo>/firmware/integration/src/. src/
cp <this-repo>/firmware/integration/platformio.ini .
cp <this-repo>/firmware/integration/patch_fastled_fp.py .

# build the merged 8 MB image
pio run -e m5stack-cplus2
```

The build writes `Bruce-m5stack-cplus2.bin` (about 3.9 MB). Flash it from address `0x0`:

```bash
esptool --port COM3 --baud 460800 write-flash 0x0 Bruce-m5stack-cplus2.bin
```

## Windows notes

- Long paths break the toolchain; build from a short path such as `C:\b\bruce` with
  `PLATFORMIO_CORE_DIR=C:\b\pio`.
- The FastLED patch runs automatically on every build and is idempotent.

## Verifying

With the Stick connected:

```bash
python tools_test.py
```

Expected: `CHECK menu title: PASS`, `CHECK screen draw: PASS`, `CHECK ir send: PASS`,
`CHECK fs upload: PASS`, and so on.
