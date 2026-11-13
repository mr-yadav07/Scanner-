from __future__ import annotations

import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import tempfile
import threading
import zipfile
from pathlib import Path
from typing import Dict, List

import requests
from fastapi import FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field, field_validator

APP_VERSION = "0.1.0"
CLI = os.getenv("ARDUINO_CLI", "arduino-cli")
DATA_DIR = Path(os.getenv("ARDUINO_DIRECTORIES_DATA", "/opt/arduino-data"))
USER_DIR = Path(os.getenv("ARDUINO_DIRECTORIES_USER", "/opt/arduino-user"))
DOWNLOAD_DIR = Path(os.getenv("ARDUINO_DIRECTORIES_DOWNLOADS", "/opt/arduino-downloads"))
CONFIG_FILE = DATA_DIR / "arduino-cli.yaml"
API_KEY = os.getenv("API_KEY", "")
CLI_LOCK = threading.Lock()
MAX_PROJECT_BYTES = 350_000
MAX_FILES = 40
MAX_BUILD_SECONDS = 900

ADDITIONAL_URLS = [
    "https://espressif.github.io/arduino-esp32/package_esp32_index.json",
    "https://arduino.esp8266.com/stable/package_esp8266com_index.json",
    "https://github.com/stm32duino/BoardManagerFiles/raw/main/package_stmicroelectronics_index.json",
    "https://ch32-riscv-ug.github.io/ArduinoCore-CH32RV/package_ch32-riscv-ug_index.json",
]
CORE_BY_BOARD = {
    "arduino_uno": "arduino:avr",
    "arduino_nano": "arduino:avr",
    "esp32": "esp32:esp32",
    "esp32_s3": "esp32:esp32",
    "esp8266": "esp8266:esp8266",
    "stm32_bluepill": "STMicroelectronics:stm32",
    "ch32v003": "ch32-riscv-ug:CH32RV",
}
FQBN_BY_BOARD = {
    "arduino_uno": "arduino:avr:uno",
    "arduino_nano": "arduino:avr:nano",
    "esp32": "esp32:esp32:esp32",
    "esp32_s3": "esp32:esp32:esp32s3",
    "esp8266": "esp8266:esp8266:nodemcuv2",
    "stm32_bluepill": "STMicroelectronics:stm32:GenF1",
    "ch32v003": "ch32-riscv-ug:CH32RV:CH32V003",
}
CORE_HEADERS = {
    "Arduino.h", "WiFi.h", "ESP8266WiFi.h", "EEPROM.h", "SPI.h", "Wire.h",
    "Preferences.h", "HardwareSerial.h", "stdint.h", "stdbool.h", "math.h",
    "string", "vector", "algorithm", "array", "functional", "memory"
}

app = FastAPI(title="ESP Forge Build API", version=APP_VERSION)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type", "X-API-Key"],
)


class CompileRequest(BaseModel):
    board: str = Field(min_length=2, max_length=40)
    fqbn: str | None = Field(default=None, max_length=160)
    files: Dict[str, str]
    libraries: List[str] = Field(default_factory=list, max_length=40)
    optimize: bool = True

    @field_validator("files")
    @classmethod
    def validate_files(cls, value: Dict[str, str]) -> Dict[str, str]:
        if not value or len(value) > MAX_FILES:
            raise ValueError(f"Project must contain 1 to {MAX_FILES} files")
        total = 0
        clean: Dict[str, str] = {}
        for name, content in value.items():
            p = Path(name)
            if p.is_absolute() or ".." in p.parts or not re.fullmatch(r"[A-Za-z0-9_./ -]+", name):
                raise ValueError(f"Unsafe file path: {name}")
            if p.suffix.lower() not in {".ino", ".cpp", ".c", ".h", ".hpp"}:
                raise ValueError(f"Unsupported source file: {name}")
            total += len(content.encode("utf-8"))
            clean[name] = content
        if total > MAX_PROJECT_BYTES:
            raise ValueError("Project source exceeds 350 KB")
        if not any(n.lower().endswith(".ino") for n in clean):
            raise ValueError("At least one .ino sketch file is required")
        return clean

    @field_validator("libraries")
    @classmethod
    def validate_libraries(cls, value: List[str]) -> List[str]:
        for lib in value:
            if not re.fullmatch(r"[A-Za-z0-9 _.+@-]{1,100}", lib):
                raise ValueError(f"Invalid library name: {lib}")
        return value


