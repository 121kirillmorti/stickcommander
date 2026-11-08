#ifndef __STICKCOMMANDER_H__
#define __STICKCOMMANDER_H__

#include <Arduino.h>

// StickCommander USB/BLE protocol bridge.
//
// The Android app talks newline-delimited ASCII over Bruce's serialDevice
// (USB CDC/UART when USB is active, BLE serial when "Toggle BLE API" is on).
// It is intentionally registered before the regular Bruce CLI so CMD:* frames
// never reach SimpleCLI or trigger a menu redraw.
void stickcommanderHandleLine(const String &line);
void stickcommanderLoop();

#endif
