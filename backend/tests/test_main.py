import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.main import CompileRequest, CORE_BY_BOARD, DEFAULT_BOARD_OPTIONS, FQBN_BY_BOARD, app, auto_library_names


def test_supported_board_presets_have_core_and_fqbn():
    assert set(CORE_BY_BOARD) == set(FQBN_BY_BOARD)
    assert FQBN_BY_BOARD["esp32_s3"].endswith(":esp32s3")
    assert FQBN_BY_BOARD["arduino_uno"] == "arduino:avr:uno"
    assert CORE_BY_BOARD["ch32v003"] == "WCH:ch32v"
    assert FQBN_BY_BOARD["ch32v003"] == "WCH:ch32v:CH32V00x_EVT"
    assert DEFAULT_BOARD_OPTIONS["stm32_bluepill"] == ["pnum=BLUEPILL_F103C8"]
    assert DEFAULT_BOARD_OPTIONS["ch32v003"] == ["pnum=CH32V003F4"]


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



def test_streaming_compile_endpoint_is_registered():
    paths = {route.path for route in app.routes}
    assert "/v1/compile/stream" in paths



def test_project_id_is_safe_for_cache_namespace():
    request = CompileRequest(project_id="my-project_01", board="esp32", files={"main.ino": "void setup(){}"})
    assert request.project_id == "my-project_01"
    try:
        CompileRequest(project_id="../escape", board="esp32", files={"main.ino": "void setup(){}"})
        assert False
    except ValueError:
        pass


def test_cached_sketch_preserves_unchanged_source_and_removes_deleted_files(tmp_path):
    from app.main import sync_cached_sketch

    sketch = tmp_path / "sketch"
    sketch.mkdir()
    files = {"main.ino": "void setup(){}\n", "helper.cpp": "int value(){return 1;}\n"}
    sync_cached_sketch(sketch, files)
    source = sketch / "main.ino"
    before = source.stat().st_mtime_ns

    sync_cached_sketch(sketch, files)
    assert source.stat().st_mtime_ns == before

    sync_cached_sketch(sketch, {"main.ino": "void setup(){ }\n"})
    assert source.read_text() == "void setup(){ }\n"
    assert not (sketch / "helper.cpp").exists()
