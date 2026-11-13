import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.main import CompileRequest, CORE_BY_BOARD, FQBN_BY_BOARD, auto_library_names


def test_supported_board_presets_have_core_and_fqbn():
    assert set(CORE_BY_BOARD) == set(FQBN_BY_BOARD)
    assert FQBN_BY_BOARD["esp32_s3"].endswith(":esp32s3")
    assert FQBN_BY_BOARD["arduino_uno"] == "arduino:avr:uno"
    assert CORE_BY_BOARD["ch32v003"] == "WCH:ch32v"
    assert FQBN_BY_BOARD["ch32v003"] == "WCH:ch32v:CH32V00x_EVT"


def test_project_rejects_path_traversal():
    try:
        CompileRequest(board="esp32", files={"../evil.ino": "void setup(){}"})
        assert False
    except ValueError:
        pass


def test_project_requires_ino():
    try:
        CompileRequest(board="esp32", files={"main.cpp": "int main(){}"})
        assert False
    except ValueError:
        pass


def test_library_auto_resolution_by_header():
    libraries = [{"name": "Fake NeoPixel", "providesIncludes": ["Adafruit_NeoPixel.h"]}]
    found = auto_library_names({"main.ino": "#include <Adafruit_NeoPixel.h>\nvoid setup(){}"}, libraries, [])
    assert found == ["Fake NeoPixel"]
