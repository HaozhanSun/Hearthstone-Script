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
        [Parameter(Mandatory)][datetime]$DeadlineUtc
    )

    [pscustomobject]@{
        RunId = $RunId
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

function Invoke-DebugRunHardCapStop {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Context,
        [datetime]$NowUtc = [datetime]::UtcNow,
        [scriptblock]$GetProcessIdentity = ${function:Get-DebugRunProcessIdentity},
        [scriptblock]$GetProcessTable = ${function:Get-DebugRunProcessTable},
        [scriptblock]$StopProcess = ${function:Stop-DebugRunHardCapTarget}
    )

    if (-not (Test-DebugRunHardCapExpired -Context $Context -NowUtc $NowUtc)) {
        return [pscustomobject]@{ Status = 'WAIT'; RootStopped = $false; HearthstoneChildIds = @() }
    }
    if ($Context.StopIssued) {
        return [pscustomobject]@{ Status = 'ALREADY_ISSUED'; RootStopped = $false; HearthstoneChildIds = @() }
    }

    # The PID plus creation time is the ownership token. A PID without the
    # original start time may have been recycled for a new Java process.
    $identity = & $GetProcessIdentity $Context.ProcessId
    if ($null -eq $identity -or
        [int]$identity.ProcessId -ne [int]$Context.ProcessId -or
        $identity.StartUtc.ToUniversalTime().Ticks -ne $Context.ProcessStartUtc.ToUniversalTime().Ticks) {
        $Context.StopIssued = $true
        return [pscustomobject]@{ Status = 'STALE_OR_EXITED_TARGET'; RootStopped = $false; HearthstoneChildIds = @() }
    }

    $processes = @(& $GetProcessTable)
    $hearthstoneChildIds = @(Get-DebugRunHearthstoneDescendantIds -RootProcessId $Context.ProcessId -Processes $processes)
    foreach ($childId in $hearthstoneChildIds) {
        & $StopProcess $childId
    }
    & $StopProcess $Context.ProcessId
    $Context.StopIssued = $true
    return [pscustomobject]@{
        Status = 'STOPPED_OWNED_RUN'
        RootStopped = $true
        HearthstoneChildIds = $hearthstoneChildIds
    }
}

function Start-DebugRunHardCapBackstop {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Context,
        [Parameter(Mandatory)][string]$ModulePath
    )

    $snapshot = [pscustomobject]@{
        RunId = [string]$Context.RunId
        ProcessId = [int]$Context.ProcessId
        ProcessStartUtc = $Context.ProcessStartUtc.ToUniversalTime()
        DeadlineUtc = $Context.DeadlineUtc.ToUniversalTime()
        StopIssued = $false
    }
    Start-Job -ArgumentList $snapshot, $ModulePath -ScriptBlock {
        param($jobContext, $jobModulePath)
        Import-Module -Name $jobModulePath -Force -ErrorAction Stop
        $remainingMilliseconds = Get-DebugRunHardCapDelayMilliseconds -DeadlineUtc $jobContext.DeadlineUtc
        if ($remainingMilliseconds -gt 0) {
            Start-Sleep -Milliseconds $remainingMilliseconds
        }
        Invoke-DebugRunHardCapStop -Context $jobContext
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
    'Get-DebugRunProcessTable',
    'Get-DebugRunHearthstoneDescendantIds',
    'Stop-DebugRunHardCapTarget',
    'Invoke-DebugRunHardCapStop',
    'Start-DebugRunHardCapBackstop',
    'Stop-DebugRunHardCapBackstop'
)
