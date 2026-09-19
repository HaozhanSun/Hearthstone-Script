#!/usr/bin/env python3
"""Opt-in, metadata-only Windows process audit collector.

The collector never launches a target, reads allowlisted file contents, or
installs/configures an OS tracing provider. It records bounded snapshots and
can normalize externally captured Sysmon/ETW/Procmon records supplied by the
operator.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

SCHEMA_VERSION = "1"
MAX_DURATION_SECONDS = 24 * 60 * 60
MAX_INTERVAL_SECONDS = 60 * 60


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def canonical_path(value: str | os.PathLike[str]) -> str:
    return os.path.normcase(os.path.realpath(os.path.abspath(os.fspath(value))))


@dataclass(frozen=True)
class AllowlistEntry:
    name: str
    root: str

    def __post_init__(self) -> None:
        object.__setattr__(self, "root", canonical_path(self.root))


def _under(path: str, root: str) -> bool:
    try:
        return os.path.commonpath((canonical_path(path), root)) == root
    except ValueError:
        return False


def redact_path(value: Any, allowlist: Sequence[AllowlistEntry]) -> str | None:
    if value is None:
        return None
    raw = os.fspath(value)
    for entry in allowlist:
        absolute = canonical_path(raw)
        if _under(absolute, entry.root):
            relative = os.path.relpath(absolute, entry.root)
            return entry.name if relative == "." else f"{entry.name}/{relative.replace(os.sep, '/')}"
    return "<redacted>"


def _safe_int(value: Any) -> int | None:
    try:
        return int(value) if value not in (None, "") else None
    except (TypeError, ValueError):
        return None


def _listify(value: Any) -> list[Any]:
    if value is None:
        return []
    return value if isinstance(value, list) else [value]


def normalize_event(raw: Mapping[str, Any], *, run_id: str, channel: str, build: str,
                    target_pid: int | None = None,
                    allowlist: Sequence[AllowlistEntry] = ()) -> dict[str, Any]:
    """Normalize one external event without retaining contents or raw paths."""
    fields = {str(key).lower(): value for key, value in raw.items()}
    source = str(fields.get("source") or fields.get("provider") or "unknown").lower()
    event_type = str(fields.get("event_type") or fields.get("eventtype") or fields.get("type") or "unknown")
    normalized: dict[str, Any] = {
        "schema": SCHEMA_VERSION, "timestamp": str(raw.get("timestamp") or utc_now()),
        "run_id": run_id, "channel": channel, "build": build,
        "source": source, "event_type": event_type,
    }
    for key in ("pid", "parent_pid", "target_pid", "access_mask", "status", "dropped_count"):
        if key in fields or key.replace("_", "") in fields:
            normalized[key] = _safe_int(fields.get(key, fields.get(key.replace("_", ""))))
    if fields.get("image"):
        image = os.fspath(fields["image"])
        normalized["image"] = os.path.basename(image) if not os.path.dirname(image) else redact_path(image, allowlist)
    for key in ("image_path", "module", "module_path", "file", "path", "registry_path"):
        if key in fields:
            redacted = redact_path(fields[key], allowlist)
            if redacted is not None:
                normalized[key] = redacted
    for key in ("local_address", "local_port", "remote_address", "remote_port", "protocol", "state"):
        if key in fields:
            normalized[key] = fields[key]
    if "dns_name" in fields:
        normalized["dns_name_present"] = bool(fields["dns_name"])
    for key in ("operation", "result", "signature_status", "hash_sha256", "command_line_present"):
        if key in fields:
            normalized[key] = fields[key]
    if isinstance(fields.get("modules"), list):
        names = [os.path.basename(os.fspath(item)) for item in fields["modules"] if item]
        normalized["module_count"] = len(names)
        normalized["module_names"] = sorted(set(names))
    if target_pid is not None:
        normalized["target_pid"] = target_pid
    normalized["correlation"] = correlate_pid_build(normalized, run_id=run_id, pid=target_pid, build=build)
    return normalized


def correlate_pid_build(event: Mapping[str, Any], *, run_id: str, pid: int | None, build: str) -> str:
    if event.get("run_id") != run_id:
        return "run_mismatch"
    target = pid if pid is not None else _safe_int(event.get("target_pid"))
    event_pid = _safe_int(event.get("pid"))
    if target is not None and event_pid not in (target, _safe_int(event.get("target_pid"))):
        return "pid_out_of_scope"
    if str(event.get("build", "")) != build:
        return "build_mismatch"
    return "correlated"


@dataclass
class EvidenceWriter:
    output: Path
    previous_hash: str = "0" * 64
    event_count: int = 0

    def __post_init__(self) -> None:
        if not self.output.exists() or self.output.stat().st_size == 0:
            return
        ok, message = verify_evidence(self.output)
        if not ok:
            raise ValueError(f"refusing to append to invalid evidence: {message}")
        with self.output.open("r", encoding="utf-8") as stream:
            records = [json.loads(line) for line in stream if line.strip()]
        self.previous_hash = records[-1]["record_hash"]
        self.event_count = len(records)

    def append(self, event: Mapping[str, Any]) -> dict[str, Any]:
        body = dict(event)
        body.setdefault("schema", SCHEMA_VERSION)
        body.setdefault("timestamp", utc_now())
        body["previous_hash"] = self.previous_hash
        canonical = json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        body["record_hash"] = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        self.output.parent.mkdir(parents=True, exist_ok=True)
        with self.output.open("a", encoding="utf-8", newline="\n") as stream:
            stream.write(json.dumps(body, ensure_ascii=False, sort_keys=True) + "\n")
        self.previous_hash = body["record_hash"]
        self.event_count += 1
        return body


def verify_evidence(path: Path) -> tuple[bool, str]:
    previous = "0" * 64
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        return False, f"read_error:{exc}"
    for index, line in enumerate(lines, 1):
        try:
            item = json.loads(line)
            actual_hash = item.pop("record_hash")
            if item.get("previous_hash") != previous:
                return False, f"chain_break:{index}"
            canonical = json.dumps(item, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            expected_hash = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
            if actual_hash != expected_hash:
                return False, f"hash_mismatch:{index}"
            previous = actual_hash
        except (KeyError, json.JSONDecodeError, TypeError) as exc:
            return False, f"invalid_record:{index}:{exc}"
    return True, f"ok:{len(lines)}"


def _powershell_json(script: str) -> Any:
    executable = "powershell.exe" if os.name == "nt" else "powershell"
    try:
        completed = subprocess.run([executable, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script],
                                   check=False, capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError(f"powershell_unavailable:{type(exc).__name__}") from exc
    if completed.returncode != 0:
        raise RuntimeError(f"powershell_failed:{completed.returncode}")
    if not completed.stdout.strip():
        return []
    try:
        return json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError("powershell_invalid_json") from exc


def collect_windows_snapshot(pids: set[int]) -> dict[str, Any]:
    if os.name != "nt":
        return {"available": False, "reason": "windows_only", "processes": [], "connections": []}
    ps = r'''
$targets = @(PIDS)
$all = @(Get-CimInstance Win32_Process)
$selected = [System.Collections.Generic.HashSet[int]]::new()
foreach ($target in $targets) { [void]$selected.Add([int]$target) }
$changed = $true
while ($changed) {
  $changed = $false
  foreach ($candidate in $all) {
    if ($selected.Contains([int]$candidate.ParentProcessId) -and $selected.Add([int]$candidate.ProcessId)) { $changed = $true }
  }
}
$processes = @($all | Where-Object { $selected.Contains([int]$_.ProcessId) } | ForEach-Object {
  $hash = $null; $sig = $null
  if ($_.ExecutablePath -and (Test-Path -LiteralPath $_.ExecutablePath)) {
    try { $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.ExecutablePath -ErrorAction Stop).Hash.ToLowerInvariant() } catch {}
    try { $sig = (Get-AuthenticodeSignature -LiteralPath $_.ExecutablePath -ErrorAction Stop).Status.ToString() } catch {}
  }
  $modules = @()
  try { $modules = @(Get-Process -Id ([int]$_.ProcessId) -ErrorAction Stop | Select-Object -ExpandProperty Modules -ErrorAction Stop | ForEach-Object { $_.FileName }) } catch {}
  [pscustomobject]@{ pid=[int]$_.ProcessId; parent_pid=[int]$_.ParentProcessId; image=$_.Name; image_path=$_.ExecutablePath; hash_sha256=$hash; signature_status=$sig; command_line_present=[bool]$_.CommandLine; modules=$modules }
})
$connections = @(Get-NetTCPConnection -ErrorAction SilentlyContinue | Where-Object { $targets -contains [int]$_.OwningProcess } | ForEach-Object {
  [pscustomobject]@{ pid=[int]$_.OwningProcess; protocol='tcp'; local_address=$_.LocalAddress; local_port=[int]$_.LocalPort; remote_address=$_.RemoteAddress; remote_port=[int]$_.RemotePort; state=$_.State.ToString() }
})
[pscustomobject]@{ processes=$processes; connections=$connections } | ConvertTo-Json -Depth 4 -Compress
'''.replace("PIDS", ",".join(str(pid) for pid in sorted(pids)))
    try:
        data = _powershell_json(ps)
    except RuntimeError as exc:
        return {"available": False, "reason": str(exc), "processes": [], "connections": []}
    if not isinstance(data, dict):
        return {"available": False, "reason": "unexpected_shape", "processes": [], "connections": []}
    return {"available": True, "processes": _listify(data.get("processes")), "connections": _listify(data.get("connections"))}


def collect_allowlisted_file_metadata(entries: Sequence[AllowlistEntry]) -> list[dict[str, Any]]:
    records = []
    for entry in entries:
        root = Path(entry.root)
        candidates: Iterable[Path]
        if root.is_file():
            candidates = (root,)
        elif root.is_dir():
            candidates = (item for item in root.iterdir() if item.is_file())
        else:
            records.append({"path": entry.name, "exists": False})
            continue
        for item in candidates:
            try:
                stat = item.stat()
            except OSError:
                continue
            records.append({"path": redact_path(str(item), entries), "exists": True,
                            "size": stat.st_size, "mtime_ns": stat.st_mtime_ns})
    return records


def run(args: argparse.Namespace) -> int:
    if not 0 <= args.duration <= MAX_DURATION_SECONDS or not 0 < args.interval <= MAX_INTERVAL_SECONDS:
        raise SystemExit("duration/interval outside safe bounds")
    run_id = args.run_id or uuid.uuid4().hex
    allowlist = [AllowlistEntry(name, root) for name, root in args.allow]
    writer = EvidenceWriter(Path(args.output))
    writer.append({"event_type": "run_start", "run_id": run_id, "channel": args.channel, "build": args.build,
                   "collector": "windows_snapshot", "metadata_only": True,
                   "sources": {"snapshot": "powershell/CIM", "process_access": "external Sysmon/ETW/Procmon only",
                               "registry_access": "external Sysmon/ETW/Procmon only", "dns": "not captured"},
                   "dropped_count": 0})
    deadline = time.monotonic() + args.duration
    known_pids = {args.pid} if args.pid else set()
    while True:
        snapshot = collect_windows_snapshot(known_pids)
        for process in snapshot["processes"]:
            normalized = normalize_event(process, run_id=run_id, channel=args.channel, build=args.build,
                                         target_pid=args.pid, allowlist=allowlist)
            writer.append({**normalized, "event_type": "process_snapshot"})
            pid = _safe_int(process.get("pid"))
            if pid is not None:
                known_pids.add(pid)
        for connection in snapshot["connections"]:
            writer.append(normalize_event(connection, run_id=run_id, channel=args.channel, build=args.build,
                                          target_pid=args.pid, allowlist=allowlist))
        writer.append({"event_type": "collector_status", "run_id": run_id, "channel": args.channel, "build": args.build,
                       "available": snapshot["available"], "reason": snapshot.get("reason"),
                       "dropped_count": 0 if snapshot["available"] else 1})
        if allowlist:
            writer.append({"event_type": "allowlisted_file_snapshot", "run_id": run_id, "channel": args.channel,
                           "build": args.build, "files": collect_allowlisted_file_metadata(allowlist)})
        if time.monotonic() >= deadline:
            break
        time.sleep(min(args.interval, max(0, deadline - time.monotonic())))
    writer.append({"event_type": "run_end", "run_id": run_id, "channel": args.channel, "build": args.build,
                   "event_count": writer.event_count, "dropped_count": 0})
    return 0


def parser() -> argparse.ArgumentParser:
    cli = argparse.ArgumentParser(description="Opt-in metadata-only Windows process audit collector")
    cli.add_argument("--pid", type=int, help="target PID; the collector never launches it")
    cli.add_argument("--channel", required=True, help="operator label, e.g. international or mainland-cn")
    cli.add_argument("--build", required=True, help="operator-supplied game/build label")
    cli.add_argument("--output", required=True, help="JSONL evidence path")
    cli.add_argument("--duration", type=int, default=60, help="capture duration, 0 for one snapshot")
    cli.add_argument("--interval", type=int, default=5, help="snapshot interval in seconds")
    cli.add_argument("--run-id", help="stable correlation id; generated if omitted")
    cli.add_argument("--allow", nargs=2, action="append", metavar=("NAME", "ROOT"), default=[],
                     help="allowlist label and file/directory root; repeatable")
    cli.add_argument("--verify", action="store_true", help="verify an existing JSONL evidence chain")
    return cli


def main(argv: Sequence[str] | None = None) -> int:
    cli = parser()
    args = cli.parse_args(argv)
    if args.verify:
        ok, message = verify_evidence(Path(args.output))
        print(message)
        return 0 if ok else 1
    return run(args)


if __name__ == "__main__":
    main()
