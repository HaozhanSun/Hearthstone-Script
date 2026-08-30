"""Isolated PaddleX object detection + OCR experiment."""

from .association import associate_objects_and_text
from .ocr_bridge import PaddleXOcrBridge
from .provider import (
    DetectedObject,
    DetectedText,
    PaddleXOcrProvider,
    PaddleXVisionProvider,
    VisionAnalysis,
)
from .rank_badge import RankBadgeBounds, RankBadgeProbe, RankBadgeResult, parse_numeric_rank

__all__ = [
    "DetectedObject",
    "DetectedText",
    "PaddleXVisionProvider",
    "PaddleXOcrProvider",
    "PaddleXOcrBridge",
    "VisionAnalysis",
    "associate_objects_and_text",
    "RankBadgeBounds",
    "RankBadgeProbe",
    "RankBadgeResult",
    "parse_numeric_rank",
]