def run_cli(args: List[str], timeout: int = 300) -> subprocess.CompletedProcess:
    return subprocess.run(
        [CLI, "--config-file", str(CONFIG_FILE), *args], text=True,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout,
        check=False, env=os.environ.copy()
    )


def ensure_cli_config() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    USER_DIR.mkdir(parents=True, exist_ok=True)
    DOWNLOAD_DIR.mkdir(parents=True, exist_ok=True)
    urls = "\n".join(f"    - {url}" for url in ADDITIONAL_URLS)
    CONFIG_FILE.write_text(
        "board_manager:\n  additional_urls:\n" + urls + "\n"
        "directories:\n"
        f"  data: {DATA_DIR}\n"
        f"  downloads: {DOWNLOAD_DIR}\n"
        f"  user: {USER_DIR}\n",
        encoding="utf-8",
    )


def ensure_core(core: str, log: List[str]) -> None:
    with CLI_LOCK:
        installed = run_cli(["core", "list", "--format", "json"], timeout=60)
        if installed.returncode == 0:
            try:
                payload = json.loads(installed.stdout or "{}")
                entries = payload.get("platforms", []) if isinstance(payload, dict) else payload
                if any(item.get("id") == core for item in entries):
                    return
            except (json.JSONDecodeError, AttributeError, TypeError):
                pass
        p = run_cli(["core", "update-index"], timeout=300)
        log.append(p.stdout)
        if p.returncode:
            raise RuntimeError("Board index update failed:\n" + p.stdout[-12000:])
        p = run_cli(["core", "install", core], timeout=MAX_BUILD_SECONDS)
        log.append(p.stdout)
        if p.returncode:
            raise RuntimeError(f"Board core installation failed ({core}):\n" + p.stdout[-16000:])


def load_library_index(log: List[str]) -> list:
    cache = DATA_DIR / "library_index.json"
    if cache.exists() and cache.stat().st_size > 1000:
        try:
            return json.loads(cache.read_text(encoding="utf-8")).get("libraries", [])
        except Exception:
            pass
    try:
        response = requests.get("https://downloads.arduino.cc/libraries/library_index.json", timeout=60)
        response.raise_for_status()
        payload = response.json()
        cache.write_text(json.dumps(payload), encoding="utf-8")
        log.append("Fetched Arduino Library Manager index.")
        return payload.get("libraries", [])
    except Exception as exc:
        log.append(f"Library index unavailable: {exc}")
        return []


def auto_library_names(files: Dict[str, str], index: list, log: List[str]) -> list[str]:
    includes = set()
    for content in files.values():
        includes.update(re.findall(r"^\s*#\s*include\s*[<\"]([^>\"]+)[>\"]", content, re.MULTILINE))
    mapping = {}
    for item in index:
        headers = item.get("providesIncludes", item.get("includes", [])) or []
        for header in headers:
            mapping.setdefault(Path(header).name.lower(), item.get("name", ""))
    names = set()
    core_headers = {h.lower() for h in CORE_HEADERS}
    for header in includes:
        base = Path(header).name
        if base.lower() in core_headers:
            continue
        name = mapping.get(base.lower())
        if name:
            names.add(name)
        else:
            log.append(f"Library not resolved from Arduino Library Manager: {base}. If it is a third-party dependency, add its package name in Libraries.")
    return sorted(names)


def install_libraries(names: List[str], log: List[str]) -> None:
    for name in sorted(set(names)):
        p = run_cli(["lib", "install", name], timeout=300)
        log.append(p.stdout)
        if p.returncode:
            raise RuntimeError(f"Library install failed ({name}):\n" + p.stdout[-12000:])


