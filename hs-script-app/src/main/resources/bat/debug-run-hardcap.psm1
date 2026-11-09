Set-StrictMode -Version Latest

function Get-DebugRunProcessIdentity {
    [CmdletBinding()]
    param([Parameter(Mandatory)][int]$ProcessId)

    try {
        $process = Get-Process -Id $ProcessId -ErrorAction Stop
        $process.Refresh()
        return [pscustomobject]@{
            ProcessId = $ProcessId
            StartUtc = $process.StartTime.ToUniversalTime()
        }
    } catch {
        return $null
    }
}

function New-DebugRunHardCapContext {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RunId,
        [Parameter(Mandatory)][int]$ProcessId,
        [Parameter(Mandatory)][datetime]$ProcessStartUtc,
        [Parameter(Mandatory)][datetime]$DeadlineUtc,
        [string]$DeploymentId = ''
    )

    [pscustomobject]@{
        RunId = $RunId
        DeploymentId = $DeploymentId
        ProcessId = $ProcessId
        ProcessStartUtc = $ProcessStartUtc.ToUniversalTime()
        DeadlineUtc = $DeadlineUtc.ToUniversalTime()
        StopIssued = $false
    }
}

function Get-DebugRunHardCapDelayMilliseconds {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][datetime]$DeadlineUtc,
        [datetime]$NowUtc = [datetime]::UtcNow
    )

    $remaining = $DeadlineUtc.ToUniversalTime() - $NowUtc.ToUniversalTime()
    return [Math]::Max(0L, [long][Math]::Ceiling($remaining.TotalMilliseconds))
}

function Test-DebugRunHardCapExpired {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Context,
        [datetime]$NowUtc = [datetime]::UtcNow
    )

    return (Get-DebugRunHardCapDelayMilliseconds -DeadlineUtc $Context.DeadlineUtc -NowUtc $NowUtc) -eq 0L
}

function Get-DebugRunHardCapLease {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$LeasePath)

    try {
        if (-not (Test-Path -LiteralPath $LeasePath -PathType Leaf)) { return $null }
        return Get-Content -LiteralPath $LeasePath -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
    } catch {
        return $null
    }
}

function Test-DebugRunProcessStartMatches {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][datetime]$ActualStartUtc,
        [Parameter(Mandatory)][datetime]$ExpectedStartUtc,
        # Process creation time is the ownership half of the watchdog token.
        # JVM/.NET conversion may differ by a small subsecond amount, but a
        # one-second difference is a distinct process and must reject a reused
        # PID rather than broadening the stop authority.
        [long]$ToleranceMilliseconds = 500
    )

    $difference = [Math]::Abs(($ActualStartUtc.ToUniversalTime() - $ExpectedStartUtc.ToUniversalTime()).TotalMilliseconds)
    return $difference -le $ToleranceMilliseconds
}

function Test-DebugRunHardCapLeaseCurrent {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][string]$LeasePath,
        [scriptblock]$GetLease = ${function:Get-DebugRunHardCapLease}
    )

    $lease = & $GetLease $LeasePath
    if ($null -eq $lease -or [string]$lease.state -ne 'ACTIVE') { return $false }
    try {
        $leaseProcessStart = ([datetime]$lease.processStartUtc).ToUniversalTime()
        $leaseDeadline = ([datetime]$lease.deadlineUtc).ToUniversalTime()
        return [string]$lease.runId -eq [string]$Context.RunId -and
            ([string]::IsNullOrWhiteSpace([string]$Context.DeploymentId) -or
                [string]$lease.deploymentId -eq [string]$Context.DeploymentId) -and
            [int]$lease.processId -eq [int]$Context.ProcessId -and
            (Test-DebugRunProcessStartMatches -ActualStartUtc $leaseProcessStart -ExpectedStartUtc $Context.ProcessStartUtc) -and
            $leaseDeadline.Ticks -eq $Context.DeadlineUtc.ToUniversalTime().Ticks
    } catch {
        return $false
    }
}

