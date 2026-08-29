"""Controlled PaddleX OCR probe for the lower-left Hearthstone rank badge.

This is an offline experiment only. It intentionally does not alter the
production Kotlin detector or make surrender decisions.
"""

from __future__ import annotations

from dataclasses import dataclass
import re
from pathlib import Path
import tempfile
from typing import Protocol

from .provider import DetectedText


@dataclass(frozen=True)
class RankBadgeBounds:
    """Normalized full-screen ROI; tuned from the saved 1920x1080 captures."""

    left: float = 0.0
    top: float = 0.852
    width: float = 0.0521
    height: float = 0.0833
    scale: int = 4

    def pixels(self, image_width: int, image_height: int) -> tuple[int, int, int, int]:
        left = round(image_width * self.left)
        top = round(image_height * self.top)
        right = round(image_width * (self.left + self.width))
        bottom = round(image_height * (self.top + self.height))
        return left, top, min(image_width, right), min(image_height, bottom)


@dataclass(frozen=True)
class RankBadgeResult:
    raw_text: str
    rank: int | None
    texts: tuple[DetectedText, ...]


class _OcrProvider(Protocol):
    def recognize(self, image: str | Path) -> tuple[DetectedText, ...]: ...


def parse_numeric_rank(raw_text: str) -> int | None:
    """Accept only a single clean rank token, with explicit precedence for 10."""

    normalized = raw_text.translate(str.maketrans("０１２３４５６７８９", "0123456789"))
    runs = re.findall(r"\d{1,2}", normalized)
    if "10" in runs:
        return 10
    digits = "".join(re.findall(r"\d", normalized))
    if len(digits) != 1:
        return None
    value = int(digits)
    return value if 1 <= value <= 9 else None


class RankBadgeProbe:
    """Crop, upscale, and OCR only the numeric badge region."""

    def __init__(self, ocr_provider: _OcrProvider, bounds: RankBadgeBounds | None = None) -> None:
        self._ocr_provider = ocr_provider
        self._bounds = bounds or RankBadgeBounds()

    def recognize(self, image: str | Path) -> RankBadgeResult:
        try:
            from PIL import Image
        except ImportError as error:
            raise RuntimeError("Pillow is required for the rank-badge experiment") from error

        image_path = Path(image)
        with Image.open(image_path) as source:
            box = self._bounds.pixels(source.width, source.height)
            if box[2] <= box[0] or box[3] <= box[1]:
                raise ValueError(f"rank badge ROI is outside image bounds: {box}")
            crop = source.crop(box)
            crop = crop.resize(
                (crop.width * self._bounds.scale, crop.height * self._bounds.scale),
                Image.Resampling.LANCZOS,
            )
            with tempfile.TemporaryDirectory(prefix="paddlex-rank-") as directory:
                crop_path = Path(directory) / "rank-badge.png"
                crop.save(crop_path)
                texts = self._ocr_provider.recognize(crop_path)

        raw_text = "".join(item.text for item in texts)
        return RankBadgeResult(raw_text, parse_numeric_rank(raw_text), texts)
