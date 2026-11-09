$ErrorActionPreference = 'Stop'

function Assert-Equal($Expected, $Actual, [string]$Message) {
    if ($Expected -ne $Actual) { throw "ASSERT_EQUAL failed: $Message expected=[$Expected] actual=[$Actual]" }
}
function Get-ArgumentValue([System.Text.RegularExpressions.Match]$Match) {
    if (-not $Match.Success) { throw 'Required elevated watchdog argument was not passed.' }
    if ($Match.Groups[1].Success) { return $Match.Groups[1].Value }
    return $Match.Groups[2].Value
}

$launcherPath = Join-Path $PSScriptRoot '..\..\..\main\resources\bat\debug-run-hardcap-launcher.ps1'
$testDirectory = Join-Path $PSScriptRoot 'debug-run-hardcap-launcher-test'
New-Item -ItemType Directory -Force -Path $testDirectory | Out-Null
$global:MockStartFailure = $false

function Start-Process {
    [CmdletBinding()]
    param(
        [string]$FilePath,
        [string]$ArgumentList,
        [string]$Verb,
        [string]$WindowStyle,
        [switch]$PassThru,
        [string]$RedirectStandardOutput,
        [string]$RedirectStandardError
    )
    Assert-Equal 'powershell.exe' $FilePath 'elevated watchdog executable'
    Assert-Equal 'RunAs' $Verb 'elevated shell launch'
    Assert-Equal 'Hidden' $WindowStyle 'detached watchdog visibility'
    Assert-Equal $true $PassThru.IsPresent 'launcher must observe the process acceptance result'
    Assert-Equal $true ([string]::IsNullOrEmpty($RedirectStandardOutput)) 'RunAs must not combine with stdout redirection'
    Assert-Equal $true ([string]::IsNullOrEmpty($RedirectStandardError)) 'RunAs must not combine with stderr redirection'
    if ($global:MockStartFailure) { throw 'simulated elevation was denied' }

    $readyMatch = [regex]::Match($ArgumentList, '(?:^|\s)-ReadyPath\s+(?:"([^"]+)"|(\S+))')
    if (-not $readyMatch.Success) { throw 'ReadyPath was not passed to the elevated watchdog' }
    $readyPath = if ($readyMatch.Groups[1].Success) { $readyMatch.Groups[1].Value } else { $readyMatch.Groups[2].Value }
    $runId = [regex]::Match($ArgumentList, '(?:^|\s)-RunId\s+(?:"([^"]+)"|(\S+))')
    $deployment = [regex]::Match($ArgumentList, '(?:^|\s)-DeploymentId\s+(?:"([^"]+)"|(\S+))')
    $process = [regex]::Match($ArgumentList, '(?:^|\s)-ProcessId\s+(?:"([^"]+)"|(\S+))')
    $deadline = [regex]::Match($ArgumentList, '(?:^|\s)-DeadlineUtc\s+(?:"([^"]+)"|(\S+))')
    $value = [ordered]@{
        schema = 1; state = 'READY'; runId = Get-ArgumentValue $runId
        deploymentId = Get-ArgumentValue $deployment
        processId = [int](Get-ArgumentValue $process)
        deadlineEpochMillis = ([DateTimeOffset]::Parse((Get-ArgumentValue $deadline))).ToUnixTimeMilliseconds()
        readyAtUtc = [datetime]::UtcNow.ToString('o')
    }
    $value | ConvertTo-Json | Set-Content -LiteralPath $readyPath -Encoding UTF8
    return [pscustomobject]@{ Id = 9876 }
}

function Invoke-TestLauncher([string]$runId, [string]$readyPath, [string]$logPath) {
    & $launcherPath `
        -WatchdogPath (Join-Path $testDirectory 'debug-run-hardcap-watchdog.ps1') `
        -RunId $runId -DeploymentId 'beta-test-deployment' -ProcessId 4321 `
        -ProcessStartUtc ([datetime]'2026-11-02T16:00:00Z') `
        -DeadlineUtc ([datetime]'2026-11-02T16:30:00Z') `
        -LeasePath (Join-Path $testDirectory 'lease.json') `
        -GuardianStatePath (Join-Path $testDirectory 'guardian.json') `
        -StopMarkerPath (Join-Path $testDirectory 'stop.json') `
        -OwnershipLockPath (Join-Path $testDirectory 'ownership.lock') `
        -ReadyPath $readyPath -ModulePath (Join-Path $testDirectory 'module.psm1') `
        -WatchdogLogPath $logPath
}

try {
    $readyPath = Join-Path $testDirectory 'accepted.ready.json'
    $logPath = Join-Path $testDirectory 'watchdog.log'
    Invoke-TestLauncher 'accepted-run' $readyPath $logPath
    $ready = Get-Content -LiteralPath $readyPath -Raw | ConvertFrom-Json
    Assert-Equal 'accepted-run' $ready.runId 'accepted launch writes readiness for the requested run'
    Assert-Equal 'beta-test-deployment' $ready.deploymentId 'readiness binds deployment identity'
    Assert-Equal 4321 $ready.processId 'readiness binds the target process'
    Assert-Equal $true ((Get-Content -LiteralPath $logPath -Raw).Contains('event=ELEVATED_WATCHDOG_PROCESS_ACCEPTED')) `
        'successful elevated launch must be observable in the watchdog log'

    $global:MockStartFailure = $true
    $failedLog = Join-Path $testDirectory 'failed-watchdog.log'
    try {
        Invoke-TestLauncher 'failed-run' (Join-Path $testDirectory 'failed.ready.json') $failedLog
        throw 'launcher unexpectedly accepted a denied elevation request'
    } catch {
        if ($_.Exception.Message -eq 'launcher unexpectedly accepted a denied elevation request') { throw }
    }
    $failureText = Get-Content -LiteralPath $failedLog -Raw
    Assert-Equal $true ($failureText.Contains('event=ELEVATED_WATCHDOG_LAUNCH_FAILED')) `
        'elevation failure must be logged with a structured refusal event'
    Assert-Equal $true ($failureText.Contains('action=LEASE_REFUSED')) 'launch failure must refuse the run'
} finally {
    Remove-Item -LiteralPath $testDirectory -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Output 'DebugRunHardCapLauncher.Tests.ps1 PASSED'
