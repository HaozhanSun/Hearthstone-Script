[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$WatchdogPath,
    [Parameter(Mandatory)][string]$RunId,
    [Parameter(Mandatory)][string]$DeploymentId,
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
    '-DeploymentId', $DeploymentId,
    '-ProcessId', [string]$ProcessId,
    '-ProcessStartUtc', $ProcessStartUtc.ToUniversalTime().ToString('o'),
    '-DeadlineUtc', $DeadlineUtc.ToUniversalTime().ToString('o'),
    '-LeasePath', $LeasePath,
    '-GuardianStatePath', $GuardianStatePath,
    '-StopMarkerPath', $StopMarkerPath,
    '-OwnershipLockPath', $OwnershipLockPath,
    '-ModulePath', $ModulePath,
    '-ReadyPath', $ReadyPath,
    '-WatchdogLogPath', $WatchdogLogPath
)
$escapedArguments = ($arguments | ForEach-Object {
    $value = [string]$_
    # ArgumentList is parsed again by powershell.exe. Quote command-line
    # metacharacters as well as whitespace: deployment IDs include jar|hash.
    if ($value -match '[\s"|&;<>^()]') { '"' + $value.Replace('"', '\"') + '"' } else { $value }
}) -join ' '

$logDirectory = Split-Path -Parent $WatchdogLogPath
if ($logDirectory) { New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null }
function Write-DebugRunHardCapLaunchEvent([string]$Event, [string]$Details = '') {
    $line = '{0} event={1} runId={2} processId={3} readyPath={4} {5}' -f `
        [datetime]::UtcNow.ToString('o'), $Event, $RunId, $ProcessId, $ReadyPath, $Details
    Add-Content -LiteralPath $WatchdogLogPath -Value $line -Encoding UTF8
}

# -Verb RunAs selects Start-Process' ShellExecute parameter set, which does
# not support -RedirectStandardOutput/Error. Keep the elevated launch in that
# parameter set; the parent JVM confirms readiness from the exact READY file.
try {
    $watchdogProcess = Start-Process -FilePath 'powershell.exe' -ArgumentList $escapedArguments `
        -Verb RunAs -WindowStyle Hidden -PassThru
    if ($null -eq $watchdogProcess) { throw 'Start-Process returned no elevated watchdog process.' }
    Write-DebugRunHardCapLaunchEvent 'ELEVATED_WATCHDOG_PROCESS_ACCEPTED' `
        "watchdogPid=$($watchdogProcess.Id) deploymentId=$DeploymentId deadlineUtc=$($DeadlineUtc.ToUniversalTime().ToString('o'))"
} catch {
    $reason = ($_.Exception.Message -replace '[\r\n]+', ' ').Replace('=', ':')
    Write-DebugRunHardCapLaunchEvent 'ELEVATED_WATCHDOG_LAUNCH_FAILED' "reason=$reason action=LEASE_REFUSED"
    throw
}
