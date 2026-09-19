# Optional Windows process audit

This is a bounded, opt-in diagnostic utility. It does not start Hearthstone,
Battle.net, or the script; inject into processes; install a driver/service; or
change Windows security policy. It writes JSONL metadata only and is off by
default because nothing in the Maven build invokes it.

Run one snapshot as the current user:

```powershell
py -3 tools/process-audit/audit.py --pid 1234 --channel international --build "build-label" --duration 0 --output audit.jsonl --allow script_logs C:\path\to\script\log
```

For a bounded observation window, set `--duration` and `--interval`. Only
explicitly allowlisted paths are represented as relative labels. All other
paths are `<redacted>` and file contents are never recorded. The output is
hash-chained and can be checked with:

```powershell
py -3 tools/process-audit/audit.py --verify --output audit.jsonl --channel unused --build unused
```

## What is observable

The snapshot records target-scoped process PID, parent PID, image name/path
after redaction, optional SHA-256 and Authenticode status, module names, and
target-scoped TCP endpoint metadata when PowerShell/CIM permits it. It also
records size/mtime metadata for explicitly allowlisted files. A failed
collector produces an explicit `collector_status` fallback event.

Historical process-handle access, memory reads, file opens/reads/writes,
registry access, and DNS queries are not inferred from snapshots. To observe
those, independently preconfigure a narrowly filtered Sysmon, ETW provider, or
Procmon capture and later normalize its metadata through the same schema. Such
capture may require administrator rights. This project does not install,
configure, or run those providers.

An ordinary process handle, visible log read, shared file, or network
connection is not proof of spying. A correlated external event establishes only
that a filtered provider observed an operation; it does not establish intent.

## Controlled comparison protocol

1. Record exact game channel/build, script version, Windows build, fresh run ID,
   PIDs, locale/settings, firewall state, allowlisted roots, and duration.
2. Start the same narrowly filtered external provider configuration for both
   builds, retaining provider configuration and dropped-event counters.
3. Run separate fresh international and mainland-China sessions with matched
   idle, launch, menu, match, and exit actions.
4. Use this collector as a separate current-user process; it never launches or
   attaches a debugger to the targets.
5. Verify each JSONL hash chain and compare operation type, redacted path class,
   target PID/image, endpoint metadata, provider availability, and dropped
   counts. Require repeated matched differences before attributing a behavior
   to a build.
