# E2E verification index

Use this page as the entry point for end-to-end work. Evidence from one run
must not be mixed with another build or process.

| Area | Guide | Use it for |
| --- | --- | --- |
| Exit gate | `../E2E_EXIT_CRITERION.md` | Required real-game outcome and stability evidence. |
| OCR replay | `offline-ocr/README.md` | Offline fixtures, provider routing, and fail-closed contracts. |
| OCR telemetry | `offline-ocr/telemetry.md` | Per-run counters, rates, event paths, and OCR gotchas. |
| Provider selection | `offline-ocr/provider-selection.md` | Persistent UI modes, routing semantics, and legacy reference delta. |
| Screen recovery | `offline-ocr/screen-recovery.md` | First-frame probes, recovery evidence, HOME/HUB re-entry, and screenshot retention. |

Before a real run, record the build/JAR hash, a fresh run id, application PID,
Power.log path, and evidence directory. Verify that no previous application or
Hearthstone process is still serving the stable launcher; an old process can
make a new run appear to use the wrong build. After the run, bind the provider
log, authoritative Power.log marker, screenshots, and process exit evidence to
that same run id.
