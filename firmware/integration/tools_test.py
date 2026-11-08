import base64
import time

import serial


def b64(value: str) -> str:
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def read_lines(port, seconds=1.0):
    deadline = time.time() + seconds
    out = []
    while time.time() < deadline:
        line = port.readline().decode("utf-8", "replace").strip()
        if line:
            out.append(line)
            print("RX:", line)
    return out


def command(port, text, wait=0.8):
    print("TX:", text)
    port.write((text + "\n").encode("ascii"))
    port.flush()
    return read_lines(port, wait)


def main() -> int:
    with serial.Serial("COM3", 115200, timeout=0.4) as port:
        port.dtr = True
        port.rts = True
        time.sleep(8.0)
        port.reset_input_buffer()

        seen = []
        seen += command(port, "CMD:MENU:LIST", 1.2)
        seen += command(port, "CMD:MENU:OPEN:0", 1.5)
        seen += command(port, "CMD:MENU:LIST", 1.2)
        seen += command(port, "CMD:MENU:BACK", 1.0)

        seen += command(port, "CMD:SCREEN:TEXT:" + b64("Hello from StickCommander\nIR + files demo"), 1.0)
        seen += command(port, "CMD:IR:NEC:04:08", 1.5)

        seen += command(port, "CMD:FS:LIST:" + b64("/"), 1.2)
        seen += command(port, "CMD:FS:MKDIR:" + b64("/tools_test"), 0.8)
        content = "hello from tools"
        path = "/tools_test/hello.txt"
        seen += command(port, "CMD:FS:BEGIN:" + b64(path) + ":" + str(len(content)), 0.8)
        seen += command(port, "CMD:FS:CHUNK:" + b64(content), 0.8)
        seen += command(port, "CMD:FS:END", 0.8)
        seen += command(port, "CMD:FS:LIST:" + b64("/tools_test"), 1.0)
        seen += command(port, "CMD:FS:READ:" + b64(path), 1.2)
        seen += command(port, "CMD:FS:DELETE:" + b64(path), 0.8)

        checks = {
            "menu title": any(line.startswith("MENU:TITLE:") for line in seen),
            "menu items": any(line.startswith("MENU:ITEM:") for line in seen),
            "screen draw": any(line == "OK:screen:text" for line in seen),
            "ir send": any(line == "OK:ir:NEC" for line in seen),
            "fs list": any(line.startswith("FS:LIST:") for line in seen),
            "fs mkdir": any(line.startswith("FS:OK:mkdir:") for line in seen),
            "fs upload": any(line.startswith("FS:OK:upload:") for line in seen),
            "fs read": any(line.startswith("FS:FILE:") for line in seen)
            and any(line.startswith("FS:DATA:") for line in seen),
            "fs delete": any(line.startswith("FS:OK:delete:") for line in seen),
        }
        for name, ok in checks.items():
            print(f"CHECK {name}: {'PASS' if ok else 'FAIL'}")
        return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
