"""Lazy PaddleX adapter and stable JSON-facing result types."""

from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import sys
from typing import Any, Iterable

from .association import associate_objects_and_text


@dataclass(frozen=True)
class DetectedObject:
    label: str
    score: float
    bbox: tuple[float, float, float, float]

    def as_dict(self) -> dict[str, object]:
        return {"label": self.label, "score": self.score, "bbox": list(self.bbox)}


@dataclass(frozen=True)
class DetectedText:
    text: str
    score: float
    bbox: tuple[float, float, float, float]

    def as_dict(self) -> dict[str, object]:
        return {"text": self.text, "score": self.score, "bbox": list(self.bbox)}


@dataclass(frozen=True)
class VisionAnalysis:
    input_path: str
    objects: tuple[DetectedObject, ...]
    texts: tuple[DetectedText, ...]
    relations: tuple[dict[str, object], ...]

    @property
    def ocr_text(self) -> str:
        """Flatten OCR output for compatibility with text-only consumers."""

        return "".join(item.text for item in self.texts if item.text)

    def as_dict(self) -> dict[str, object]:
        return {
            "schema_version": 1,
            "input": self.input_path,
            "ocr_text": self.ocr_text,
            "objects": [item.as_dict() for item in self.objects],
            "texts": [item.as_dict() for item in self.texts],
            "relations": list(self.relations),
        }


def _payload(result: Any) -> dict[str, Any]:
    """Extract PaddleX's JSON payload from a result object or plain mapping."""

    value = getattr(result, "json", result)
    if callable(value):
        value = value()
    if not isinstance(value, dict):
        raise TypeError(f"unexpected PaddleX result type: {type(value).__name__}")
    nested = value.get("res")
    return nested if isinstance(nested, dict) else value


def _as_bbox(value: Any) -> tuple[float, float, float, float] | None:
    try:
        values = list(value)
    except TypeError:
        return None
    if len(values) != 4:
        return None
    try:
        x_min, y_min, x_max, y_max = (float(part) for part in values)
    except (TypeError, ValueError):
        return None
    if x_max < x_min or y_max < y_min:
        return None
    return (x_min, y_min, x_max, y_max)


def normalize_object_payload(payload: dict[str, Any]) -> tuple[DetectedObject, ...]:
    """Normalize PaddleX object-detection output for stable downstream use."""

    normalized: list[DetectedObject] = []
    for item in payload.get("boxes", []) or []:
        if not isinstance(item, dict):
            continue
        bbox = _as_bbox(item.get("coordinate", item.get("bbox")))
        label = item.get("label")
        if bbox is None or not isinstance(label, str) or not label:
            continue
        try:
            score = float(item.get("score", 0.0))
        except (TypeError, ValueError):
            score = 0.0
        normalized.append(DetectedObject(label, score, bbox))
    return tuple(normalized)


def normalize_ocr_payload(payload: dict[str, Any]) -> tuple[DetectedText, ...]:
    """Normalize PaddleX OCR output while preserving text-box alignment."""

    texts = payload.get("rec_texts", []) or []
    scores = payload.get("rec_scores", []) or []
    boxes = payload.get("rec_boxes", []) or []
    normalized: list[DetectedText] = []
    for index, text in enumerate(texts):
        if not isinstance(text, str):
            continue
        bbox = _as_bbox(boxes[index]) if index < len(boxes) else None
        if bbox is None:
            continue
        try:
            score = float(scores[index]) if index < len(scores) else 0.0
        except (TypeError, ValueError):
            score = 0.0
        normalized.append(DetectedText(text, score, bbox))
    return tuple(normalized)


class PaddleXVisionProvider:
    """Run PaddleX object detection and OCR without importing it at module load."""

    def __init__(
        self,
        *,
        device: str = "cpu",
        object_pipeline: str = "object_detection",
        ocr_pipeline: str = "OCR",
    ) -> None:
        # PaddlePaddle 3.x can hit a Windows oneDNN/PIR attribute path in the
        # default CPU runner. Keep this workaround scoped to the experiment and
        # allow callers to opt back into oneDNN after validating their runtime.
        if sys.platform == "win32" and os.environ.get("PADDLEX_DISABLE_MKLDNN", "1") == "1":
            os.environ.setdefault("PADDLE_PDX_ENABLE_MKLDNN_BYDEFAULT", "0")
        try:
            from paddlex import create_pipeline
        except ImportError as error:
            raise RuntimeError(
                "PaddleX is not installed. Install this experiment's runtime extra "
                "with: python -m pip install 'paddlex-vision-experiment[runtime]'"
            ) from error

        self._object_pipeline = create_pipeline(pipeline=object_pipeline, device=device)
        self._ocr_pipeline = create_pipeline(pipeline=ocr_pipeline, device=device)

    @staticmethod
    def _first(results: Iterable[Any]) -> Any:
        try:
            return next(iter(results))
        except StopIteration as error:
            raise RuntimeError("PaddleX returned no result") from error

    def analyze(self, image: str | Path) -> VisionAnalysis:
        image_path = str(Path(image))
        object_result = self._first(self._object_pipeline.predict(image_path))
        ocr_result = self._first(
            self._ocr_pipeline.predict(
                input=image_path,
                use_doc_orientation_classify=False,
                use_doc_unwarping=False,
                use_textline_orientation=False,
            )
        )
        objects = normalize_object_payload(_payload(object_result))
        texts = normalize_ocr_payload(_payload(ocr_result))
        relations = tuple(
            relation.as_dict()
            for relation in associate_objects_and_text(
                (item.bbox for item in objects), (item.bbox for item in texts)
            )
        )
        return VisionAnalysis(image_path, objects, texts, relations)


class PaddleXOcrProvider:
    """Lazy OCR-only PaddleX adapter for tightly controlled regions of interest."""

    def __init__(self, *, device: str = "cpu", ocr_pipeline: str = "OCR") -> None:
        if sys.platform == "win32" and os.environ.get("PADDLEX_DISABLE_MKLDNN", "1") == "1":
            os.environ.setdefault("PADDLE_PDX_ENABLE_MKLDNN_BYDEFAULT", "0")
        try:
            from paddlex import create_pipeline
        except ImportError as error:
            raise RuntimeError(
                "PaddleX is not installed. Install this experiment's runtime extra "
                "with: python -m pip install 'paddlex-vision-experiment[runtime]'"
            ) from error
        self._ocr_pipeline = create_pipeline(pipeline=ocr_pipeline, device=device)

    def recognize(self, image: str | Path) -> tuple[DetectedText, ...]:
        result = next(
            iter(
                self._ocr_pipeline.predict(
                    input=str(Path(image)),
                    use_doc_orientation_classify=False,
                    use_doc_unwarping=False,
                    use_textline_orientation=False,
                )
            )
        )
        return normalize_ocr_payload(_payload(result))
