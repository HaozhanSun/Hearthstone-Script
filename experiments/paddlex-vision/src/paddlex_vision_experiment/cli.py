"""Command line entry point for the isolated vision experiment."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .provider import PaddleXVisionProvider


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run PaddleX object detection + OCR")
    parser.add_argument("--input", required=True, type=Path, help="image to analyze")
    parser.add_argument("--device", default="cpu", help="PaddleX device, e.g. cpu or gpu:0")
    parser.add_argument("--json-out", type=Path, help="optional output JSON path")
    return parser


def main() -> int:
    stdout = getattr(sys, "stdout", None)
    if stdout is not None and hasattr(stdout, "reconfigure"):
        stdout.reconfigure(encoding="utf-8", errors="replace")

    args = build_parser().parse_args()
    if not args.input.is_file():
        raise SystemExit(f"input image does not exist: {args.input}")
    result = PaddleXVisionProvider(device=args.device).analyze(args.input).as_dict()
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
