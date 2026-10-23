$ErrorActionPreference = 'Stop'

$modulePath = Join-Path $PSScriptRoot '..\..\..\main\resources\bat\debug-run-hardcap.psm1'
Import-Module -Name $modulePath -Force

function Assert-Equal($Expected, $Actual, [string]$Message) {
    if ($Expected -ne $Actual) {
        throw "ASSERT_EQUAL failed: $Message expected=[$Expected] actual=[$Actual]"
    }
}

function Assert-Sequence([object[]]$Expected, [object[]]$Actual, [string]$Message) {
    Assert-Equal ($Expected -join ',') ($Actual -join ',') $Message
}

$processStartUtc = [datetime]'2026-10-22T16:25:58Z'
$deadlineUtc = $processStartUtc.AddMinutes(15)
$lateWatchdogUtc = [datetime]'2026-10-22T16:42:34Z'

# The historical failure: the watchdog was only created 16m36s after Java.
# A deadline derived from the JVM start must produce no fresh delay.
Assert-Equal 0L (Get-DebugRunHardCapDelayMilliseconds -DeadlineUtc $deadlineUtc -NowUtc $lateWatchdogUtc) 'late watchdog must not extend a 15-minute cap'

$stopped = [System.Collections.Generic.List[int]]::new()
$context = New-DebugRunHardCapContext -RunId 'offline-late-watchdog' -ProcessId 100 -ProcessStartUtc $processStartUtc -DeadlineUtc $deadlineUtc
$ownedIdentity = { param($targetProcessId) [pscustomobject]@{ ProcessId = $targetProcessId; StartUtc = $processStartUtc } }
$ownedProcesses = {
    @(
        [pscustomobject]@{ ProcessId = 100; ParentProcessId = 1; Name = 'java.exe' },
        [pscustomobject]@{ ProcessId = 200; ParentProcessId = 100; Name = 'Hearthstone.exe' },
        [pscustomobject]@{ ProcessId = 201; ParentProcessId = 999; Name = 'Hearthstone.exe' },
        [pscustomobject]@{ ProcessId = 202; ParentProcessId = 100; Name = 'Battle.net.exe' }
    )
}
$stopOwned = { param($targetProcessId) [void]$stopped.Add([int]$targetProcessId) }
$lateStop = Invoke-DebugRunHardCapStop -Context $context -NowUtc $lateWatchdogUtc -GetProcessIdentity $ownedIdentity -GetProcessTable $ownedProcesses -StopProcess $stopOwned
Assert-Equal 'STOPPED_OWNED_RUN' $lateStop.Status 'late watchdog must terminate the original owned run'
Assert-Sequence @(200, 100) $stopped.ToArray() 'only owned Hearthstone descendants then the JVM may be stopped'

# The exact deadline is expired; one millisecond before it is not.
$clockContext = New-DebugRunHardCapContext -RunId 'offline-clock' -ProcessId 300 -ProcessStartUtc $processStartUtc -DeadlineUtc $deadlineUtc
$clockStop = [System.Collections.Generic.List[int]]::new()
$clockIdentity = { param($targetProcessId) [pscustomobject]@{ ProcessId = $targetProcessId; StartUtc = $processStartUtc } }
$clockProcesses = { @([pscustomobject]@{ ProcessId = 300; ParentProcessId = 1; Name = 'java.exe' }) }
$clockStopper = { param($targetProcessId) [void]$clockStop.Add([int]$targetProcessId) }
$beforeDeadline = Invoke-DebugRunHardCapStop -Context $clockContext -NowUtc $deadlineUtc.AddMilliseconds(-1) -GetProcessIdentity $clockIdentity -GetProcessTable $clockProcesses -StopProcess $clockStopper
Assert-Equal 'WAIT' $beforeDeadline.Status 'hard cap must not fire early'
$atDeadline = Invoke-DebugRunHardCapStop -Context $clockContext -NowUtc $deadlineUtc -GetProcessIdentity $clockIdentity -GetProcessTable $clockProcesses -StopProcess $clockStopper
Assert-Equal 'STOPPED_OWNED_RUN' $atDeadline.Status 'hard cap must fire exactly at deadline'
Assert-Sequence @(300) $clockStop.ToArray() 'deadline stop must include its JVM root'

# A retry/new JVM can reuse the old numeric PID. Its start time differs, so an
# old backstop must not kill that new process or its Hearthstone child.
$reusedPidContext = New-DebugRunHardCapContext -RunId 'offline-pid-reuse' -ProcessId 400 -ProcessStartUtc $processStartUtc -DeadlineUtc $deadlineUtc
$reusedStops = [System.Collections.Generic.List[int]]::new()
$reusedIdentity = { param($targetProcessId) [pscustomobject]@{ ProcessId = $targetProcessId; StartUtc = $processStartUtc.AddSeconds(1) } }
$reusedProcesses = {
    @(
        [pscustomobject]@{ ProcessId = 400; ParentProcessId = 1; Name = 'java.exe' },
        [pscustomobject]@{ ProcessId = 401; ParentProcessId = 400; Name = 'Hearthstone.exe' }
    )
}
$reusedStopper = { param($targetProcessId) [void]$reusedStops.Add([int]$targetProcessId) }
$reusedResult = Invoke-DebugRunHardCapStop -Context $reusedPidContext -NowUtc $deadlineUtc -GetProcessIdentity $reusedIdentity -GetProcessTable $reusedProcesses -StopProcess $reusedStopper
Assert-Equal 'STALE_OR_EXITED_TARGET' $reusedResult.Status 'old watchdog must reject a restarted/reused PID'
Assert-Equal 0 $reusedStops.Count 'old watchdog must not stop retry process or its child'

# A second invocation after a successful stop is a no-op, which makes duplicate
# completion paths safe when the process monitor observes the same deadline.
$repeat = Invoke-DebugRunHardCapStop -Context $context -NowUtc $lateWatchdogUtc -GetProcessIdentity $ownedIdentity -GetProcessTable $ownedProcesses -StopProcess $stopOwned
Assert-Equal 'ALREADY_ISSUED' $repeat.Status 'stop must be idempotent'
Assert-Sequence @(200, 100) $stopped.ToArray() 'idempotent stop must not add a second termination'

Write-Output 'DebugRunHardCap.Tests.ps1 PASSED'
