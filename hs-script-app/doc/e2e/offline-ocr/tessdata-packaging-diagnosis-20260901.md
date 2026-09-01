# Tesseract data packaging diagnosis

## Evidence

Run `63245_7909` used v4.16.161 and logged this native Tesseract failure in
`outputs/Hearthstone Script/log/e2e-runs/63245_7909/java-console-debug.log`:

```text
Error opening data file .../outputs/Hearthstone Script/resources/tessdata/chi_sim_vert.traineddata
Failed loading language 'chi_sim_vert'
```

The deployed runtime had `resources/tessdata/chi_sim.traineddata` but not
`chi_sim_vert.traineddata`. The worktree source contains all three expected
models: `chi_sim.traineddata`, `chi_sim_vert.traineddata`, and
`eng.traineddata`.

## Root cause

`hs-script-app/assembly.xml` had the tessdata `fileSet` commented out, so the
release ZIP did not copy `src/main/resources/resources/tessdata` into the
runtime. The JAR exclusion is not the problem by itself: tessdata is an
external runtime resource and must be copied by the assembly ZIP. A previous
runtime's leftover `chi_sim.traineddata` made the installation look partially
healthy and hid the packaging regression.

## Fix in this checkpoint

The assembly now copies `*.traineddata` to `resources/tessdata/`. The new
`TessDataPackagingTest` parses the assembly XML and verifies the three source
files and destination directory. No shared runtime was modified.

## Provider interpretation

The same run proves PaddleX health passed first, then the full-screen PaddleX
result was rejected by its contract and explicitly fell back to Legacy. The
missing Legacy model therefore created a second OCR failure after a correctly
audited fallback; it does not prove that PaddleX was unhealthy. A future
release must deploy the fixed ZIP, verify all three files and their hashes in
the canonical runtime, then run the normal provider/E2E gate.

## Release requirement

The release owner must bump the version and run the checked-in
`build-and-deploy.ps1` path. Before accepting the release, inspect the ZIP and
canonical runtime for all three `resources/tessdata/*.traineddata` files and
record their hashes. This checkpoint intentionally does not perform that
deployment.
