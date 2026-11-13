# ESP Forge Online Build API

This Linux service uses Arduino CLI. It fetches board cores from vendor Boards Manager indexes and resolves known sketch headers against the Arduino Library Manager index. The returned ZIP includes all build outputs, a manifest and diagnostics.

## Run with Docker

```bash
docker build -t esp-forge-build-api backend
docker run --rm -p 8080:8080 -e API_KEY='replace-with-a-long-random-secret' -v espforge-arduino-data:/opt/arduino-data -v espforge-arduino-user:/opt/arduino-user esp-forge-build-api
```

Configure the Android app's Online Build URL as `https://YOUR_HOST` and enter the same API key.

## API

- `GET /health`
- `GET /v1/boards`
- `POST /v1/compile` with `X-API-Key` and JSON body: `board`, optional exact `fqbn`, a `files` map and optional explicit library package names.

The result is an `application/zip` containing `firmware/*`, `build-manifest.json`, and `build.log`.

## Supported presets

Uno, Nano, ESP32, ESP32-S3, ESP8266 NodeMCU, STM32 Blue Pill F1, and WCH CH32V003. CH32/STM32 families contain many hardware variants; confirm the exact FQBN before flashing a real board.

## Notes

- The service compiles only; it cannot access a phone's USB OTG connection. The firmware archive must be downloaded to Android for local flashing.
- Set a strong API key, HTTPS and deployment-level rate limits before public exposure.
- The Library Manager index is fetched by the server. Known header dependencies in that index are auto-installed; unrecognized third-party includes must be named explicitly in the Libraries field.
- First-time core downloads can take several minutes. Keep the Arduino data volume persistent.
