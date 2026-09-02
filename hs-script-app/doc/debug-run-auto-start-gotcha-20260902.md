# DebugRun auto-start gotcha

Observed 2026-09-02 (Pacific): the E2E runner launched with
`hs.script.autostart=true`, while the persisted `START_ON_OPEN` preference was
also enabled. The first ordinary schedule poll therefore ran before the
DebugRun UI action and created a `startup-window`, producing a legitimate
outside-hours/invalid-window baseline that was easy to misread as a DebugRun
suppression failure. The same run later reached `SCREEN_WATCHDOG_BLOCKED`
with `kind=UNKNOWN` and paused in `REPLACE_CARD`; it is not valid success
evidence.

Prevention: the isolated `run-debug.ps1` path now passes the opt-in
`hs.script.debugrun.prearm=true` property. `MainApplication.preInit()` arms
the in-memory lease before `WorkTimeListener.launch`, and the main window
retains the live lease and reflects it in the DebugRun checkbox. Ordinary
production launches do not set this property and keep the normal schedule
warning behavior. A fresh verification must still record a separate
outside-hours baseline, then exercise the visible checkbox and prove the
structured `SCHEDULE_OVERRIDE_SUPPRESSED_OUTSIDE_HOURS` marker on the new
lineage.
