import json

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
