# UsbThing

An Android application for programming MCUs over USB (usb-serial and DFU).
Domain: scinova.net

## Architecture

Base class `Programmer`, with derived `AVRProgrammer`, `ESP32Programmer`,
`STM32Programmer`.

## Main view

Five sections, stacked:

1. **Programmer selector** — AVR / ESP32 / STM32. Choice persisted.
2. **File selector** — select a file, show filename and size at the top of
   the section, remember the file persistently. No external-storage
   permission needed; the file comes in over USB.
3. **Device list** — scrollable list of connected USB devices, showing
   VID/PID only. Each device has a button that opens the permission dialog
   for that device. Once permission is granted, the button disappears for
   that device. With multiple devices connected, the user can select any
   one; the button and dialog appear only for devices not yet granted.
4. **Log** — scrollable, auto-scrolled to the bottom so the latest line is
   visible. Every class logs its relevant events through a single
   `log(message)` method.
5. **Info** — an "Info" button that requests detailed info from the
   selected device, for chips that expose it (not AV

Rs). The MCU type
   and protocol come from the user's selection, not from the connected
   hardware's VID/PID. Automatic MCU detection is a future feature; for now
   a hardware mismatch is ignored.

## Environment setup

See `install.sh` (TODO: exact path and whether it also handles arduino-cli).

Android SDK root: `/usr/local/lib/android-sdk`
JDK: OpenJDK 21 (headless)

## Build

`make` builds the APK (and copies it to the public web dir); `make build/UsbThing-0.03.apk`
builds it without publishing. The blink test images are compiled with `arduino-cli` for
both boards: `blink*.hex` (Uno, `FQBN`) and `blink*_mega.hex` (Mega 2560, `FQBN_MEGA`).

`make test` runs the JVM unit tests for the STK500v2 framing (`Stk500v2.java`), no
Android or hardware needed.

## Protocols

| MCU         | Bootloader protocol | Programmer          |
|-------------|---------------------|---------------------|
| ATmega328P  | STK500v1            | `AVRProgrammer`     |
| ATmega2560  | STK500v2            | `AVRProgrammerV2`   |

`MCUConfig.createProgrammer` picks the programmer from the MCU's protocol.

STK500v2 notes (checked against the Arduino `stk500boot.c`):
- Frame `1B SEQ SIZE_H SIZE_L 0E body XOR`. Reply body is `[cmd][status][payload]`.
- Sync is `SIGN_ON` with seq 1; any well-formed reply counts, the string is only logged.
- `ENTER_PROGMODE_ISP` is sent after sync; any well-formed reply is accepted.
- `LOAD_ADDRESS` takes a 4-byte big-endian *word* address. Bit 31 (extended flag) is
  set for the 2560 and is shifted out by the bootloader, so it is harmless.
- `PROGRAM_FLASH_ISP` data starts at body offset 10. The bootloader erases the next
  page on each write, so pages are written in order from address 0.
- The top 8 KB of the 2560's flash is the bootloader; larger images are refused.

Not yet tested on hardware: DTR/RTS reset timing on the Mega's USB-serial chip
(ATmega16U2 or CH340), and the first real flash. The log shows which step failed.