function Enter-DebugRunHardCapOwnershipLock {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$LockPath,
        [int]$Attempts = 1200
    )

    $directory = Split-Path -Parent $LockPath
    if ($directory) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        $stream = $null
        try {
            $stream = [System.IO.File]::Open(
                $LockPath,
                [System.IO.FileMode]::OpenOrCreate,
                [System.IO.FileAccess]::ReadWrite,
                [System.IO.FileShare]::ReadWrite
            )
            $stream.Lock(0, 1)
            return $stream
        } catch [System.IO.IOException] {
            if ($null -ne $stream) { $stream.Dispose() }
            Start-Sleep -Milliseconds 50
        } catch {
            if ($null -ne $stream) { $stream.Dispose() }
            throw
        }
    }
    return $null
}

function Exit-DebugRunHardCapOwnershipLock {
    [CmdletBinding()]
    param([AllowNull()][System.IO.FileStream]$LockStream)

    if ($null -eq $LockStream) { return }
    try { $LockStream.Unlock(0, 1) } catch { }
    $LockStream.Dispose()
}

function Set-DebugRunHardCapGuardianStopIntent {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][string]$GuardianStatePath
    )

    try {
        if (-not (Test-Path -LiteralPath $GuardianStatePath -PathType Leaf)) { return $false }
        $state = Get-Content -LiteralPath $GuardianStatePath -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
        if ([int64]$state.pid -ne [int64]$Context.ProcessId -or
            (-not [string]::IsNullOrWhiteSpace([string]$Context.DeploymentId) -and
                [string]$state.deploymentId -ne [string]$Context.DeploymentId) -or
            -not (Test-DebugRunProcessStartMatches -ActualStartUtc ([datetime]$state.processStartTimeUtc) -ExpectedStartUtc $Context.ProcessStartUtc)) {
            return $false
        }
        $now = [datetime]::UtcNow.ToString('o')
        foreach ($entry in @(
            @('intent', 'STOPPED'),
            @('working', $false),
            @('paused', $true),
            @('pauseOrigin', 'DEBUG_RUN_HARD_CAP'),
            @('healthSnapshotKnown', $false),
            @('hardCapRunId', [string]$Context.RunId),
            @('deploymentId', [string]$Context.DeploymentId),
            @('hardCapDeadlineUtc', $Context.DeadlineUtc.ToUniversalTime().ToString('o')),
            @('recordedAtUtc', $now),
            @('heartbeatAtUtc', $now),
            @('reason', "debug-run-hard-cap:$($Context.RunId)")
        )) {
            $state | Add-Member -NotePropertyName $entry[0] -NotePropertyValue $entry[1] -Force
        }
        $directory = Split-Path -Parent $GuardianStatePath
        $temporary = Join-Path $directory ("guardian-state.$([guid]::NewGuid().ToString('N')).tmp")
        $state | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $temporary -Encoding UTF8
        Move-Item -LiteralPath $temporary -Destination $GuardianStatePath -Force
        return $true
    } catch {
        return $false
    }
}

function Set-DebugRunHardCapStopMarker {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][string]$StopMarkerPath
    )

    try {
        $directory = Split-Path -Parent $StopMarkerPath
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
        $marker = [ordered]@{
            schema = 1
            runId = [string]$Context.RunId
            deploymentId = [string]$Context.DeploymentId
            processId = [int]$Context.ProcessId
            processStartUtc = $Context.ProcessStartUtc.ToUniversalTime().ToString('o')
            deadlineUtc = $Context.DeadlineUtc.ToUniversalTime().ToString('o')
            requestedAtUtc = [datetime]::UtcNow.ToString('o')
            reason = 'debug-run-hard-cap'
        }
        $temporary = "$StopMarkerPath.$([guid]::NewGuid().ToString('N')).tmp"
        $marker | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $temporary -Encoding UTF8
        Move-Item -LiteralPath $temporary -Destination $StopMarkerPath -Force
        return $true
    } catch {
        return $false
    }
}

