$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $root 'build-version-utils.ps1')

function Assert-Equal([string]$Expected, [string]$Actual, [string]$Label) {
    if ($Expected -cne $Actual) { throw "$Label expected '$Expected' but got '$Actual'" }
}

Assert-Equal 'v4.16.483-local-20260928-211941PDT' `
    (Get-DeployedVersionFromJarName 'hs-script_v4.16.483-local-20260928-211941PDT.jar') `
    'PDT manifest version'
Assert-Equal 'v4.16.483-local-20260104-031122PST' `
    (Get-DeployedVersionFromJarName 'hs-script_v4.16.483-local-20260104-031122PST.jar') `
    'PST manifest version'
Assert-Equal 'v4.16.484-local-20260928-220000PDT' `
    (Get-NextApplicationVersion 'v4.16.483' 'v4.16.483-local-20260928-211941PDT' ([datetime]'2026-09-28T22:00:00')) `
    'strict bump from deployed local version'
Assert-Equal 'v4.16.484-local-20260928-220000PDT' `
    (Get-NextApplicationVersion 'v4.16.482' 'v4.16.483-local-20260928-211941PDT' ([datetime]'2026-09-28T22:00:00')) `
    'strict bump when source is behind'
Assert-Equal 'v4.16.484-local-20260928-200000PDT' `
    (Get-NextApplicationVersion 'v4.16.484-local-20260928-200000PDT' 'v4.16.483-local-20260928-211941PDT' ([datetime]'2026-09-28T22:00:00')) `
    'preserve already newer source version'

$buildTime = [datetime]'2026-10-01T10:08:00'
$buildTimestamp = '2026-10-01 10:08:00 PDT'
$alreadyNewerMetadata = Get-ReleaseBuildMetadata 'v4.16.504' 'v4.16.503' $buildTime 'PDT'
Assert-Equal 'v4.16.504' $alreadyNewerMetadata.Version 'already-newer build version'
Assert-Equal $buildTimestamp $alreadyNewerMetadata.BuildTimestampPacific 'already-newer build timestamp'

$bumpedMetadata = Get-ReleaseBuildMetadata 'v4.16.503' 'v4.16.503' $buildTime 'PDT'
Assert-Equal 'v4.16.504-local-20261001-100800PDT' $bumpedMetadata.Version 'version-bump build version'
Assert-Equal $buildTimestamp $bumpedMetadata.BuildTimestampPacific 'version-bump build timestamp'

$noManifestMetadata = Get-ReleaseBuildMetadata 'v4.16.504' '' $buildTime 'PDT'
Assert-Equal 'v4.16.504' $noManifestMetadata.Version 'first-deploy build version'
Assert-Equal $buildTimestamp $noManifestMetadata.BuildTimestampPacific 'first-deploy build timestamp'
$pstMetadata = Get-ReleaseBuildMetadata 'v4.16.504' 'v4.16.503' ([datetime]'2026-01-04T03:11:22') 'PST'
Assert-Equal '2026-01-04 03:11:22 PST' $pstMetadata.BuildTimestampPacific 'standard-time build timestamp'

$collisionFixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("beta-artifact-collision-" + [guid]::NewGuid().ToString('N'))
$collisionRuntime = Join-Path $collisionFixtureRoot 'Hearthstone Script Beta'
$collisionSourceTarget = Join-Path $collisionFixtureRoot 'hs-script-app\target'
try {
    New-Item -ItemType Directory -Path $collisionRuntime, $collisionSourceTarget -Force | Out-Null
    $retryVersion = 'v4.16.680-local-20261027-034150PDT'
    [System.IO.File]::WriteAllText((Join-Path $collisionRuntime "hs-script_$retryVersion.jar"), 'preserve deployed artifact')
    [System.IO.File]::WriteAllText((Join-Path $collisionRuntime "hs-script_$retryVersion.zip"), 'preserve deployed artifact')
    Assert-Equal 'v4.16.681-local-20261027-034200PDT' `
        (Get-BetaCollisionSafeArtifactVersion $retryVersion ([datetime]'2026-10-27T03:42:00') $collisionRuntime $collisionSourceTarget) `
        'Beta retry bumps patch when prior runtime JAR and ZIP exist'
    Assert-Equal 'preserve deployed artifact' ([System.IO.File]::ReadAllText((Join-Path $collisionRuntime "hs-script_$retryVersion.jar"))) 'collision detection leaves deployed JAR intact'
    Assert-Equal 'preserve deployed artifact' ([System.IO.File]::ReadAllText((Join-Path $collisionRuntime "hs-script_$retryVersion.zip"))) 'collision detection leaves deployed ZIP intact'

    $sourceRetryVersion = 'v4.16.748-local-20261107-235212PST'
    [System.IO.File]::WriteAllText((Join-Path $collisionSourceTarget "hs-script_$sourceRetryVersion.jar"), 'preserve packaged source artifact')
    [System.IO.File]::WriteAllText((Join-Path $collisionSourceTarget "hs-script_$sourceRetryVersion.zip"), 'preserve packaged source artifact')
    Assert-Equal 'v4.16.749-local-20261108-001500PST' `
        (Get-BetaCollisionSafeArtifactVersion $sourceRetryVersion ([datetime]'2026-11-08T00:15:00') $collisionRuntime $collisionSourceTarget) `
        'Beta retry bumps patch when prior packaged source JAR and ZIP exist'
    Assert-Equal 'preserve packaged source artifact' ([System.IO.File]::ReadAllText((Join-Path $collisionSourceTarget "hs-script_$sourceRetryVersion.jar"))) 'collision detection leaves packaged source JAR intact'
    Assert-Equal 'preserve packaged source artifact' ([System.IO.File]::ReadAllText((Join-Path $collisionSourceTarget "hs-script_$sourceRetryVersion.zip"))) 'collision detection leaves packaged source ZIP intact'

    Assert-Equal 'v4.16.681-local-20261027-034200PDT' `
        (Get-BetaCollisionSafeArtifactVersion 'v4.16.681-local-20261027-034200PDT' ([datetime]'2026-10-27T03:42:00') $collisionRuntime $collisionSourceTarget) `
        'Beta retry accepts a fresh artifact version'

    Assert-Equal 'v4.16.748-local-20261107-235212PST' `
        (Get-NextApplicationVersion 'v4.16.748-local-20261107-235212PST' 'v4.16.747-local-20261103-183415PST' ([datetime]'2026-11-08T00:15:00')) `
        'normal newer source version remains the candidate before collision selection'
} finally {
    if (Test-Path -LiteralPath $collisionFixtureRoot) { Remove-Item -LiteralPath $collisionFixtureRoot -Recurse -Force }
}

$pomFixture = '<project><local-build-timestamp-pacific>old</local-build-timestamp-pacific></project>'
$stampedPom = Set-PomBuildTimestampPacific $pomFixture $buildTimestamp
if ($stampedPom -notmatch '<local-build-timestamp-pacific>2026-10-01 10:08:00 PDT</local-build-timestamp-pacific>') {
    throw 'POM timestamp helper did not preserve the exact deployment timestamp'
}
Assert-Equal $stampedPom (Set-PomBuildTimestampPacific $stampedPom $buildTimestamp) 'idempotent POM timestamp update'
Assert-Equal $buildTimestamp (Get-BuildInfoTimestampPacific "version=v4.16.504`nbuildTimestampPacific=$buildTimestamp`nchannel=beta") 'JAR build.info timestamp'

