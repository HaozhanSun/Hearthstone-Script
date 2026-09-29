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

Write-Output 'BuildAndDeployVersion.Tests: PASS (PDT/PST extraction, strict bump, invalid-name fail-closed)'
