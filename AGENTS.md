# Hearthstone Copilot project rules

These rules apply to every Codex session that changes or builds this project.

## Completion requires forced installation

For every user-requested code or configuration change that is reported as complete,
run the checked-in `build-and-deploy.ps1` release path after validation. This means
creating a new version, installing it into the canonical runtime directory, updating
the manifest, and replacing/verifying the Desktop, Start Menu, and Taskbar shortcuts.
Do not report the change as complete while any of those installation or verification
steps are unfinished. A user request that explicitly limits the work to offline/read-
only investigation is the exception; in that case do not build or install.

## Release and Windows shortcut rule

Every build that can be used to run the application is a new build and must:

1. Bump the application version in `pom.xml`. Never reuse the previous build's version or artifact name. Use the checked-in `build-and-deploy.ps1` release path when possible.
2. Build and verify the new JAR/ZIP before deployment.
3. Deploy the new artifact to the canonical Hearthstone Script runtime directory:
   `C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script`
4. Update the deployment manifest so the launcher selects the new JAR and verifies its hash.
5. Replace and verify all three user shortcuts: Desktop, Start Menu, and the per-user Taskbar pin. Shortcuts must target the stable admin launcher, which must resolve the newly deployed JAR; they must not point at an old versioned JAR.
6. Record the resulting version, deployment ID, JAR hash, launcher target, and all shortcut targets in the handoff.

Do not call a build complete if the build succeeds but deployment or shortcut replacement fails. Do not delete user data directories such as `config`, `data`, `log`, or `plugin` backups while deploying.

## Verification checklist

- Confirm `pom.xml` contains a version greater than the previous build.
- Confirm the built artifact exists and the deployment manifest names that exact artifact and hash.
- Confirm Desktop, Start Menu, and Taskbar shortcuts all target `wscript.exe` with the stable launcher as their argument.
- Confirm the stable launcher resolves the manifest's current JAR.

## Stable and beta release channels

- GitHub `main` is the stable branch. Keep it on the last known-good source
  line; do not merge experimental work merely because it compiles.
- Experimental work belongs on `beta/*` branches and must declare `channel:
  beta` in `release-channel.json`.
- Stable and beta deployments use separate runtime roots, manifests, PIDs,
  logs, and shortcut names. A beta deployment must never overwrite the stable
  `Hearthstone Script` runtime or its shortcuts.
- Promote beta to `main` only after the exact beta artifact passes the online
  E2E completion gate. The checked-in channel validation workflow must pass
  before promotion.
