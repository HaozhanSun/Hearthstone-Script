# Runtime robustness audit — 2026-09-13

Scope: the integrated runtime plus the focus/OCR and MCTS recovery work already
present in this line. No Hearthstone process was started and no online E2E result
is claimed.

## Priority findings

| Priority | Finding | Disposition |
| --- | --- | --- |
| P0 | `PowerLogListener.dealNewLog()` called phase/state code without a per-line fault boundary. A malformed event, stale model, or plugin exception could escape the scheduled callback and leave log progress dependent on a later listener restart. | Fixed: consume the already-read line, record structured failure context, use bounded exponential backoff, and continue from the next observation. No failed input is replayed. |
| P0 | `Core.restart()` had no duplicate-request gate. Screen recovery, log-size protection, and timeout services could queue overlapping kill/start sequences. | Fixed: one in-flight restart at a time, with release on success, failure, or executor rejection. |
| P1 | A transient `IOException` while a phase read was being replaced during client restart was wrapped in `RuntimeException`. | Fixed: log the phase/read failure and yield so the outer listener can rescan/re-plan. |
| P1 | Screen recovery can still be inconclusive when capture or OCR has no authoritative anchor. | Existing behavior is fail-closed: preserve categorized screenshot evidence and perform no click. |
| P1 | Foreground confirmation, stale HWND refresh, PaddleX timeout teardown, and MCTS turn-end action re-planning are guarded in the integrated line. | Existing protections retained; offline tests cover their pure policies and replay contracts. |
| P2 | `ScreenLogListener` and `DeckLogListener` still contain legacy substring/`!!` parsing assumptions for malformed log lines. | Remaining risk; next isolated parser-hardening target with fixtures. |
| P2 | Native/desktop E2E remains environment-dependent. | Not claimed; requires a fresh build, live process ledger, authoritative `Power.log` result marker, and screenshot evidence. |

## Recovery contract implemented

- Every Power.log resolver fault is observable as `POWER_LOG_RESOLVE_FAILED` with phase, step, line prefix, failure count, retry delay, and circuit state.
- Backoff is bounded at 5 seconds and opens after three consecutive faults; the faulty input is not blindly retried.
- A successful resolved event clears the fault budget.
- An interrupt is treated as cancellation and preserves the interrupt flag.
- Restart suppression emits `CORE_RESTART_SUPPRESSED`; executor failure releases the gate so a later independent recovery can try again.

## Verification

Focused worker verification ran the PowerLogListener and RuntimeFaultBackoff tests: 6 tests, 0 failures, 0 errors, 0 skipped. The expanded offline recovery set also passed 45 tests. Two existing `OfflinePaddleXOcrMulliganE2ETest` assertions reproduce a baseline metadata mismatch because the intentional local OCR path reports `LEGACY` while those assertions expect `PADDLEX`; this is outside the changed files.

## Deployment decision

Safe to merge the bounded listener/restart changes after integration. Not safe to call this a live deployment or declare end-to-end completion until a fresh build, process-liveness window, authoritative game-result marker, and final screenshot are available.
