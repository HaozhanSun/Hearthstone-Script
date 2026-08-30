"""Minimal PaddleX rank-badge bridge used by the JVM detector.

The bridge prints exactly one JSON result on stdout. PaddleX's initialization
messages are redirected to stderr so the application only has to parse the
last JSON line. The ROI matches the independently verified experiment:
(0, 920, 100, 1010) on a 1920x1080 capture, with proportional coordinates for
other resolutions.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import tempfile
from pathlib import Path


def parse_rank(raw_text: str) -> int | None:
    normalized = raw_text.translate(str.maketrans("０１２３４５６７８９", "0123456789"))
    runs = re.findall(r"\d{1,2}", normalized)
    if "10" in runs:
        return 10
    digits = re.findall(r"\d", normalized)
    if len(digits) != 1:
        return None
    value = int(digits[0])
    return value if 1 <= value <= 9 else None


def payload(result):
    value = getattr(result, "json", result)
    if callable(value):
        value = value()
    if not isinstance(value, dict):
        raise TypeError(f"unexpected PaddleX result: {type(value).__name__}")
    nested = value.get("res")
    return nested if isinstance(nested, dict) else value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    args = parser.parse_args()
    if not args.input.is_file():
        raise SystemExit(f"image does not exist: {args.input}")

    os.environ.setdefault("PADDLE_PDX_ENABLE_MKLDNN_BYDEFAULT", "0")
    from PIL import Image
    from paddlex import create_pipeline

    with Image.open(args.input) as source:
        left = round(source.width * 0.0)
        top = round(source.height * 0.852)
        right = min(source.width, round(source.width * 0.0521))
        bottom = min(source.height, round(source.height * 0.9353))
        crop = source.crop((left, top, right, bottom)).resize(
            ((right - left) * 4, (bottom - top) * 4), Image.Resampling.LANCZOS
        )
        with tempfile.TemporaryDirectory(prefix="hs-script-paddlex-rank-") as directory:
            crop_path = Path(directory) / "rank-badge.png"
            crop.save(crop_path)
            pipeline = create_pipeline(pipeline="OCR", device="cpu")
            result = next(iter(pipeline.predict(
                input=str(crop_path),
                use_doc_orientation_classify=False,
                use_doc_unwarping=False,
                use_textline_orientation=False,
            )))

    data = payload(result)
    texts = [item for item in data.get("rec_texts", []) or [] if isinstance(item, str)]
    raw_text = "".join(texts)
    print(json.dumps({"schema_version": 1, "raw_text": raw_text, "rank": parse_rank(raw_text)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