$deployScript = [System.IO.File]::ReadAllText((Join-Path $root 'build-and-deploy.ps1'))
$requiredFocusedRegressionTests = @(
    'StartupModeEntryDispatchPolicyTest',
    'BetaNeverSurrenderOverrideTest'
)
foreach ($requiredTest in $requiredFocusedRegressionTests) {
    if ($deployScript -notmatch "-Dtest=.*$requiredTest") {
        throw "Release focused test selector must include $requiredTest"
    }
}
$metadataCall = $deployScript.IndexOf('$releaseMetadata = Get-ReleaseBuildMetadata', [System.StringComparison]::Ordinal)
$deployedVersionGuard = $deployScript.IndexOf('if (-not [string]::IsNullOrWhiteSpace($deployedVersion))', [System.StringComparison]::Ordinal)
if ($metadataCall -lt 0 -or $deployedVersionGuard -lt 0 -or $metadataCall -gt $deployedVersionGuard) {
    throw 'Build timestamp/version metadata must be resolved before the optional deployed-version branch'
}
$betaCollisionGuard = $deployScript.IndexOf("if (`$Channel -eq 'beta')", [System.StringComparison]::Ordinal)
$collisionHelperCall = $deployScript.IndexOf('Get-BetaCollisionSafeArtifactVersion', [System.StringComparison]::Ordinal)
$collisionVersionAssignment = $deployScript.IndexOf('$nextVersion = $collisionSafeVersion', [System.StringComparison]::Ordinal)
if ($betaCollisionGuard -lt 0 -or $collisionHelperCall -lt $betaCollisionGuard -or $collisionVersionAssignment -lt $collisionHelperCall) {
    throw 'Artifact collision avoidance must be limited to Beta and applied before packaging'
}
if ($deployScript -notmatch 'Get-BetaCollisionSafeArtifactVersion\s+\$nextVersion\s+\$now\s+\$runtimeRoot\s+\$targetRoot') {
    throw 'Beta collision avoidance must check both the canonical runtime and local packaged artifact output'
}
if ($deployScript -notmatch 'buildTimestampPacific\s*=\s*\$buildTimestampPacific') {
    throw 'Deployment manifest must record the exact artifact buildTimestampPacific value'
}
if ($deployScript -notmatch 'embeddedBuildTimestampPacific\s+-cne\s+\$buildTimestampPacific') {
    throw 'Deployment script must reject a JAR whose embedded timestamp differs from the build timestamp'
}
$artifactCheck = $deployScript.IndexOf('$embeddedBuildTimestampPacific -cne $buildTimestampPacific', [System.StringComparison]::Ordinal)
$runtimeStop = $deployScript.IndexOf('Stop-ManagedHsScriptProcesses', [System.StringComparison]::Ordinal)
if ($artifactCheck -lt 0 -or $runtimeStop -lt 0 -or $artifactCheck -gt $runtimeStop) {
    throw 'Artifact timestamp must be verified before the deployment process is stopped or runtime is modified'
}
$betaGameGuard = $deployScript.IndexOf('Assert-HsScriptBetaGameProcessAbsent | Out-Null', [System.StringComparison]::Ordinal)
$betaRuntimeRestart = $deployScript.IndexOf('Start-HsScriptBetaRuntimeAfterDeploy', [System.StringComparison]::Ordinal)
$shortcutSync = $deployScript.IndexOf("sync-shortcuts.ps1", [System.StringComparison]::Ordinal)
$deployComplete = $deployScript.IndexOf("BUILD_AND_DEPLOY_COMPLETE", [System.StringComparison]::Ordinal)
if ($betaGameGuard -lt 0 -or $betaGameGuard -gt $runtimeStop) {
    throw 'Beta deployment must fail closed on a live Hearthstone process before stopping the Beta app'
}
if ($betaRuntimeRestart -lt $shortcutSync -or $deployComplete -lt $betaRuntimeRestart) {
    throw 'Beta deployment must launch and verify the canonical runtime after shortcut sync before reporting completion'
}
if ($deployScript -notmatch 'BETA_APP_RESTART_VERIFIED pid=.*deploymentId=') {
    throw 'Beta deployment must report the verified post-deploy PID and deployment ID'
}

$invalidNames = @(
    'hs-script_v4.16.483-local-20260928-211941.jar',
    'hs-script_v4.16.483-local-20260928-211941UTC.jar',
    'hs-script_v4.16.483.jar.bak'
)
foreach ($name in $invalidNames) {
    try {
        Get-DeployedVersionFromJarName $name | Out-Null
        throw "invalid manifest name was accepted: $name"
    } catch {
        if ($_.Exception.Message -like "invalid manifest name was accepted:*") { throw }
    }
}

Write-Output 'BuildAndDeployVersion.Tests: PASS (version bump/no-bump/first deploy timestamps, exact POM/JAR/manifest timestamp contract, invalid-name fail-closed)'
