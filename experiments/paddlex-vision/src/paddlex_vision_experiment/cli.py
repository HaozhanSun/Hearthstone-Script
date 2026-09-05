"""Command line entry point for the isolated vision experiment."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .provider import PaddleXOcrProvider, PaddleXVisionProvider


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run PaddleX object detection + OCR")
    parser.add_argument("--input", required=True, type=Path, help="image to analyze")
    parser.add_argument("--device", default="cpu", help="PaddleX device, e.g. cpu or gpu:0")
    parser.add_argument("--json-out", type=Path, help="optional output JSON path")
    parser.add_argument("--ocr-only", action="store_true", help="run only OCR and emit the same JSON text contract")
    return parser


def main() -> int:
    stdout = getattr(sys, "stdout", None)
    if stdout is not None and hasattr(stdout, "reconfigure"):
        stdout.reconfigure(encoding="utf-8", errors="replace")

    args = build_parser().parse_args()
    if not args.input.is_file():
        raise SystemExit(f"input image does not exist: {args.input}")
    if args.ocr_only:
        texts = PaddleXOcrProvider(device=args.device).recognize(args.input)
        result = {
            "schema_version": 1,
            "input": str(args.input),
            "ocr_text": "".join(item.text for item in texts if item.text),
            "objects": [],
            "texts": [item.as_dict() for item in texts],
            "relations": [],
        }
    else:
        result = PaddleXVisionProvider(device=args.device).analyze(args.input).as_dict()
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