function Write-DebugRunHardCapJsonAtomically {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)]$Value)

    $directory = Split-Path -Parent $Path
    if ($directory) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
    $temporary = "$Path.$([guid]::NewGuid().ToString('N')).tmp"
    $Value | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $temporary -Encoding UTF8
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Set-DebugRunHardCapLease {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][string]$LeasePath,
        [Parameter(Mandatory)][string]$OwnershipLockPath
    )

    $ownershipLock = Enter-DebugRunHardCapOwnershipLock -LockPath $OwnershipLockPath
    if ($null -eq $ownershipLock) { return $false }
    try {
        Write-DebugRunHardCapJsonAtomically -Path $LeasePath -Value ([ordered]@{
            schema = 1; state = 'ACTIVE'; runId = [string]$Context.RunId
            processId = [int]$Context.ProcessId
            processStartUtc = $Context.ProcessStartUtc.ToUniversalTime().ToString('o')
            deadlineUtc = $Context.DeadlineUtc.ToUniversalTime().ToString('o')
        })
        return $true
    } catch {
        return $false
    } finally {
        Exit-DebugRunHardCapOwnershipLock -LockStream $ownershipLock
    }
}

function Cancel-DebugRunHardCapLease {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$LeasePath,
        [Parameter(Mandatory)][string]$OwnershipLockPath,
        [string]$Reason = 'cancelled'
    )

    $ownershipLock = Enter-DebugRunHardCapOwnershipLock -LockPath $OwnershipLockPath
    if ($null -eq $ownershipLock) { return $false }
    try {
        Write-DebugRunHardCapJsonAtomically -Path $LeasePath -Value ([ordered]@{
            schema = 1; state = 'CANCELLED'; cancelledAtUtc = [datetime]::UtcNow.ToString('o'); reason = $Reason
        })
        return $true
    } catch {
        return $false
    } finally {
        Exit-DebugRunHardCapOwnershipLock -LockStream $ownershipLock
    }
}

function Start-DebugRunHardCapWatchdog {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][string]$LeasePath,
        [Parameter(Mandatory)][string]$OwnershipLockPath,
        [Parameter(Mandatory)][string]$StopMarkerPath,
        [string]$GuardianStatePath,
        [Parameter(Mandatory)][string]$WatchdogPath,
        [Parameter(Mandatory)][string]$ModulePath,
        [Parameter(Mandatory)][string]$ReadyPath,
        [Parameter(Mandatory)][string]$LogPath,
        [int]$ReadyTimeoutMilliseconds = 10000
    )

    Remove-Item -LiteralPath $ReadyPath -Force -ErrorAction SilentlyContinue
    $arguments = @(
        '-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-ExecutionPolicy', 'Bypass',
        '-File', $WatchdogPath,
        '-RunId', [string]$Context.RunId,
        '-ProcessId', [string]$Context.ProcessId,
        '-ProcessStartUtc', $Context.ProcessStartUtc.ToUniversalTime().ToString('o'),
        '-DeadlineUtc', $Context.DeadlineUtc.ToUniversalTime().ToString('o'),
        '-LeasePath', $LeasePath,
        '-StopMarkerPath', $StopMarkerPath,
        '-OwnershipLockPath', $OwnershipLockPath,
        '-ModulePath', $ModulePath,
        '-ReadyPath', $ReadyPath
    )
    if (-not [string]::IsNullOrWhiteSpace($GuardianStatePath)) {
        $arguments += @('-GuardianStatePath', $GuardianStatePath)
    }
    $escapedArguments = ($arguments | ForEach-Object {
        $value = [string]$_
        if ($value -match '\s|"') { '"' + $value.Replace('"', '\"') + '"' } else { $value }
    }) -join ' '
    $watchdog = $null
    try {
        $watchdog = Start-Process -FilePath 'powershell.exe' -ArgumentList $escapedArguments `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput $LogPath `
            -RedirectStandardError ($LogPath + '.err')
    } catch {
        return $null
    }

    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    while ($clock.ElapsedMilliseconds -lt $ReadyTimeoutMilliseconds) {
        if (-not $watchdog -or $watchdog.HasExited) { return $null }
        try {
            if (Test-Path -LiteralPath $ReadyPath -PathType Leaf) {
                $ready = Get-Content -LiteralPath $ReadyPath -Raw | ConvertFrom-Json
                if ([string]$ready.runId -eq [string]$Context.RunId -and
                    [int]$ready.processId -eq [int]$Context.ProcessId -and
                    [long]$ready.deadlineEpochMillis -eq ([DateTimeOffset]$Context.DeadlineUtc.ToUniversalTime()).ToUnixTimeMilliseconds()) {
                    return $watchdog
                }
            }
        } catch { }
        Start-Sleep -Milliseconds 50
    }
    try { Stop-Process -Id $watchdog.Id -Force -ErrorAction SilentlyContinue } catch { }
    return $null
}

