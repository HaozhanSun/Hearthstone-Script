import os
from pathlib import Path

import pytest

from paddlex_vision_experiment import PaddleXVisionProvider


@pytest.mark.paddlex
def test_live_paddlex_fixture():
    fixture = os.environ.get("PADDLEX_VISION_FIXTURE")
    if not fixture:
        pytest.skip("set PADDLEX_VISION_FIXTURE to run the live model smoke test")
    image = Path(fixture)
    if not image.is_file():
        pytest.fail(f"PADDLEX_VISION_FIXTURE does not exist: {image}")

    result = PaddleXVisionProvider(device=os.environ.get("PADDLEX_DEVICE", "cpu")).analyze(image)

    assert result.as_dict()["schema_version"] == 1
    assert isinstance(result.ocr_text, str)
