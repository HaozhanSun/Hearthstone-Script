# Diagnostic storage headroom and channel-safe retention closeout

## Investigation and known-good reference

The read-only inventory showed that C: was nearly full while D: had ample
headroom. The large trees were MCTS replay, unknown-state, and game-result
diagnostics, and most Beta/RC files were newer than the existing 30-day cutoff.
That explained why retention alone could not prevent the next run from growing
the runtime volume.

The last known-good storage implementation was the Beta policy introduced by
`1410e9e0` and its retention follow-ups. It selected a fixed non-runtime volume,
namespaced destinations by runtime key, reserved free space, and failed closed
for optional artifacts. The concrete regression was that the launcher,
`BetaArtifactStorage`, MCTS replay guard, and PowerShell retention reconstruction
only applied those protections to the Beta token; Stable and Release Candidate
continued using their C: runtime trees. The release script already contains the
per-channel `-InstallTask -VerifyTask` hook, but an installed Stable task still
requires live verification after deployment.

## Authorized source fix

On the existing `beta/release-candidate-v4.16.759` worktree, the source-only
change:

- generalizes the checked-in `beta-temp-policy.ps1` contract with channel-neutral
  diagnostic log/artifact selectors while retaining the old Beta wrapper names;
- routes new Stable, Beta, and Release Candidate logs and bulky artifacts to
  distinct runtime-keyed D: roots when headroom is available;
- disables optional artifact writes when no safe artifact root is available,
  rather than silently writing more to a low-space runtime volume;
- recognizes only timestamped, channel-matched preservation batches beneath
  `D:\HearthstoneScriptArchives`, with known artifact categories as deletion
  roots; arbitrary archive/configuration paths remain untouched;
- generalizes path resolution, artifact reserve checks, MCTS replay backoff,
  and Java/PowerShell retention root discovery to all supported channels;
- keeps retention restricted to the existing allowlisted diagnostic extensions,
  30-day cutoff, active-file protection, lock handling, and reparse fences; and
- adds regression coverage for channel roots, low-space fail-closed behavior,
  stable task description/source wiring, active files, and the exact cutoff.

No live runtime, user configuration/data, Hearthstone process, scheduled task,
shortcut, or existing diagnostic file was changed by this source work.

## Verification

Passed:

- `pwsh -NoProfile -NonInteractive -File tests/BetaTempDirectoryPolicy.Tests.ps1`
- `pwsh -NoProfile -NonInteractive -File tests/DiagnosticRetentionScheduler.Tests.ps1`
- `./mvnw.cmd -pl hs-script-app,hs-script-card-sdk -am '-Dtest=BetaArtifactStorageTest,DiagnosticRetentionTest,BetaLogDirectoryPolicyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
  (`19` focused tests passed; reactor build succeeded)
- `git diff --check`

Pending and intentionally not performed:

- commit creation and source-thread handoff;
- authorized deployment through `build-and-deploy.ps1`;
- Stable/Beta/RC process restart, shortcut/task verification, and live
  launcher-selection evidence;
- preservation-only migration of inactive C: diagnostics to their D: roots.

## Prevention

Keep the channel-neutral storage policy and retention entrypoint in the same
deployment payload, and require the release gate to verify each channel's
manifest-bound task plus launcher-selected diagnostic roots. Any new diagnostic
category must be added to both allowlists and covered by channel, headroom,
active-file, lock, reparse, and 30-day-boundary tests before deployment.
Preservation migrations must use a timestamped batch manifest under the
canonical archive root; do not broaden the archive root or move files into a
channel artifact root without recording source/destination lineage.
