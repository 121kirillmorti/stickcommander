import base64
import time

import serial

PORT = "COM3"


def b64(value: str) -> str:
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def main() -> int:
    with serial.Serial(PORT, 115200, timeout=0.5) as port:
        port.dtr = True
        port.rts = True
        time.sleep(2.0)
        port.reset_input_buffer()

        name = b64("hello_sc")
        source = b64('log("uploaded from StickCommander");\n')
        command = f"CMD:SCRIPT:LOAD:{name}:{source}\n"
        print("TX:", command[:100])
        port.write(command.encode("ascii"))
        port.flush()
        time.sleep(2.0)

        seen = []
        deadline = time.time() + 4.0
        while time.time() < deadline:
            line = port.readline().decode("utf-8", "replace").strip()
            if line:
                print("RX:", line)
                seen.append(line)

        port.write(b"CMD:SCRIPT:LIST\n")
        port.flush()
        time.sleep(1.5)
        deadline = time.time() + 3.0
        while time.time() < deadline:
            line = port.readline().decode("utf-8", "replace").strip()
            if line:
                print("RX:", line)
                seen.append(line)

        ok = any("OK:script hello_sc.js" in line for line in seen) and any(
            "hello_sc.js" in line and line.startswith("SCRIPTS:") for line in seen
        )
        print("RESULT:", "PASS" if ok else "FAIL")
        return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
