"""Deterministic spatial association between object and OCR boxes."""

from __future__ import annotations

from dataclasses import dataclass
from math import hypot
from typing import Iterable, Sequence


@dataclass(frozen=True)
class Box:
    """Pixel-space rectangle in [x_min, y_min, x_max, y_max] order."""

    x_min: float
    y_min: float
    x_max: float
    y_max: float

    @classmethod
    def from_sequence(cls, value: Sequence[float]) -> "Box":
        if len(value) != 4:
            raise ValueError(f"expected four coordinates, got {value!r}")
        x_min, y_min, x_max, y_max = (float(part) for part in value)
        if x_max < x_min or y_max < y_min:
            raise ValueError(f"invalid box coordinates: {value!r}")
        return cls(x_min, y_min, x_max, y_max)

    @property
    def center(self) -> tuple[float, float]:
        return ((self.x_min + self.x_max) / 2, (self.y_min + self.y_max) / 2)

    @property
    def width(self) -> float:
        return self.x_max - self.x_min

    @property
    def height(self) -> float:
        return self.y_max - self.y_min


@dataclass(frozen=True)
class Relation:
    object_index: int
    text_index: int
    relation: str
    distance: float

    def as_dict(self) -> dict[str, object]:
        return {
            "object_index": self.object_index,
            "text_index": self.text_index,
            "relation": self.relation,
            "distance": round(self.distance, 3),
        }


def _relation(object_box: Box, text_box: Box) -> tuple[str, float] | None:
    """Return a relation when boxes are near and directionally compatible."""

    object_center_x, object_center_y = object_box.center
    text_center_x, text_center_y = text_box.center
    max_gap = max(object_box.width, object_box.height, text_box.width, text_box.height) * 2.5
    dx = text_center_x - object_center_x
    dy = text_center_y - object_center_y

    horizontal_overlap = min(object_box.x_max, text_box.x_max) - max(
        object_box.x_min, text_box.x_min
    )
    vertical_overlap = min(object_box.y_max, text_box.y_max) - max(
        object_box.y_min, text_box.y_min
    )

    if horizontal_overlap >= 0 and abs(dy) <= max_gap:
        return ("below" if dy >= 0 else "above", abs(dy))
    if vertical_overlap >= 0 and abs(dx) <= max_gap:
        return ("right_of" if dx >= 0 else "left_of", abs(dx))

    # For separated boxes, use center distance but only when the nearest axis
    # is clearly dominant. This avoids associating unrelated text diagonally.
    distance = hypot(dx, dy)
    if distance > max_gap * 1.5:
        return None
    if abs(dx) >= abs(dy) * 1.5:
        return ("right_of" if dx >= 0 else "left_of", distance)
    if abs(dy) >= abs(dx) * 1.5:
        return ("below" if dy >= 0 else "above", distance)
    return None


def associate_objects_and_text(
    objects: Iterable[Sequence[float]], texts: Iterable[Sequence[float]]
) -> list[Relation]:
    """Associate each object with its nearest compatible text box."""

    object_boxes = [Box.from_sequence(box) for box in objects]
    text_boxes = [Box.from_sequence(box) for box in texts]
    relations: list[Relation] = []
    for object_index, object_box in enumerate(object_boxes):
        candidates: list[Relation] = []
        for text_index, text_box in enumerate(text_boxes):
            relation = _relation(object_box, text_box)
            if relation is not None:
                direction, distance = relation
                candidates.append(Relation(object_index, text_index, direction, distance))
        if candidates:
            relations.append(min(candidates, key=lambda candidate: candidate.distance))
    return relations
