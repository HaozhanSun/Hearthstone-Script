# DebugRun deployment lineage diagnosis — 2026-09-01

This is an append-only diagnosis of the missing `调试/Test Run` control in the
canonical deployment. It does not reuse gameplay or Computer Use evidence.

## Finding

The DebugRun feature was lost in a source-lineage fork before the current
canonical package was built. The current `v4.16.169` JAR is internally
hash-consistent with its manifest, but it is not built from the DebugRun
source lineage.

## Bisect boundary

| Boundary | Build / commit evidence | DebugRun content |
|---|---|---|
| Last real good artifact | `hs-script_v4.16.133-local-20260901-003823PDT.jar`; published by `db92943f` (`build: publish isolated debug runner`) | 7 `DebugRun*` class entries; `fxml/main.fxml` contains `debugRunModeCheckBox`, `DEBUG_RUN_MODE`, and `debugRunStatus` |
| First real bad artifact | `hs-script_v4.16.133-local-20260901-004329PDT.jar` | 0 `DebugRun*` class entries; no DebugRun checkbox/config/status in `fxml/main.fxml` |
| First committed source deletion | `e6ede413` (`Fail closed E2E dispatch until fresh Power.log game`), version `v4.16.134-local-20260901-005112PDT` | Deletes `DebugRunLease.kt`, `DebugRunController.kt`, and the DebugRun FXML block while changing the version/POM lineage |
| Current canonical artifact | `hs-script_v4.16.169-local-20260901-195127PDT.jar`, generated `2026-09-01 19:55:16 PDT` | 0 DebugRun classes and no DebugRun UI/config in the JAR |

The first bad artifact is earlier than the first committed deletion because
the 04:43 build was produced from a parallel/stale source state. The next
committed build at `e6ede413` makes the deletion explicit. `db92943f` and
`e6ede413` do not form a single linear descendant chain: the former is on the
DebugRun provisional line, while the latter is on the later readiness line.
That branch choice, not the JAR hash or the assembly ZIP mechanism, is the
primary regression.

## Package and runtime evidence

- Canonical manifest:
  `C:/Users/yzjsh/Documents/Codex/2026-08-15/for-all-these-delay-short-are-2/outputs/Hearthstone Script/deployment-manifest.json`
- Manifest-selected JAR SHA-256 equals the actual JAR SHA-256:
  `174bb6ba595b52176d23773865840a49436573257c5f95e6353d387a9a66785d`
- Read-only JAR scan found `DebugRunClassCount=0`.
- Read-only `fxml/main.fxml` scan found no `debugRunModeCheckBox`,
  `DEBUG_RUN_MODE`, or `debugRunStatus`.
- Source FXML and deployed FXML hashes differ (`d1afb643...` versus
  `7e8cb691...`).
- Old `DEBUG_OVERRIDE_*` log lines at `00:07` and `00:45 PDT` belong to
  earlier v4.16.131/v4.16.133 runs, before the 19:55 v4.16.169 deployment;
  they are not current-build proof.

## Corrected current-lineage checkpoint

Worktree:
`C:/Users/yzjsh/Documents/Codex/2026-09-01/debugrun-current-integration`

Branch: `codex/debugrun-current-integration`

Base: current `codex/e2e-milestone-lineage-fix` (`9d7e03de`), then the
DebugRun integration was cherry-picked and resolved against the newer
`activeScheduleWindow`, `currentScheduleDate`, and `startupRunWindow` logic.
The resolved gate keeps ordinary schedule state separate from the effective
DebugRun gate and reports `DEBUG_OVERRIDE` as a distinct schedule decision.

Focused verification command:

```text
.\\mvnw.cmd -pl hs-script-app -am -Pjvm -Djava.version=24 -Dkotlin.compiler.jvmTarget=24 -Dmaven.compiler.source=24 -Dmaven.compiler.target=24 -Dtest=DebugRunLeaseTest,DebugRunScriptContractTest -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=0 test
```

Result: reactor build success; `DebugRunLeaseTest` 7/7 passed, 0 failures,
0 errors. The seven tests cover default-off behavior, closed-schedule plus
active-lease bypass, 30-minute cap, non-renewal, monotonic expiry despite a
wall-clock jump, disable/stale-callback safety, restart clearing, and
concurrent enable requests.

## Release gate

Before any canonical deployment, the release must use this current-lineage
worktree, increment the root/app version, run the checked-in
`build-and-deploy.ps1`, and verify from the produced JAR itself:

1. `DebugRunLease.class` and `DebugRunController.class` exist.
2. `fxml/main.fxml` contains the DebugRun checkbox and status label.
3. The manifest-selected JAR hash equals the actual file hash.
4. The ZIP, `run-debug.ps1`, stable launcher, Desktop, Start Menu, and
   Taskbar shortcuts all point to the same new deployment identity.
5. The release worktree is clean.

No live runner, matchmaking, Hearthstone process, or Computer Use action was
started for this diagnosis or checkpoint.
