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
    """Normalized full-screen ROI for the inner numeric rank frame."""

    left: float = 0.01198
    top: float = 0.87130
    width: float = 0.02969
    height: float = 0.04352
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
    """Accept one numeric token and reject username/other Latin contamination."""

    normalized = raw_text.translate(str.maketrans("０１２３４５６７８９", "0123456789"))
    if re.search(r"[A-Za-z]", normalized):
        return None
    runs = re.findall(r"\d+", normalized)
    if len(runs) != 1:
        return None
    token = runs[0]
    if token == "10":
        return 10
    if len(token) != 1:
        return None
    value = int(token)
    return value if 1 <= value <= 9 else None


class RankBadgeProbe:
    """Crop, upscale, and OCR only the inner numeric rank region."""

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
