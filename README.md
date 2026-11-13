# ESP Forge IDE

Android-first Arduino/embedded development environment. This repository is being repurposed from Scanner-.

## Targets
- ESP32 / ESP32-S3
- ESP8266
- Arduino AVR Uno / Nano
- CH32
- STM32

## Build
Open this repository in Android Studio (Hedgehog or newer) and build the `app` module, or use the included GitHub Actions workflow to produce a debug APK.

## Build modes (planned architecture)
- **Online:** a remote Arduino CLI build service resolves libraries from configured registries and compiles projects. The Android app should not require users to manually download library ZIPs.
- **Offline:** a local toolchain compiles on-device where supported. Users can import/export library ZIPs and install them into the local library store.
- **Upload:** compiled firmware is flashed over USB OTG using board-specific upload tools. Cloud compilation does not itself flash a USB-connected board; the APK receives the firmware and performs the USB upload locally.

Hardware flashing and local toolchain support must be verified on real Android devices before being labelled production-ready.
