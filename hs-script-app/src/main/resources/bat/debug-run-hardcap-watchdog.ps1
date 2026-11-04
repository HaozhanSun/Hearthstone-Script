[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RunId,
    [Parameter(Mandatory)][int]$ProcessId,
    [Parameter(Mandatory)][datetime]$ProcessStartUtc,
    [Parameter(Mandatory)][datetime]$DeadlineUtc,
    [Parameter(Mandatory)][string]$LeasePath,
    [string]$GuardianStatePath,
    [Parameter(Mandatory)][string]$StopMarkerPath,
    [Parameter(Mandatory)][string]$OwnershipLockPath,
    [Parameter(Mandatory)][string]$ModulePath,
    [Parameter(Mandatory)][string]$ReadyPath
)

$ErrorActionPreference = 'Stop'
Import-Module -Name $ModulePath -Force

$context = New-DebugRunHardCapContext `
    -RunId $RunId `
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
        processId = $ProcessId
        deadlineEpochMillis = ([DateTimeOffset]$DeadlineUtc.ToUniversalTime()).ToUnixTimeMilliseconds()
        readyAtUtc = [datetime]::UtcNow.ToString('o')
    })
} finally {
    Exit-DebugRunHardCapOwnershipLock -LockStream $ownershipLock
}
$remainingMilliseconds = Get-DebugRunHardCapDelayMilliseconds -DeadlineUtc $DeadlineUtc
if ($remainingMilliseconds -gt 0) {
    Start-Sleep -Milliseconds $remainingMilliseconds
}
Invoke-DebugRunHardCapStop `
    -Context $context `
    -LeasePath $LeasePath `
    -GuardianStatePath $GuardianStatePath `
    -StopMarkerPath $StopMarkerPath `
    -OwnershipLockPath $OwnershipLockPath | ConvertTo-Json -Compress
