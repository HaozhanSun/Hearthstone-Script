$ErrorActionPreference = 'Stop'

$modulePath = Join-Path $PSScriptRoot '..\..\..\main\resources\bat\debug-run-hardcap.psm1'
Import-Module -Name $modulePath -Force
$testOwnershipDirectory = Join-Path ([IO.Path]::GetTempPath()) ("DebugRunHardCap_Ownership_" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testOwnershipDirectory | Out-Null
$testOwnershipLockPath = Join-Path $testOwnershipDirectory 'ownership.lock'
$testStopMarkerPath = Join-Path $testOwnershipDirectory 'stop.json'

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
Assert-Equal $true (Test-DebugRunProcessStartMatches -ActualStartUtc $processStartUtc.AddMilliseconds(500) -ExpectedStartUtc $processStartUtc) 'Java/.NET process creation precision must allow a bounded subsecond difference'
Assert-Equal $false (Test-DebugRunProcessStartMatches -ActualStartUtc $processStartUtc.AddSeconds(1) -ExpectedStartUtc $processStartUtc) 'a one-second difference must reject a PID-reused process'
Assert-Equal $false (Test-DebugRunProcessStartMatches -ActualStartUtc $processStartUtc.AddSeconds(2) -ExpectedStartUtc $processStartUtc) 'process start tolerance must still reject a different process identity'

$leaseContext = New-DebugRunHardCapContext -RunId 'lease-dimensions' -ProcessId 44 -ProcessStartUtc $processStartUtc -DeadlineUtc $deadlineUtc
$dimensionLeasePath = Join-Path $testOwnershipDirectory 'dimension-lease.json'
$dimensionLease = [ordered]@{
    schema = 1; state = 'ACTIVE'; runId = 'lease-dimensions'; processId = 44
    processStartUtc = $processStartUtc.ToString('o'); deadlineUtc = $deadlineUtc.ToString('o')
}
$dimensionLease | ConvertTo-Json | Set-Content -LiteralPath $dimensionLeasePath -Encoding UTF8
Assert-Equal $true (Test-DebugRunHardCapLeaseCurrent -Context $leaseContext -LeasePath $dimensionLeasePath) 'exact lease ownership must be accepted'
$deploymentContext = New-DebugRunHardCapContext -RunId 'lease-dimensions' -ProcessId 44 `
    -ProcessStartUtc $processStartUtc -DeadlineUtc $deadlineUtc -DeploymentId 'beta-test'
$deploymentLease = [ordered]@{
    schema = 1; state = 'ACTIVE'; runId = 'lease-dimensions'; deploymentId = 'beta-test'
    processId = 44; processStartUtc = $processStartUtc.ToString('o')
    deadlineUtc = $deadlineUtc.ToString('o')
}
$deploymentLease | ConvertTo-Json | Set-Content -LiteralPath $dimensionLeasePath -Encoding UTF8
Assert-Equal $true (Test-DebugRunHardCapLeaseCurrent -Context $deploymentContext -LeasePath $dimensionLeasePath) 'matching deployment identity must be accepted'
$deploymentLease.deploymentId = 'beta-other-deployment'
$deploymentLease | ConvertTo-Json | Set-Content -LiteralPath $dimensionLeasePath -Encoding UTF8
Assert-Equal $false (Test-DebugRunHardCapLeaseCurrent -Context $deploymentContext -LeasePath $dimensionLeasePath) 'different deployment must never inherit stop authority'
foreach ($mutation in @(
    @{ name = 'state'; key = 'state'; value = 'CANCELLED' },
    @{ name = 'runId'; key = 'runId'; value = 'other-run' },
    @{ name = 'pid'; key = 'processId'; value = 45 },
    @{ name = 'start'; key = 'processStartUtc'; value = $processStartUtc.AddSeconds(2).ToString('o') },
    @{ name = 'deadline'; key = 'deadlineUtc'; value = $deadlineUtc.AddMilliseconds(1).ToString('o') }
)) {
    $changedLease = [ordered]@{}
    foreach ($key in $dimensionLease.Keys) { $changedLease[$key] = $dimensionLease[$key] }
    $changedLease[$mutation.key] = $mutation.value
    $changedLease | ConvertTo-Json | Set-Content -LiteralPath $dimensionLeasePath -Encoding UTF8
    Assert-Equal $false (Test-DebugRunHardCapLeaseCurrent -Context $leaseContext -LeasePath $dimensionLeasePath) "stale lease $($mutation.name) must be rejected"
}

# The detached watchdog must acknowledge ownership before Java treats the GUI
# hard-cap as armed. This executes the packaged watchdog against a fake PID and
# a future deadline, verifies its READY record, then explicitly stops only the
# test PowerShell child.
$watchdogDirectory = Join-Path ([IO.Path]::GetTempPath()) ("DebugRunHardCap_Ready_" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $watchdogDirectory | Out-Null
$startupLock = $null
try {
    $watchdogLeasePath = Join-Path $watchdogDirectory 'lease.json'
    $watchdogReadyPath = Join-Path $watchdogDirectory 'watchdog.ready.json'
    $watchdogStopMarkerPath = Join-Path $watchdogDirectory 'watchdog.stop.json'
    $watchdogLockPath = Join-Path $watchdogDirectory 'watchdog.lock'
    $watchdogStdoutPath = Join-Path $watchdogDirectory 'watchdog.stdout.log'
    $watchdogStderrPath = Join-Path $watchdogDirectory 'watchdog.stderr.log'
    $watchdogLogPath = Join-Path $watchdogDirectory 'watchdog.events.log'
    $watchdogDeadline = [datetime]::UtcNow.AddMinutes(5)
    $watchdogContext = New-DebugRunHardCapContext -RunId 'watchdog-ready' -ProcessId 424242 `
        -ProcessStartUtc $processStartUtc -DeadlineUtc $watchdogDeadline -DeploymentId 'beta-test'
    @{ schema = 1; state = 'ACTIVE'; runId = 'watchdog-ready'; deploymentId = 'beta-test'; processId = 424242
        processStartUtc = $processStartUtc.ToString('o'); deadlineUtc = $watchdogDeadline.ToString('o')
    } | ConvertTo-Json | Set-Content -LiteralPath $watchdogLeasePath -Encoding UTF8
    $watchdogScript = Join-Path $PSScriptRoot '..\..\..\main\resources\bat\debug-run-hardcap-watchdog.ps1'
    $watchdogArgs = @(
        '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $watchdogScript,
        '-RunId', $watchdogContext.RunId,
        '-DeploymentId', $watchdogContext.DeploymentId,
        '-ProcessId', $watchdogContext.ProcessId,
        '-ProcessStartUtc', $watchdogContext.ProcessStartUtc.ToString('o'),
        '-DeadlineUtc', $watchdogContext.DeadlineUtc.ToString('o'),
        '-LeasePath', $watchdogLeasePath,
        '-StopMarkerPath', $watchdogStopMarkerPath,
        '-OwnershipLockPath', $watchdogLockPath,
        '-ModulePath', $modulePath,
        '-ReadyPath', $watchdogReadyPath,
        '-WatchdogLogPath', $watchdogLogPath
    )
    $escapedWatchdogArgs = ($watchdogArgs | ForEach-Object {
        $value = [string]$_
        if ($value -match '\s|"') { '"' + $value.Replace('"', '\"') + '"' } else { $value }
    }) -join ' '
    # The final-stop ownership lock can be occupied during a previous hard-cap
    # transaction. That must not delay this new watchdog's run-specific READY
    # record beyond Java's ten-second handshake budget.
    $startupLock = [System.IO.File]::Open(
        $watchdogLockPath,
        [System.IO.FileMode]::OpenOrCreate,
        [System.IO.FileAccess]::ReadWrite,
        [System.IO.FileShare]::ReadWrite
    )
    $startupLock.Lock(0, 1)
    $startupLockHeldAt = [System.Diagnostics.Stopwatch]::StartNew()
    $watchdogProcess = Start-Process -FilePath 'powershell.exe' -ArgumentList $escapedWatchdogArgs `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $watchdogStdoutPath -RedirectStandardError $watchdogStderrPath
    $ready = $null
    for ($attempt = 0; $attempt -lt 100 -and $null -eq $ready; $attempt++) {
        if (Test-Path -LiteralPath $watchdogReadyPath -PathType Leaf) {
            try { $ready = Get-Content -LiteralPath $watchdogReadyPath -Raw | ConvertFrom-Json } catch { }
        }
        if ($null -eq $ready) { Start-Sleep -Milliseconds 100 }
    }
    Assert-Equal 'READY' ([string]$ready.state) 'packaged watchdog must create a ready handshake record'
    Assert-Equal $true ($startupLockHeldAt.ElapsedMilliseconds -lt 10000) 'READY must arrive before Java handshake timeout while final-stop lock is contended'
    Assert-Equal 'watchdog-ready' ([string]$ready.runId) 'ready record must bind the requested run id'
    Assert-Equal 'beta-test' ([string]$ready.deploymentId) 'ready record must bind the exact deployment identity'
    Assert-Equal 424242 ([int]$ready.processId) 'ready record must bind the requested process id'
    Assert-Equal ([DateTimeOffset]$watchdogDeadline.ToUniversalTime()).ToUnixTimeMilliseconds() ([long]$ready.deadlineEpochMillis) 'ready record must bind the exact deadline'
    $watchdogEvents = Get-Content -LiteralPath $watchdogLogPath -Raw
    Assert-Equal $true $watchdogEvents.Contains('event=WATCHDOG_LEASE_ACCEPTED') 'watchdog must prove lease validation before READY'
    Assert-Equal $true $watchdogEvents.Contains('event=WATCHDOG_READY') 'watchdog must log the lock-independent READY transition'
    Start-Sleep -Milliseconds 10500
    Assert-Equal $true ($startupLockHeldAt.ElapsedMilliseconds -ge 10000) 'test must keep the final-stop lock contended beyond the Java handshake budget'
} finally {
    if ($null -ne $startupLock) {
        try { $startupLock.Unlock(0, 1) } catch { }
        $startupLock.Dispose()
    }
    if ($null -ne $watchdogProcess) {
        try { Stop-Process -Id $watchdogProcess.Id -Force -ErrorAction SilentlyContinue } catch { }
        try { [void]$watchdogProcess.WaitForExit(5000) } catch { }
    }
    Remove-Item -LiteralPath $watchdogDirectory -Recurse -Force -ErrorAction SilentlyContinue
}

# The legacy E2E runner's job-based backstop must carry the same ownership
# arguments as the detached GUI watchdog. A cancelled lease proves the job
# received and honored that lease instead of falling back to an unguarded stop.
$backstopLeasePath = Join-Path $testOwnershipDirectory 'backstop-lease.json'
@{ schema = 1; state = 'CANCELLED' } | ConvertTo-Json | Set-Content -LiteralPath $backstopLeasePath -Encoding UTF8
$backstopContext = New-DebugRunHardCapContext -RunId 'backstop-cancelled' -ProcessId 424242 `
    -ProcessStartUtc $processStartUtc -DeadlineUtc ([datetime]::UtcNow.AddSeconds(-1))
$backstopJob = Start-DebugRunHardCapBackstop -Context $backstopContext -ModulePath $modulePath `
    -LeasePath $backstopLeasePath -StopMarkerPath $testStopMarkerPath -OwnershipLockPath $testOwnershipLockPath
try {
    Wait-Job -Job $backstopJob -Timeout 20 | Out-Null
    $backstopResult = @(Receive-Job -Job $backstopJob -ErrorAction Stop | Select-Object -Last 1)[0]
    Assert-Equal 'CANCELLED_OR_REPLACED' ([string]$backstopResult.Status) 'legacy backstop must reject a cancelled lease'
} finally {
    Stop-DebugRunHardCapBackstop -Job $backstopJob
}

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
$ownedAlive = { param($targetProcessId) return -not $stopped.Contains([int]$targetProcessId) }
$lateStop = Invoke-DebugRunHardCapStop -Context $context -NowUtc $lateWatchdogUtc -OwnershipLockPath $testOwnershipLockPath -StopMarkerPath $testStopMarkerPath -GetProcessIdentity $ownedIdentity -GetProcessTable $ownedProcesses -StopProcess $stopOwned -IsProcessAlive $ownedAlive
Assert-Equal 'STOPPED_OWNED_RUN' $lateStop.Status 'late watchdog must terminate the original owned run'
Assert-Sequence @(200, 100) $stopped.ToArray() 'only owned Hearthstone descendants then the JVM may be stopped'

# The exact deadline is expired; one millisecond before it is not.
$clockContext = New-DebugRunHardCapContext -RunId 'offline-clock' -ProcessId 300 -ProcessStartUtc $processStartUtc -DeadlineUtc $deadlineUtc
$clockStop = [System.Collections.Generic.List[int]]::new()
$clockIdentity = { param($targetProcessId) [pscustomobject]@{ ProcessId = $targetProcessId; StartUtc = $processStartUtc } }
$clockProcesses = { @([pscustomobject]@{ ProcessId = 300; ParentProcessId = 1; Name = 'java.exe' }) }
$clockStopper = { param($targetProcessId) [void]$clockStop.Add([int]$targetProcessId) }
$clockAlive = { param($targetProcessId) return -not $clockStop.Contains([int]$targetProcessId) }
$beforeDeadline = Invoke-DebugRunHardCapStop -Context $clockContext -NowUtc $deadlineUtc.AddMilliseconds(-1) -GetProcessIdentity $clockIdentity -GetProcessTable $clockProcesses -StopProcess $clockStopper
Assert-Equal 'WAIT' $beforeDeadline.Status 'hard cap must not fire early'
$atDeadline = Invoke-DebugRunHardCapStop -Context $clockContext -NowUtc $deadlineUtc -OwnershipLockPath $testOwnershipLockPath -StopMarkerPath $testStopMarkerPath -GetProcessIdentity $clockIdentity -GetProcessTable $clockProcesses -StopProcess $clockStopper -IsProcessAlive $clockAlive
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
$reusedAlive = { param($targetProcessId) return -not $reusedStops.Contains([int]$targetProcessId) }
$reusedResult = Invoke-DebugRunHardCapStop -Context $reusedPidContext -NowUtc $deadlineUtc -OwnershipLockPath $testOwnershipLockPath -StopMarkerPath $testStopMarkerPath -GetProcessIdentity $reusedIdentity -GetProcessTable $reusedProcesses -StopProcess $reusedStopper -IsProcessAlive $reusedAlive
Assert-Equal 'STALE_OR_EXITED_TARGET' $reusedResult.Status 'old watchdog must reject a restarted/reused PID'
Assert-Equal 0 $reusedStops.Count 'old watchdog must not stop retry process or its child'

# A second invocation after a successful stop is a no-op, which makes duplicate
# completion paths safe when the process monitor observes the same deadline.
$repeat = Invoke-DebugRunHardCapStop -Context $context -NowUtc $lateWatchdogUtc -OwnershipLockPath $testOwnershipLockPath -StopMarkerPath $testStopMarkerPath -GetProcessIdentity $ownedIdentity -GetProcessTable $ownedProcesses -StopProcess $stopOwned -IsProcessAlive $ownedAlive
Assert-Equal 'ALREADY_ISSUED' $repeat.Status 'stop must be idempotent'
Assert-Sequence @(200, 100) $stopped.ToArray() 'idempotent stop must not add a second termination'

# GUI Debug Run keeps a single authoritative lease record. A cancellation,
# extension/re-arm, or JVM restart replaces it, so an older detached watchdog
# cannot stop the new run when its old deadline arrives.
$leaseDirectory = Join-Path ([IO.Path]::GetTempPath()) ("DebugRunHardCap_" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $leaseDirectory | Out-Null
try {
    $leasePath = Join-Path $leaseDirectory 'debug-run-hardcap-lease.json'
    $guardianStatePath = Join-Path $leaseDirectory 'guardian-state.json'
    $stopMarkerPath = Join-Path $leaseDirectory 'debug-run-hardcap-stop.json'
    $ownershipLockPath = Join-Path $leaseDirectory 'debug-run-hardcap-ownership.lock'
    $guiContext = New-DebugRunHardCapContext -RunId 'gui-active' -ProcessId 500 -ProcessStartUtc $processStartUtc -DeadlineUtc $deadlineUtc
    @{
        schema = 1; state = 'ACTIVE'; runId = 'gui-active'; processId = 500
        processStartUtc = $processStartUtc.ToString('o'); deadlineUtc = $deadlineUtc.ToString('o')
    } | ConvertTo-Json | Set-Content -LiteralPath $leasePath -Encoding UTF8
    @{
        schema = 3; pid = 500; processStartTimeUtc = $processStartUtc.ToString('o')
        deploymentId = 'beta-test'; releaseChannel = 'beta'; intent = 'RUNNING'; working = $true
        paused = $false; pauseOrigin = 'NONE'; healthSnapshotKnown = $true
        heartbeatAtUtc = $processStartUtc.ToString('o'); recordedAtUtc = $processStartUtc.ToString('o')
    } | ConvertTo-Json | Set-Content -LiteralPath $guardianStatePath -Encoding UTF8
    $guiStops = [System.Collections.Generic.List[int]]::new()
    $guiIdentity = { param($targetProcessId) [pscustomobject]@{ ProcessId = $targetProcessId; StartUtc = $processStartUtc } }
    $guiProcesses = {
        @(
            [pscustomobject]@{ ProcessId = 500; ParentProcessId = 1; Name = 'javaw.exe' },
            [pscustomobject]@{ ProcessId = 501; ParentProcessId = 500; Name = 'Battle.net.exe' },
            [pscustomobject]@{ ProcessId = 502; ParentProcessId = 501; Name = 'Hearthstone.exe' }
        )
    }
    $guiStopper = { param($targetProcessId) [void]$guiStops.Add([int]$targetProcessId) }
    $guiAlive = { param($targetProcessId) return -not $guiStops.Contains([int]$targetProcessId) }
    $guiResult = Invoke-DebugRunHardCapStop -Context $guiContext -NowUtc $deadlineUtc -LeasePath $leasePath `
        -GuardianStatePath $guardianStatePath -StopMarkerPath $stopMarkerPath -OwnershipLockPath $ownershipLockPath `
        -GetProcessIdentity $guiIdentity -GetProcessTable $guiProcesses -StopProcess $guiStopper -IsProcessAlive $guiAlive
    Assert-Equal 'STOPPED_OWNED_RUN' $guiResult.Status 'active GUI lease must arm the hard stop'
    Assert-Sequence @(502, 500) $guiStops.ToArray() 'hard stop must stop owned Hearthstone descendants then JVM only'
    $stopMarker = Get-Content -LiteralPath $stopMarkerPath -Raw | ConvertFrom-Json
    Assert-Equal 'gui-active' $stopMarker.runId 'hard stop marker must bind the exact GUI run'
    Assert-Equal 500 $stopMarker.processId 'hard stop marker must bind the exact JVM'
    $guardianState = Get-Content -LiteralPath $guardianStatePath -Raw | ConvertFrom-Json
    Assert-Equal 'STOPPED' $guardianState.intent 'hard stop must persist non-restartable guardian intent before process termination'
    . (Join-Path $PSScriptRoot '..\..\..\main\resources\bat\launcher-guardian-policy.ps1')
    $guardianDecision = Get-HsScriptGuardianDecision -ChildAlive:$false -Intent ([string]$guardianState.intent) `
        -HeartbeatAgeSeconds 0 -CooldownElapsed:$true -RestartsInWindow 0 -HearthstoneAlive:$true
    Assert-Equal 'DO_NOT_RESTART' $guardianDecision 'guardian must not relaunch a hard-capped GUI run'

    $failedStops = [System.Collections.Generic.List[int]]::new()
    $refusingStopper = { param($targetProcessId) [void]$failedStops.Add([int]$targetProcessId) }
    $alwaysAlive = { param($targetProcessId) return $true }
    $failureContext = New-DebugRunHardCapContext -RunId 'gui-stop-refused' -ProcessId 550 -ProcessStartUtc $processStartUtc -DeadlineUtc $deadlineUtc
    $failureIdentity = { param($targetProcessId) [pscustomobject]@{ ProcessId = $targetProcessId; StartUtc = $processStartUtc } }
    $failureProcesses = { @([pscustomobject]@{ ProcessId = 550; ParentProcessId = 1; Name = 'javaw.exe' }) }
    $failedResult = Invoke-DebugRunHardCapStop -Context $failureContext -NowUtc $deadlineUtc `
        -OwnershipLockPath $ownershipLockPath -StopMarkerPath $stopMarkerPath `
        -GetProcessIdentity $failureIdentity -GetProcessTable $failureProcesses `
        -StopProcess $refusingStopper -IsProcessAlive $alwaysAlive
    Assert-Equal 'STOP_FAILED' $failedResult.Status 'a stop request without confirmed process exit must not report success'
    Assert-Equal $false $failedResult.RootStopped 'failed root termination must remain visible'
    Assert-Equal 1 $failedStops.Count 'failed root termination attempt must be observable'

    $cancelledContext = New-DebugRunHardCapContext -RunId 'gui-cancelled' -ProcessId 600 -ProcessStartUtc $processStartUtc -DeadlineUtc $deadlineUtc
    @{ schema = 1; state = 'CANCELLED' } | ConvertTo-Json | Set-Content -LiteralPath $leasePath -Encoding UTF8
    $cancelledResult = Invoke-DebugRunHardCapStop -Context $cancelledContext -NowUtc $deadlineUtc -LeasePath $leasePath `
        -StopMarkerPath $stopMarkerPath -OwnershipLockPath $ownershipLockPath -GetProcessIdentity $guiIdentity -GetProcessTable $guiProcesses -StopProcess $guiStopper -IsProcessAlive $guiAlive
    Assert-Equal 'CANCELLED_OR_REPLACED' $cancelledResult.Status 'manual disable must cancel detached watchdog'

    $extendedContext = New-DebugRunHardCapContext -RunId 'gui-extension-before-restart' -ProcessId 700 -ProcessStartUtc $processStartUtc -DeadlineUtc $deadlineUtc
    @{
        schema = 1; state = 'ACTIVE'; runId = 'gui-extension-before-restart'; processId = 700
        processStartUtc = $processStartUtc.ToString('o'); deadlineUtc = $deadlineUtc.AddMinutes(30).ToString('o')
    } | ConvertTo-Json | Set-Content -LiteralPath $leasePath -Encoding UTF8
    $extendedResult = Invoke-DebugRunHardCapStop -Context $extendedContext -NowUtc $deadlineUtc.AddMinutes(30) -LeasePath $leasePath `
        -StopMarkerPath $stopMarkerPath -OwnershipLockPath $ownershipLockPath -GetProcessIdentity $guiIdentity -GetProcessTable $guiProcesses -StopProcess $guiStopper -IsProcessAlive $guiAlive
    Assert-Equal 'CANCELLED_OR_REPLACED' $extendedResult.Status 'old deadline must not terminate an extended/re-armed lease'

    $restartContext = New-DebugRunHardCapContext -RunId 'gui-before-restart' -ProcessId 800 -ProcessStartUtc $processStartUtc -DeadlineUtc $deadlineUtc
    @{
        schema = 1; state = 'ACTIVE'; runId = 'gui-after-restart'; processId = 801
        processStartUtc = $processStartUtc.AddSeconds(1).ToString('o'); deadlineUtc = $deadlineUtc.AddMinutes(30).ToString('o')
    } | ConvertTo-Json | Set-Content -LiteralPath $leasePath -Encoding UTF8
    $restartResult = Invoke-DebugRunHardCapStop -Context $restartContext -NowUtc $deadlineUtc.AddMinutes(30) -LeasePath $leasePath `
        -StopMarkerPath $stopMarkerPath -OwnershipLockPath $ownershipLockPath -GetProcessIdentity $guiIdentity -GetProcessTable $guiProcesses -StopProcess $guiStopper -IsProcessAlive $guiAlive
    Assert-Equal 'CANCELLED_OR_REPLACED' $restartResult.Status 'pre-restart watchdog must not kill restarted JVM'

    $preparedContext = New-DebugRunHardCapContext -RunId 'gui-before-rearm' -ProcessId 900 -ProcessStartUtc $processStartUtc -DeadlineUtc $deadlineUtc
    @{
        schema = 1; state = 'ACTIVE'; runId = 'gui-before-rearm'; processId = 900
        processStartUtc = $processStartUtc.ToString('o'); deadlineUtc = $deadlineUtc.ToString('o')
    } | ConvertTo-Json | Set-Content -LiteralPath $leasePath -Encoding UTF8
    $rearmStopMarker = {
        param($runContext, $path)
        @{ schema = 1; state = 'ACTIVE'; runId = 'gui-after-rearm'; processId = 901
            processStartUtc = $processStartUtc.ToString('o'); deadlineUtc = $deadlineUtc.AddMinutes(30).ToString('o')
        } | ConvertTo-Json | Set-Content -LiteralPath $leasePath -Encoding UTF8
        return $true
    }
    $beforeRearmStops = $guiStops.Count
    $rearmResult = Invoke-DebugRunHardCapStop -Context $preparedContext -NowUtc $deadlineUtc -LeasePath $leasePath `
        -StopMarkerPath $stopMarkerPath -OwnershipLockPath $ownershipLockPath -GetProcessIdentity $guiIdentity -GetProcessTable $guiProcesses `
        -StopProcess $guiStopper -IsProcessAlive $guiAlive -SetStopMarker $rearmStopMarker -SetGuardianStopIntent { return $true }
    Assert-Equal 'CANCELLED_OR_REPLACED_AFTER_PREPARE' $rearmResult.Status 'a replacement lease written during preparation must revoke final ownership'
    Assert-Equal $beforeRearmStops $guiStops.Count 're-arm after preparation must dispatch no process stop'
} finally {
    Remove-Item -LiteralPath $leaseDirectory -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Output 'DebugRunHardCap.Tests.ps1 PASSED'
