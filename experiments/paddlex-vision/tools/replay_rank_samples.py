"""Replay retained Hearthstone rank screenshots with the real PaddleX OCR.

The tool is intentionally offline with respect to Hearthstone: it reads only
the supplied images, writes evidence outside the application log, and never
changes application state. Use ``--sample id=path`` once per source image.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil

from PIL import Image, ImageDraw, ImageFont

from paddlex_vision_experiment import RankBadgeBounds, RankBadgeProbe
from paddlex_vision_experiment.provider import PaddleXOcrProvider


@dataclass
class ReplayResult:
    sample_id: str
    source: str
    provider: str
    roi: tuple[int, int, int, int]
    raw_ocr: str
    normalized_ocr: str
    numeric_rank: int | None
    confidence: float | None
    tier: str
    unknown_reason: str
    final_decision: str
    human_visible_rank: str
    source_note: str
    original_copy: str | None = None
    annotated_image: str | None = None
    error: str | None = None


def parse_sample(value: str) -> tuple[str, Path]:
    sample_id, separator, source = value.partition("=")
    if not separator or not sample_id or not source:
        raise argparse.ArgumentTypeError("sample must be id=path")
    return sample_id, Path(source)


def parse_metadata(value: str) -> tuple[str, str]:
    sample_id, separator, text = value.partition("=")
    if not separator or not sample_id:
        raise argparse.ArgumentTypeError("metadata must be id=value")
    return sample_id, text


def annotate(source: Path, destination: Path, result: ReplayResult) -> None:
    with Image.open(source).convert("RGB") as image:
        canvas = image.copy()
    draw = ImageDraw.Draw(canvas)
    font = ImageFont.load_default()
    x, y, right, bottom = result.roi
    draw.rectangle((x, y, right - 1, bottom - 1), outline=(255, 35, 35), width=8)
    lines = [
        f"time={datetime.now(timezone.utc).isoformat(timespec='milliseconds')}",
        "stage=historical-rank-replay",
        f"provider={result.provider}",
        f"roi=x={x} y={y} w={right - x} h={bottom - y}",
        f"rawOCR={result.raw_ocr or '<empty>'}",
        f"normalizedOCR={result.normalized_ocr or '<empty>'}",
        f"numericRank={result.numeric_rank if result.numeric_rank is not None else 'UNKNOWN'}",
        f"confidence={result.confidence if result.confidence is not None else 'UNKNOWN'}",
        f"tier={result.tier}",
        f"unknownReason={result.unknown_reason}",
        f"finalDecision={result.final_decision}",
    ]
    line_height = 12
    panel_width = min(canvas.width - 24, max(260, max(draw.textlength(line, font=font) for line in lines) + 16))
    panel_height = min(canvas.height - 24, len(lines) * line_height + 16)
    panel = (12, 12, 12 + panel_width, 12 + panel_height)
    draw.rectangle(panel, fill=(0, 0, 0), outline=(110, 210, 255), width=2)
    for index, line in enumerate(lines):
        draw.text((20, 20 + index * line_height), line[:180], fill=(255, 255, 255), font=font)
    destination.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(destination)


def replay(
    samples: list[tuple[str, Path]],
    output: Path,
    human_visible: dict[str, str],
    notes: dict[str, str],
) -> list[ReplayResult]:
    output.mkdir(parents=True, exist_ok=True)
    provider = PaddleXOcrProvider(device="cpu")
    bounds = RankBadgeBounds()
    results: list[ReplayResult] = []
    for sample_id, source in samples:
        result = ReplayResult(
            sample_id=sample_id,
            source=str(source),
            provider="PADDLEX",
            roi=(0, 0, 0, 0),
            raw_ocr="",
            normalized_ocr="",
            numeric_rank=None,
            confidence=None,
            tier="UNKNOWN",
            unknown_reason="not-run",
            final_decision="REPLAY_FAILED",
            human_visible_rank=human_visible.get(sample_id, "UNKNOWN"),
            source_note=notes.get(sample_id, ""),
        )
        try:
            if not source.is_file():
                raise FileNotFoundError(source)
            with Image.open(source) as image:
                left, top, right, bottom = bounds.pixels(image.width, image.height)
            replay_result = RankBadgeProbe(provider, bounds=bounds).recognize(source)
            result.roi = (left, top, right, bottom)
            result.raw_ocr = replay_result.raw_text
            result.normalized_ocr = "".join(replay_result.raw_text.split())
            result.numeric_rank = replay_result.rank
            result.confidence = max((text.score for text in replay_result.texts), default=None)
            result.unknown_reason = "none" if replay_result.rank is not None else "numeric-rank-unresolved"
            result.final_decision = "RANK_RESOLVED" if replay_result.rank is not None else "UNKNOWN_FAIL_CLOSED"
            original = output / "original" / f"{sample_id}.png"
            original.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, original)
            result.original_copy = str(original)
            annotated_path = output / "annotated" / f"{sample_id}.png"
            annotate(source, annotated_path, result)
            result.annotated_image = str(annotated_path)
        except Exception as error:  # evidence must retain failed samples too
            result.error = f"{type(error).__name__}: {error}"
            result.unknown_reason = "replay-error"
        results.append(result)
    (output / "rank-replay.json").write_text(
        json.dumps([asdict(result) for result in results], ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return results


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--sample", required=True, action="append", type=parse_sample)
    parser.add_argument("--human-visible", action="append", default=[], metavar="ID=RANK")
    parser.add_argument("--note", action="append", default=[], metavar="ID=TEXT")
    args = parser.parse_args()
    human_visible = dict(parse_metadata(value) for value in args.human_visible)
    notes = dict(parse_metadata(value) for value in args.note)
    results = replay(args.sample, args.output, human_visible, notes)
    for result in results:
        print(json.dumps(asdict(result), ensure_ascii=False))
    return 0 if all(result.error is None for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
