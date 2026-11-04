[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$WatchdogPath,
    [Parameter(Mandatory)][string]$RunId,
    [Parameter(Mandatory)][int]$ProcessId,
    [Parameter(Mandatory)][datetime]$ProcessStartUtc,
    [Parameter(Mandatory)][datetime]$DeadlineUtc,
    [Parameter(Mandatory)][string]$LeasePath,
    [Parameter(Mandatory)][string]$GuardianStatePath,
    [Parameter(Mandatory)][string]$StopMarkerPath,
    [Parameter(Mandatory)][string]$OwnershipLockPath,
    [Parameter(Mandatory)][string]$ReadyPath,
    [Parameter(Mandatory)][string]$ModulePath,
    [Parameter(Mandatory)][string]$WatchdogLogPath
)

$ErrorActionPreference = 'Stop'

# Start-Process deliberately creates a separate, hidden watchdog process.  The
# JVM waits for the watchdog's signed READY record, not this short-lived
# bootstrap, so closing the bootstrap cannot cancel an armed hard cap.
$arguments = @(
    '-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-ExecutionPolicy', 'Bypass',
    '-File', $WatchdogPath,
    '-RunId', $RunId,
    '-ProcessId', [string]$ProcessId,
    '-ProcessStartUtc', $ProcessStartUtc.ToUniversalTime().ToString('o'),
    '-DeadlineUtc', $DeadlineUtc.ToUniversalTime().ToString('o'),
    '-LeasePath', $LeasePath,
    '-GuardianStatePath', $GuardianStatePath,
    '-StopMarkerPath', $StopMarkerPath,
    '-OwnershipLockPath', $OwnershipLockPath,
    '-ModulePath', $ModulePath,
    '-ReadyPath', $ReadyPath
)
$escapedArguments = ($arguments | ForEach-Object {
    $value = [string]$_
    if ($value -match '\s|"') { '"' + $value.Replace('"', '\"') + '"' } else { $value }
}) -join ' '

$logDirectory = Split-Path -Parent $WatchdogLogPath
if ($logDirectory) { New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null }
Start-Process -FilePath 'powershell.exe' -ArgumentList $escapedArguments -WindowStyle Hidden `
    -RedirectStandardOutput $WatchdogLogPath -RedirectStandardError ($WatchdogLogPath + '.err') | Out-Null