@app.get("/health")
def health():
    return {"ok": True, "service": "esp-forge-build-api", "version": APP_VERSION}


@app.get("/v1/boards")
def boards():
    return {
        "boards": [
            {"id": key, "label": label, "fqbn": FQBN_BY_BOARD[key], "core": CORE_BY_BOARD[key]}
            for key, label in [
                ("arduino_uno", "Arduino Uno"),
                ("arduino_nano", "Arduino Nano"),
                ("esp32", "ESP32 Dev Module"),
                ("esp32_s3", "ESP32-S3 Dev Module"),
                ("esp8266", "ESP8266 NodeMCU"),
                ("stm32_bluepill", "STM32 Blue Pill (F1)"),
                ("ch32v003", "WCH CH32V003"),
            ]
        ],
        "note": "Choose the exact board FQBN for your hardware. CH32 and STM32 variants differ by MCU and board definition."
    }


@app.post("/v1/compile")
def compile_project(request: CompileRequest, x_api_key: str | None = Header(default=None)):
    if not API_KEY:
        raise HTTPException(status_code=503, detail="Build service is not configured. Set API_KEY before exposing it.")
    if x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="Invalid build API key.")
    board = request.board.lower()
    if board not in CORE_BY_BOARD:
        raise HTTPException(status_code=400, detail=f"Unsupported board preset: {board}")
    fqbn = request.fqbn or FQBN_BY_BOARD[board]
    if not fqbn.startswith(CORE_BY_BOARD[board] + ":") or not re.fullmatch(r"[A-Za-z0-9_.:-]+", fqbn):
        raise HTTPException(status_code=400, detail="FQBN does not match the selected board family.")

    logs: List[str] = []
    ensure_cli_config()
    work = Path(tempfile.mkdtemp(prefix="espforge-"))
    try:
        sketch = work / "ESPForgeSketch"
        outdir = work / "build"
        libsdir = work / "project-libraries"
        sketch.mkdir()
        outdir.mkdir()
        libsdir.mkdir()
        for name, content in request.files.items():
            dest = (sketch / name).resolve()
            if sketch.resolve() not in dest.parents:
                raise HTTPException(status_code=400, detail="Invalid project path.")
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(content, encoding="utf-8")
        ensure_core(CORE_BY_BOARD[board], logs)
        names = set(request.libraries)
        names.update(auto_library_names(request.files, load_library_index(logs), logs))
        install_libraries(list(names), logs)
        command = ["compile", "--fqbn", fqbn, "--output-dir", str(outdir), "--libraries", str(libsdir), "--warnings", "all"]
        if request.optimize:
            command.extend(["--build-property", "compiler.cpp.extra_flags=-Os"])
        command.append(str(sketch))
        result = run_cli(command, timeout=MAX_BUILD_SECONDS)
        logs.append(result.stdout)
        if result.returncode:
            raise RuntimeError("Compilation failed:\n" + result.stdout[-22000:])

        artifacts = [p for p in outdir.rglob("*") if p.is_file() and p.suffix.lower() in {".bin", ".hex", ".elf", ".eep", ".uf2"}]
        if not artifacts:
            raise RuntimeError("Compiler reported success but produced no firmware artifact.")
        memory = io.BytesIO()
        manifest = {
            "board": board, "fqbn": fqbn,
            "artifacts": [{"name": p.name, "size": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in artifacts],
        }
        with zipfile.ZipFile(memory, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("build-manifest.json", json.dumps(manifest, indent=2))
            archive.writestr("build.log", "\n".join(logs))
            for artifact in artifacts:
                archive.write(artifact, f"firmware/{artifact.name}")
        memory.seek(0)
        headers = {"Content-Disposition": 'attachment; filename="espforge-firmware.zip"'}
        return StreamingResponse(memory, media_type="application/zip", headers=headers)
    except subprocess.TimeoutExpired:
        raise HTTPException(status_code=504, detail="Build timed out after 15 minutes.")
    except RuntimeError as exc:
        raise HTTPException(status_code=422, detail=str(exc))
    finally:
        shutil.rmtree(work, ignore_errors=True)