function Get-DebugRunProcessTable {
    [CmdletBinding()]
    param()

    Get-CimInstance Win32_Process -ErrorAction Stop |
        ForEach-Object {
            [pscustomobject]@{
                ProcessId = [int]$_.ProcessId
                ParentProcessId = [int]$_.ParentProcessId
                Name = [string]$_.Name
            }
        }
}

function Get-DebugRunHearthstoneDescendantIds {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][int]$RootProcessId,
        [Parameter(Mandatory)][object[]]$Processes
    )

    $childrenByParent = @{}
    foreach ($process in $Processes) {
        $parentId = [int]$process.ParentProcessId
        if (-not $childrenByParent.ContainsKey($parentId)) {
            $childrenByParent[$parentId] = [System.Collections.Generic.List[object]]::new()
        }
        [void]$childrenByParent[$parentId].Add($process)
    }

    $pending = [System.Collections.Generic.Queue[int]]::new()
    $visited = [System.Collections.Generic.HashSet[int]]::new()
    $pending.Enqueue($RootProcessId)
    [void]$visited.Add($RootProcessId)
    $hearthstoneIds = [System.Collections.Generic.List[int]]::new()

    while ($pending.Count -gt 0) {
        $parentId = $pending.Dequeue()
        if (-not $childrenByParent.ContainsKey($parentId)) { continue }
        foreach ($child in $childrenByParent[$parentId]) {
            $childId = [int]$child.ProcessId
            if (-not $visited.Add($childId)) { continue }
            $pending.Enqueue($childId)
            if ([string]::Equals([string]$child.Name, 'Hearthstone.exe', [System.StringComparison]::OrdinalIgnoreCase)) {
                [void]$hearthstoneIds.Add($childId)
            }
        }
    }
    return $hearthstoneIds.ToArray()
}

function Stop-DebugRunHardCapTarget {
    [CmdletBinding()]
    param([Parameter(Mandatory)][int]$ProcessId)

    Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
}

function Test-DebugRunProcessAlive {
    [CmdletBinding()]
    param([Parameter(Mandatory)][int]$ProcessId)

    try {
        $process = Get-Process -Id $ProcessId -ErrorAction Stop
        $process.Refresh()
        return -not $process.HasExited
    } catch {
        return $false
    }
}

function Wait-DebugRunHardCapProcessExit {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][int]$ProcessId,
        [scriptblock]$IsAlive = ${function:Test-DebugRunProcessAlive},
        [int]$Attempts = 30
    )
    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        if (-not (& $IsAlive $ProcessId)) { return $true }
        Start-Sleep -Milliseconds 100
    }
    return -not (& $IsAlive $ProcessId)
}

