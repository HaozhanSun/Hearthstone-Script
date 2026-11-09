[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RunId,
    [Parameter(Mandatory)][string]$DeploymentId,
    [Parameter(Mandatory)][int]$ProcessId,
    [Parameter(Mandatory)][datetime]$ProcessStartUtc,
    [Parameter(Mandatory)][datetime]$DeadlineUtc,
    [Parameter(Mandatory)][string]$LeasePath,
    [string]$GuardianStatePath,
    [Parameter(Mandatory)][string]$StopMarkerPath,
    [Parameter(Mandatory)][string]$OwnershipLockPath,
    [Parameter(Mandatory)][string]$ModulePath,
    [Parameter(Mandatory)][string]$ReadyPath,
    [Parameter(Mandatory)][string]$WatchdogLogPath
)

$ErrorActionPreference = 'Stop'
function Write-DebugRunHardCapWatchdogEvent([string]$Event, [string]$Details = '') {
    $line = '{0} event={1} runId={2} deploymentId={3} processId={4} {5}' -f `
        [datetime]::UtcNow.ToString('o'), $Event, $RunId, $DeploymentId, $ProcessId, $Details
    try { Add-Content -LiteralPath $WatchdogLogPath -Value $line -Encoding UTF8 } catch { }
}
trap {
    $reason = ($_.Exception.Message -replace '[\r\n]+', ' ').Replace('=', ':')
    Write-DebugRunHardCapWatchdogEvent 'WATCHDOG_FAILED' "reason=$reason action=LEASE_REFUSED"
    throw
}

Write-DebugRunHardCapWatchdogEvent 'WATCHDOG_STARTED' "readyPath=$ReadyPath deadlineUtc=$($DeadlineUtc.ToUniversalTime().ToString('o'))"
Import-Module -Name $ModulePath -Force

$context = New-DebugRunHardCapContext `
    -RunId $RunId `
    -DeploymentId $DeploymentId `
    -ProcessId $ProcessId `
    -ProcessStartUtc $ProcessStartUtc `
    -DeadlineUtc $DeadlineUtc
$ownershipLock = Enter-DebugRunHardCapOwnershipLock -LockPath $OwnershipLockPath
if ($null -eq $ownershipLock) { exit 31 }
try {
    if (-not (Test-DebugRunHardCapLeaseCurrent -Context $context -LeasePath $LeasePath)) { exit 32 }
    Write-DebugRunHardCapJsonAtomically -Path $ReadyPath -Value ([ordered]@{
        schema = 1
        state = 'READY'
        runId = $RunId
        deploymentId = $DeploymentId
        processId = $ProcessId
        deadlineEpochMillis = ([DateTimeOffset]$DeadlineUtc.ToUniversalTime()).ToUnixTimeMilliseconds()
        readyAtUtc = [datetime]::UtcNow.ToString('o')
    })
    Write-DebugRunHardCapWatchdogEvent 'WATCHDOG_READY' "readyPath=$ReadyPath"
} finally {
    Exit-DebugRunHardCapOwnershipLock -LockStream $ownershipLock
}
$remainingMilliseconds = Get-DebugRunHardCapDelayMilliseconds -DeadlineUtc $DeadlineUtc
if ($remainingMilliseconds -gt 0) {
    Start-Sleep -Milliseconds $remainingMilliseconds
}
$stopResult = Invoke-DebugRunHardCapStop `
    -Context $context `
    -LeasePath $LeasePath `
    -GuardianStatePath $GuardianStatePath `
    -StopMarkerPath $StopMarkerPath `
    -OwnershipLockPath $OwnershipLockPath
$stopJson = $stopResult | ConvertTo-Json -Compress
Write-DebugRunHardCapWatchdogEvent 'WATCHDOG_STOP_DECISION' "result=$($stopJson -replace '[\r\n]+', ' ')"
Write-Output $stopJson
