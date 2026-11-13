# ESP Forge Online Build API

This Linux service uses Arduino CLI. It fetches board cores from vendor Boards Manager indexes and resolves known sketch headers against the Arduino Library Manager index. The returned ZIP includes all build outputs, a manifest and diagnostics.

## Run with Docker

```bash
docker build -t esp-forge-build-api backend
docker run --rm -p 8080:8080 -e API_KEY='replace-with-a-long-random-secret' -v espforge-arduino-data:/opt/arduino-data -v espforge-arduino-user:/opt/arduino-user esp-forge-build-api
```

Configure the Android app's Online Build URL as `http://YOUR_HOST:8080` (use HTTPS when internet-facing) and enter the same API key.

## API

- `GET /health`
- `GET /v1/boards`
- `POST /v1/compile` with `X-API-Key` and JSON:

```json
{
  "board": "esp32",
  "fqbn": "esp32:esp32:esp32",
  "files": {"main.ino": "#include <Arduino.h>\nvoid setup() {}\nvoid loop() {}"},
  "libraries": []
}
```

The result is an `application/zip` containing `firmware/*`, `build-manifest.json`, and `build.log`.

## Presets

| Target | Default FQBN |
|---|---|
| Uno | `arduino:avr:uno` |
| Nano | `arduino:avr:nano` |
| ESP32 | `esp32:esp32:esp32` |
| ESP32-S3 | `esp32:esp32:esp32s3` |
| ESP8266 NodeMCU | `esp8266:esp8266:nodemcuv2` |
| STM32 Blue Pill | `STMicroelectronics:stm32:GenF1` |
| CH32V003 | `ch32-riscv-ug:CH32RV:CH32V003` |

CH32 and STM32 board definitions differ by exact MCU, package and board layout. Confirm the FQBN against the installed core before building on a physical target.

## Notes

- The service compiles only; it cannot access a phone's USB OTG connection. The firmware archive must be downloaded to Android for local flashing.
- Set a strong API key, HTTPS and deployment-level rate limits before public exposure. Never expose this service without authentication.
- Known Arduino Library Manager includes are fetched automatically. Third-party libraries absent from that registry must be added by exact library package name.
- First-time core downloads can take several minutes. Keep the Arduino data volume persistent between service restarts.