function Invoke-DebugRunHardCapStop {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Context,
        [datetime]$NowUtc = [datetime]::UtcNow,
        [string]$LeasePath,
        [string]$GuardianStatePath,
        [string]$StopMarkerPath,
        [string]$OwnershipLockPath,
        [scriptblock]$GetProcessIdentity = ${function:Get-DebugRunProcessIdentity},
        [scriptblock]$GetProcessTable = ${function:Get-DebugRunProcessTable},
        [scriptblock]$StopProcess = ${function:Stop-DebugRunHardCapTarget},
        [scriptblock]$IsProcessAlive = ${function:Test-DebugRunProcessAlive},
        [scriptblock]$TestLeaseCurrent = ${function:Test-DebugRunHardCapLeaseCurrent},
        [scriptblock]$SetGuardianStopIntent = ${function:Set-DebugRunHardCapGuardianStopIntent},
        [scriptblock]$SetStopMarker = ${function:Set-DebugRunHardCapStopMarker}
    )

    if (-not (Test-DebugRunHardCapExpired -Context $Context -NowUtc $NowUtc)) {
        return [pscustomobject]@{ Status = 'WAIT'; RootStopped = $false; HearthstoneChildIds = @() }
    }
    if ($Context.StopIssued) {
        return [pscustomobject]@{ Status = 'ALREADY_ISSUED'; RootStopped = $false; HearthstoneChildIds = @() }
    }
    if (-not $OwnershipLockPath) {
        $Context.StopIssued = $true
        return [pscustomobject]@{ Status = 'OWNERSHIP_LOCK_REQUIRED'; RootStopped = $false; HearthstoneChildIds = @() }
    }
    if (-not $StopMarkerPath) {
        $Context.StopIssued = $true
        return [pscustomobject]@{ Status = 'STOP_MARKER_REQUIRED'; RootStopped = $false; HearthstoneChildIds = @() }
    }
    $ownershipLock = $null
    $ownershipLock = Enter-DebugRunHardCapOwnershipLock -LockPath $OwnershipLockPath
    if ($null -eq $ownershipLock) {
        $Context.StopIssued = $true
        return [pscustomobject]@{ Status = 'OWNERSHIP_LOCK_UNAVAILABLE'; RootStopped = $false; HearthstoneChildIds = @() }
    }
    try {
        # The shared lock serializes lease changes and this final stop transaction.
        # A GUI cancel/re-arm which wins the lock first makes this watchdog stale;
        # once this path holds it, no new lease can race the guardian write or kill.
        if ($LeasePath -and -not (& $TestLeaseCurrent $Context $LeasePath)) {
            $Context.StopIssued = $true
            return [pscustomobject]@{ Status = 'CANCELLED_OR_REPLACED'; RootStopped = $false; HearthstoneChildIds = @() }
        }

        $identity = & $GetProcessIdentity $Context.ProcessId
        if ($null -eq $identity -or
            [int]$identity.ProcessId -ne [int]$Context.ProcessId -or
        -not (Test-DebugRunProcessStartMatches -ActualStartUtc $identity.StartUtc -ExpectedStartUtc $Context.ProcessStartUtc)) {
            $Context.StopIssued = $true
            return [pscustomobject]@{ Status = 'STALE_OR_EXITED_TARGET'; RootStopped = $false; HearthstoneChildIds = @() }
        }

        if ($StopMarkerPath -and -not (& $SetStopMarker $Context $StopMarkerPath)) {
            $Context.StopIssued = $true
            return [pscustomobject]@{ Status = 'STOP_MARKER_UNVERIFIED'; RootStopped = $false; HearthstoneChildIds = @() }
        }
        if ($GuardianStatePath -and -not (& $SetGuardianStopIntent $Context $GuardianStatePath)) {
            $Context.StopIssued = $true
            return [pscustomobject]@{ Status = 'GUARDIAN_STATE_UNVERIFIED'; RootStopped = $false; HearthstoneChildIds = @() }
        }
        # Keep the second check after preparation as a defensive invariant and
        # as a guard against external lease replacement outside this process lock.
        if ($LeasePath -and -not (& $TestLeaseCurrent $Context $LeasePath)) {
            $Context.StopIssued = $true
            return [pscustomobject]@{ Status = 'CANCELLED_OR_REPLACED_AFTER_PREPARE'; RootStopped = $false; HearthstoneChildIds = @() }
        }

        $processes = @(& $GetProcessTable)
        $hearthstoneChildIds = @(Get-DebugRunHearthstoneDescendantIds -RootProcessId $Context.ProcessId -Processes $processes)
        $failedStops = [System.Collections.Generic.List[int]]::new()
        foreach ($childId in $hearthstoneChildIds) {
            try { & $StopProcess $childId } catch { }
            if (-not (Wait-DebugRunHardCapProcessExit -ProcessId $childId -IsAlive $IsProcessAlive)) {
                [void]$failedStops.Add([int]$childId)
            }
        }
        try { & $StopProcess $Context.ProcessId } catch { }
        $rootStopped = Wait-DebugRunHardCapProcessExit -ProcessId $Context.ProcessId -IsAlive $IsProcessAlive
        $Context.StopIssued = $true
        if (-not $rootStopped -or $failedStops.Count -gt 0) {
            return [pscustomobject]@{
                Status = 'STOP_FAILED'
                RootStopped = $rootStopped
                HearthstoneChildIds = $hearthstoneChildIds
                FailedProcessIds = @($failedStops.ToArray()) + $(if (-not $rootStopped) { @([int]$Context.ProcessId) } else { @() })
            }
        }
        return [pscustomobject]@{
            Status = 'STOPPED_OWNED_RUN'
            RootStopped = $true
            HearthstoneChildIds = $hearthstoneChildIds
        }
    } finally {
        Exit-DebugRunHardCapOwnershipLock -LockStream $ownershipLock
    }
}

