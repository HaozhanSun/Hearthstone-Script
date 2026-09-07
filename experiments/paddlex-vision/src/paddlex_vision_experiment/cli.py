"""Command line entry point for the isolated vision experiment."""

from __future__ import annotations

import argparse
import json
import sys
import traceback
from pathlib import Path

from .provider import PaddleXOcrProvider, PaddleXVisionProvider


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run PaddleX object detection + OCR")
    parser.add_argument("--input", type=Path, help="image to analyze")
    parser.add_argument("--device", default="cpu", help="PaddleX device, e.g. cpu or gpu:0")
    parser.add_argument("--json-out", type=Path, help="optional output JSON path")
    parser.add_argument("--ocr-only", action="store_true", help="run only OCR and emit the same JSON text contract")
    parser.add_argument(
        "--server",
        action="store_true",
        help="keep one OCR pipeline alive and serve newline-delimited JSON requests on stdin",
    )
    return parser


def _ocr_result(image: Path, provider: PaddleXOcrProvider) -> dict[str, object]:
    texts = provider.recognize(image)
    return {
        "schema_version": 1,
        "input": str(image),
        "ocr_text": "".join(item.text for item in texts if item.text),
        "objects": [],
        "texts": [item.as_dict() for item in texts],
        "relations": [],
    }


def _server_response(request: dict[str, object], provider: PaddleXOcrProvider) -> dict[str, object]:
    request_id = request.get("request_id")
    response: dict[str, object] = {"schema_version": 1, "request_id": request_id}
    operation = request.get("op", "ocr")
    if operation == "health":
        response.update({"ok": True, "provider": "PADDLEX"})
        return response
    if operation != "ocr":
        raise ValueError(f"unsupported operation: {operation}")
    input_value = request.get("input")
    if not isinstance(input_value, str) or not Path(input_value).is_file():
        raise ValueError(f"input image does not exist: {input_value}")
    response.update(_ocr_result(Path(input_value), provider))
    return response


def serve(device: str) -> int:
    """Load PaddleX once, then handle one JSON request per input line."""

    stdout = getattr(sys, "stdout", None)
    if stdout is not None and hasattr(stdout, "reconfigure"):
        stdout.reconfigure(encoding="utf-8", errors="replace")
    # Loading happens before the first read, so a successful health response
    # proves that the persistent process has initialized its pipeline.
    provider = PaddleXOcrProvider(device=device)
    for raw_line in sys.stdin:
        line = raw_line.strip()
        if not line:
            continue
        request_id: object = None
        try:
            request = json.loads(line)
            if not isinstance(request, dict):
                raise ValueError("request must be a JSON object")
            request_id = request.get("request_id")
            response = _server_response(request, provider)
        except Exception as error:  # keep the protocol alive for request-local errors
            response = {
                "schema_version": 1,
                "request_id": request_id,
                "ok": False,
                "error": f"{type(error).__name__}: {error}",
            }
            print(traceback.format_exc(), file=sys.stderr, flush=True)
        print(json.dumps(response, ensure_ascii=False), flush=True)
    return 0


def main() -> int:
    stdout = getattr(sys, "stdout", None)
    if stdout is not None and hasattr(stdout, "reconfigure"):
        stdout.reconfigure(encoding="utf-8", errors="replace")

    args = build_parser().parse_args()
    if args.server:
        return serve(args.device)
    if args.input is None:
        raise SystemExit("--input is required unless --server is used")
    if not args.input.is_file():
        raise SystemExit(f"input image does not exist: {args.input}")
    if args.ocr_only:
        result = _ocr_result(args.input, PaddleXOcrProvider(device=args.device))
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
