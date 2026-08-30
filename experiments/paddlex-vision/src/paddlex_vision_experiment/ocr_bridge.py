"""Text-only compatibility bridge for the existing OCR consumer shape."""

from __future__ import annotations

from pathlib import Path
from typing import Protocol

from .provider import PaddleXVisionProvider


class _VisionProvider(Protocol):
    def analyze(self, image: str | Path): ...


class PaddleXOcrBridge:
    """Expose PaddleX OCR as a ``do_ocr(image) -> str`` sidecar seam.

    The production Kotlin/Tess4J class is deliberately untouched. A future
    integration can invoke this bridge out of process and consume the same
    flattened text contract.
    """

    def __init__(self, provider: _VisionProvider | None = None, *, device: str = "cpu") -> None:
        self._provider = provider or PaddleXVisionProvider(device=device)

    def do_ocr(self, image: str | Path) -> str:
        return self._provider.analyze(image).ocr_text
