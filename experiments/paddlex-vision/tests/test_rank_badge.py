from pathlib import Path

from paddlex_vision_experiment import RankBadgeBounds, RankBadgeProbe, parse_numeric_rank
from paddlex_vision_experiment.provider import DetectedText


def test_rank_parser_rejects_ambiguous_one_and_noise():
    assert parse_numeric_rank("1") == 1
    assert parse_numeric_rank("10") == 10
    assert parse_numeric_rank("１") == 1
    assert parse_numeric_rank("1|39") is None
    assert parse_numeric_rank("") is None


def test_rank_roi_matches_the_saved_1920_by_1080_layout():
    assert RankBadgeBounds().pixels(1920, 1080) == (0, 920, 100, 1010)


def test_probe_crops_and_upscales_before_ocr(tmp_path):
    seen: list[tuple[int, int, int, int]] = []

    class FakeOcr:
        def recognize(self, image: str | Path):
            from PIL import Image

            with Image.open(image) as cropped:
                seen.append(cropped.size)
            return (DetectedText("10", 1.0, (0.0, 0.0, 1.0, 1.0)),)

    from PIL import Image

    image = tmp_path / "rank-fixture.png"
    Image.new("RGB", (1920, 1080), "black").save(image)
    result = RankBadgeProbe(FakeOcr()).recognize(image)

    assert result.rank == 10
    assert result.raw_text == "10"
    assert seen == [(400, 360)]