function Start-DebugRunHardCapBackstop {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][string]$ModulePath,
        [Parameter(Mandatory)][string]$LeasePath,
        [Parameter(Mandatory)][string]$StopMarkerPath,
        [Parameter(Mandatory)][string]$OwnershipLockPath,
        [string]$GuardianStatePath
    )

    $snapshot = [pscustomobject]@{
        RunId = [string]$Context.RunId
        ProcessId = [int]$Context.ProcessId
        ProcessStartUtc = $Context.ProcessStartUtc.ToUniversalTime()
        DeadlineUtc = $Context.DeadlineUtc.ToUniversalTime()
        StopIssued = $false
    }
    Start-Job -ArgumentList $snapshot, $ModulePath, $LeasePath, $StopMarkerPath, $OwnershipLockPath, $GuardianStatePath -ScriptBlock {
        param($jobContext, $jobModulePath, $jobLeasePath, $jobStopMarkerPath, $jobOwnershipLockPath, $jobGuardianStatePath)
        Import-Module -Name $jobModulePath -Force -ErrorAction Stop
        $remainingMilliseconds = Get-DebugRunHardCapDelayMilliseconds -DeadlineUtc $jobContext.DeadlineUtc
        if ($remainingMilliseconds -gt 0) {
            Start-Sleep -Milliseconds $remainingMilliseconds
        }
        Invoke-DebugRunHardCapStop -Context $jobContext -LeasePath $jobLeasePath `
            -StopMarkerPath $jobStopMarkerPath -OwnershipLockPath $jobOwnershipLockPath `
            -GuardianStatePath $jobGuardianStatePath
    }
}

function Stop-DebugRunHardCapBackstop {
    [CmdletBinding()]
    param([AllowNull()]$Job)

    if ($null -eq $Job) { return }
    try {
        if ($Job.State -eq 'Running') { Stop-Job -Job $Job -ErrorAction SilentlyContinue }
    } finally {
        Remove-Job -Job $Job -Force -ErrorAction SilentlyContinue
    }
}

Export-ModuleMember -Function @(
    'Get-DebugRunProcessIdentity',
    'New-DebugRunHardCapContext',
    'Get-DebugRunHardCapDelayMilliseconds',
    'Test-DebugRunHardCapExpired',
    'Get-DebugRunHardCapLease',
    'Test-DebugRunProcessStartMatches',
    'Test-DebugRunHardCapLeaseCurrent',
    'Enter-DebugRunHardCapOwnershipLock',
    'Exit-DebugRunHardCapOwnershipLock',
    'Set-DebugRunHardCapLease',
    'Cancel-DebugRunHardCapLease',
    'Write-DebugRunHardCapJsonAtomically',
    'Set-DebugRunHardCapGuardianStopIntent',
    'Set-DebugRunHardCapStopMarker',
    'Get-DebugRunProcessTable',
    'Get-DebugRunHearthstoneDescendantIds',
    'Stop-DebugRunHardCapTarget',
    'Test-DebugRunProcessAlive',
    'Wait-DebugRunHardCapProcessExit',
    'Invoke-DebugRunHardCapStop',
    'Start-DebugRunHardCapBackstop',
    'Stop-DebugRunHardCapBackstop'
)
