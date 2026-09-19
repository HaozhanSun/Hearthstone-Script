import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).parents[1] / "audit.py"
spec = importlib.util.spec_from_file_location("process_audit", MODULE_PATH)
audit = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = audit
spec.loader.exec_module(audit)


class AuditTests(unittest.TestCase):
    def test_event_normalization_redacts_content_and_normalizes_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "logs"
            event = audit.normalize_event(
                {"Provider": "Sysmon", "EventType": "FileCreate", "Pid": "42", "Path": str(root / "app.log"),
                 "Content": "must not be retained", "DnsName": "secret.example"},
                run_id="run", channel="international", build="b1", target_pid=42,
                allowlist=[audit.AllowlistEntry("script_logs", str(root))],
            )
        self.assertEqual(event["source"], "sysmon")
        self.assertEqual(event["event_type"], "FileCreate")
        self.assertEqual(event["pid"], 42)
        self.assertEqual(event["path"], "script_logs/app.log")
        self.assertNotIn("Content", event)
        self.assertNotIn("DnsName", event)
        self.assertEqual(event["correlation"], "correlated")

    def test_allowlist_and_path_redaction(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "logs"
            allowed = audit.AllowlistEntry("logs", str(root))
            self.assertEqual(audit.redact_path(str(root / "a.txt"), [allowed]), "logs/a.txt")
            self.assertEqual(audit.redact_path(str(Path(tmp) / "private.txt"), [allowed]), "<redacted>")
            self.assertEqual(audit.redact_path(str(root.parent / "logs2" / "x"), [allowed]), "<redacted>")

    def test_pid_and_build_correlation(self):
        base = {"run_id": "r", "build": "cn-1", "pid": 10}
        self.assertEqual(audit.correlate_pid_build(base, run_id="r", pid=10, build="cn-1"), "correlated")
        self.assertEqual(audit.correlate_pid_build(base, run_id="r2", pid=10, build="cn-1"), "run_mismatch")
        self.assertEqual(audit.correlate_pid_build(base, run_id="r", pid=11, build="cn-1"), "pid_out_of_scope")
        self.assertEqual(audit.correlate_pid_build(base, run_id="r", pid=10, build="intl-1"), "build_mismatch")

    def test_collector_unavailable_fallback_is_explicit(self):
        with mock.patch.object(audit, "_powershell_json", side_effect=RuntimeError("test_unavailable")):
            snapshot = audit.collect_windows_snapshot({999999})
        self.assertFalse(snapshot["available"])
        self.assertEqual(snapshot["reason"], "test_unavailable")
        self.assertEqual(snapshot["processes"], [])

    def test_evidence_hash_chain_detects_tampering(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "evidence.jsonl"
            writer = audit.EvidenceWriter(path)
            writer.append({"event_type": "one", "run_id": "r"})
            writer.append({"event_type": "two", "run_id": "r"})
            self.assertEqual(audit.verify_evidence(path), (True, "ok:2"))
            records = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
            records[0]["event_type"] = "tampered"
            path.write_text("\n".join(json.dumps(item) for item in records) + "\n", encoding="utf-8")
            ok, message = audit.verify_evidence(path)
            self.assertFalse(ok)
            self.assertIn("hash_mismatch:1", message)


if __name__ == "__main__":
    unittest.main()
