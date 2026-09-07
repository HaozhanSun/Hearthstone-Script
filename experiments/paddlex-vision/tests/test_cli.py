import json
import io

from paddlex_vision_experiment import cli
from paddlex_vision_experiment.provider import DetectedText


def test_cli_ocr_only_emits_text_contract(monkeypatch, tmp_path, capsys):
    image = tmp_path / "crop.png"
    image.write_bytes(b"fake image")

    class FakeOcrProvider:
        def __init__(self, *, device):
            assert device == "cpu"

        def recognize(self, image_path):
            assert image_path == image
            return (DetectedText("寻找", 0.9, (0.0, 0.0, 1.0, 1.0)), DetectedText("对手", 0.8, (1.0, 0.0, 2.0, 1.0)))

    monkeypatch.setattr(cli, "PaddleXOcrProvider", FakeOcrProvider)
    monkeypatch.setattr("sys.argv", ["paddlex-vision", "--ocr-only", "--input", str(image), "--device", "cpu"])

    assert cli.main() == 0
    payload = json.loads(capsys.readouterr().out)
    assert payload["schema_version"] == 1
    assert payload["ocr_text"] == "寻找对手"
    assert payload["objects"] == []
    assert payload["relations"] == []


def test_server_loads_provider_once_and_keeps_protocol_alive(monkeypatch, tmp_path, capsys):
    image = tmp_path / "crop.png"
    image.write_bytes(b"fake image")
    starts = 0
    calls = 0

    class FakeOcrProvider:
        def __init__(self, *, device):
            nonlocal starts
            assert device == "cpu"
            starts += 1

        def recognize(self, image_path):
            nonlocal calls
            calls += 1
            return (DetectedText("主菜单", 0.91, (0.0, 0.0, 1.0, 1.0)),)

    monkeypatch.setattr(cli, "PaddleXOcrProvider", FakeOcrProvider)
    monkeypatch.setattr(
        cli.sys,
        "stdin",
        io.StringIO(
            json.dumps({"request_id": "health-1", "op": "health"})
            + "\n"
            + json.dumps({"request_id": "ocr-1", "op": "ocr", "input": str(image)})
            + "\n"
            + json.dumps({"request_id": "ocr-2", "op": "ocr", "input": str(tmp_path / "missing.png")})
            + "\n",
        ),
    )

    assert cli.serve("cpu") == 0
    responses = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    assert starts == 1
    assert calls == 1
    assert responses[0] == {"schema_version": 1, "request_id": "health-1", "ok": True, "provider": "PADDLEX"}
    assert responses[1]["request_id"] == "ocr-1"
    assert responses[1]["ocr_text"] == "主菜单"
    assert responses[2]["request_id"] == "ocr-2"
    assert responses[2]["ok"] is False
