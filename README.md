# ESP Forge IDE

ESP Forge is an Android-first Arduino / embedded development environment, replacing the previous Scanner- prototype.

## Current project features

- Multi-file C/C++ sketch editor with local project persistence.
- Project ZIP import/export.
- Board and FQBN selection presets for Arduino Uno/Nano, ESP32, ESP32-S3, ESP8266, STM32 Blue Pill F1, and WCH CH32V003.
- Online compile client that downloads firmware archives from an Arduino CLI build API.
- Offline compile bridge using Termux + Debian PRoot + Arduino CLI.
- Library ZIP import/export for offline projects.
- Online server-side Arduino Library Manager index lookup and package installation for recognized `#include` dependencies.
- USB serial monitor and command sending.
- Direct ROM/bootloader flashing implemented in the app for ESP8266, ESP32 / ESP32-S3, and ATmega328P Uno/Nano (STK500v1), with verification paths in code.

## Build the APK

Open the repository in Android Studio (JDK 17, Android SDK 35) and build the `app` module. Or use GitHub Actions:

1. Open [Actions](https://github.com/mr-yadav07/Scanner-/actions).
2. Open the latest **ESP Forge CI** run.
3. Download the `esp-forge-debug-apk` artifact after both jobs pass.

The CI pipeline runs Android unit tests, assembles a debug APK, runs the Python backend tests, and checks backend syntax. The editor includes live online compiler logs, stage-based progress, elapsed time and cancellation; offline builds show Termux completion status and read the build log when shared-storage access permits.

## Online compilation setup

The Android client requires a deployed build API; no online compiler endpoint is preconfigured in the APK.

1. Import this repository into Render and use the root `render.yaml` Blueprint, or deploy `backend/Dockerfile` to another Docker host.
2. Set a strong private `API_KEY` environment variable. Render's Blueprint generates one automatically.
3. Wait for `/health` to report `ok: true`.
4. In the app, open Settings and enter the service HTTPS base URL and matching API key.

The server installs the requested Arduino board core and dependencies, compiles the uploaded multi-file project, and returns a ZIP containing firmware files, a build manifest and logs. The app uses `/v1/compile/stream` for live build stages and compiler output; `/v1/compile` remains available for clients that expect a completed ZIP response. Progress percentages represent completed build stages, not a compiler-internal percentage (Arduino CLI does not expose a reliable exact percentage). Arduino Library Manager headers are resolved from the live registry index when the index supplies include metadata. If a library is missing from that registry or its header cannot be mapped, add its exact Library Manager package name in the optional libraries field.

A hosted service needs sufficient memory and storage for the selected board packages, especially ESP32. Free hosting can cold-start, have limited resources and may not retain downloaded toolchains between restarts. A persistent disk and a more capable instance are recommended for reliable repeated builds.

## Offline compilation setup

1. Install the current Termux app from its official distribution source and open it once.
2. Enable Android app permission **Run commands in Termux environment** for ESP Forge.
3. In Termux, run `termux-setup-storage`.
4. Set `allow-external-apps=true` in `~/.termux/termux.properties` and restart Termux.
5. In ESP Forge Settings, tap **Prepare offline toolchain** for the selected target.

The first setup downloads Arduino CLI, board indexes, board core and available compiler packages. Subsequent offline builds work only while the selected board's complete toolchain and required libraries are present locally. Some third-party board package indexes publish compiler tools only for x86-64 Linux; those targets will not compile in an ARM64 Android PRoot environment and must use online compilation instead. CH32 tool packages are currently published for x86-64 Linux, so CH32 offline compilation is not expected to work on an ARM64 phone.

Offline library ZIPs are imported from the Libraries tab. The app stores them locally and copies them into `Documents/ESPForge/LibraryZips` for the Termux build process.

## Upload scope

- ESP8266 / ESP32 / ESP32-S3: serial ROM bootloader path is implemented.
- Arduino Uno / Nano: ATmega328P STK500v1 uploader accepts Intel HEX, checks the signature, writes pages and reads them back.
- STM32 and CH32: compile presets are included, but native Android USB flashing is not implemented yet. STM32 upload can require BOOT0/RESET sequencing and STM32 ROM bootloader support; CH32 commonly uses WCH-Link / OpenOCD. Do not use an ESP or AVR upload method for these boards.
- Upload over USB OTG depends on the phone, cable, USB-to-UART adapter, voltage levels, boot mode and the particular board. No physical board test can be inferred from a successful CI build.

## Third-party notices

The ESP serial ROM bootloader implementation is adapted from [janisxyz/esp-flasher](https://github.com/janisxyz/esp-flasher) under MIT; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and `licenses/ESP-FLASHER-MIT-LICENSE.txt`.

Board core indexes are maintained by their upstream projects:
- [Espressif Arduino-ESP32](https://espressif.github.io/arduino-esp32/package_esp32_index.json)
- [ESP8266 Arduino core](https://arduino.esp8266.com/stable/package_esp8266com_index.json)
- [STM32duino](https://github.com/stm32duino/BoardManagerFiles/raw/main/package_stmicroelectronics_index.json)
- [WCH Arduino Core for CH32](https://github.com/openwch/board_manager_files/raw/main/package_ch32v_index.json)
