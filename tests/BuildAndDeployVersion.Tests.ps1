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

$pomFixture = '<project><local-build-timestamp-pacific>old</local-build-timestamp-pacific></project>'
$stampedPom = Set-PomBuildTimestampPacific $pomFixture $buildTimestamp
if ($stampedPom -notmatch '<local-build-timestamp-pacific>2026-10-01 10:08:00 PDT</local-build-timestamp-pacific>') {
    throw 'POM timestamp helper did not preserve the exact deployment timestamp'
}
Assert-Equal $stampedPom (Set-PomBuildTimestampPacific $stampedPom $buildTimestamp) 'idempotent POM timestamp update'
Assert-Equal $buildTimestamp (Get-BuildInfoTimestampPacific "version=v4.16.504`nbuildTimestampPacific=$buildTimestamp`nchannel=beta") 'JAR build.info timestamp'

$deployScript = [System.IO.File]::ReadAllText((Join-Path $root 'build-and-deploy.ps1'))
$metadataCall = $deployScript.IndexOf('$releaseMetadata = Get-ReleaseBuildMetadata', [System.StringComparison]::Ordinal)
$deployedVersionGuard = $deployScript.IndexOf('if (-not [string]::IsNullOrWhiteSpace($deployedVersion))', [System.StringComparison]::Ordinal)
if ($metadataCall -lt 0 -or $deployedVersionGuard -lt 0 -or $metadataCall -gt $deployedVersionGuard) {
    throw 'Build timestamp/version metadata must be resolved before the optional deployed-version branch'
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
