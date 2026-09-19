# Windows process-audit feasibility report

## Verdict

Feasible as an optional, metadata-only diagnostic aid. The safe collector can
correlate a run ID, channel/build label, target PID, process ancestry,
executable identity, module/process snapshots, allowlisted file metadata, and
target-scoped socket metadata. It cannot by itself answer whether Hearthstone
or Blizzard historically opened/read a script log, read memory, inspected the
registry, opened a process handle, or issued a DNS query. Those event-level
questions require an independently configured OS telemetry source.

The implementation is `tools/process-audit/audit.py` and is not wired into the
Maven build or runtime. It is therefore off by default and does not change the
shipped application unless explicitly run.

| Question | Safe local signal | Required source | Proof limit |
|---|---|---|---|
| Process/build identity | PID, parent PID, image, optional hash/signature, operator label | PowerShell/CIM; hash/signature may need access | Labels are operator-supplied; PID reuse is possible |
| Loaded modules | Point-in-time module names | Target permissions | No historical load/unload timeline |
| Process handle or memory access | Sysmon process-access or suitable ETW/kernel telemetry | Pre-enabled provider, often elevation | Coverage varies; handle presence is not proof of memory inspection |
| Log/file opens and reads | Filtered Sysmon file events, ETW File I/O, or Procmon | Pre-enabled provider and path filters | Size/mtime does not prove a read |
| Registry access | Filtered Sysmon registry events, ETW, or Procmon | Pre-enabled provider, often elevation | Missing provider means unknown, not absent |
| Network/DNS | Target-PID socket snapshots; external DNS telemetry | PowerShell plus DNS ETW/firewall logs | No payload, intent, or complete historical DNS proof |
| Lost events | Provider dropped-event counters and fallback records | Provider/export support | Zero/unreported counters do not prove completeness |

The tool never collects file contents, command-line text, keystrokes,
screenshots, credentials, memory contents, packet contents, or unrelated
full-disk telemetry. Paths outside the explicit allowlist are redacted.
Evidence is JSONL with a SHA-256 previous-record chain so later editing is
detectable.

## Interpretation

Run separate fresh international and mainland-China captures with matched
Windows version, script version, account state, locale/settings, firewall,
actions, provider configuration, and duration. Retain provider configuration
and dropped counts. A live matched comparison is still required for any
empirical claim; this repository change alone cannot prove that one build is
spying or that another is not.

## Offline verification

```powershell
py -3 -m unittest discover -s tools/process-audit/tests -p "test_*.py" -v
py -3 -m py_compile tools/process-audit/audit.py tools/process-audit/tests/test_audit.py
py -3 tools/process-audit/audit.py --help
```
