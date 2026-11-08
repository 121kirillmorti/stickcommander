"""Quick serial smoke test for the StickCommander adapter firmware.

Usage:
    python firmware/serial_check.py --port COM3
"""

import argparse
import time

import serial


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", default="COM3")
    parser.add_argument("--baud", type=int, default=115200)
    args = parser.parse_args()

    with serial.Serial(args.port, args.baud, timeout=0.5) as port:
        # Both asserted together is the "do not reset" state on ESP32 auto-reset boards.
        port.dtr = True
        port.rts = True
        time.sleep(8.0)
        port.reset_input_buffer()

        seen = []
        deadline = time.time() + 3.0
        while time.time() < deadline:
            line = port.readline().decode("utf-8", "replace").strip()
            if line:
                print("RX:", line)
                seen.append(line)

        for command in (b"CMD:PING\n", b"CMD:SYS:STATUS\n", b"CMD:BTN:M5:PRESS\n",
                        b"CMD:TEXT:hello from serial check\n", b"CMD:SCRIPT:LIST\n",
                        b"CMD:SCRIPT:RUN:status_report\n"):
            print("TX:", command.decode().strip())
            port.write(command)
            port.flush()
            time.sleep(0.4)

        deadline = time.time() + 3.0
        while time.time() < deadline:
            line = port.readline().decode("utf-8", "replace").strip()
            if line:
                print("RX:", line)
                seen.append(line)

        ok = any(line == "PONG" for line in seen) and any(
            line.startswith("STATUS:BAT:") for line in seen
        )
        print("RESULT:", "PASS" if ok else "FAIL")
        return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
