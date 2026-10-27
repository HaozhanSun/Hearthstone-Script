$ErrorActionPreference = 'Stop'

function Stop-ManagedHsScriptProcesses(
    [string]$RuntimeRoot,
    [scriptblock]$GetProcessRecords = { @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop) },
    [scriptblock]$GetIdentity = ${function:Get-HsScriptProcessIdentity},
    [scriptblock]$StopProcess = { param($ProcessId) Stop-Process -Id ([int]$ProcessId) -Force -ErrorAction Stop }
) {
    $root = Get-HsScriptCanonicalRuntimeRoot $RuntimeRoot
    if ((Split-Path -Leaf $root) -cne 'Hearthstone Script Beta') {
        throw "Beta process stop refuses a non-Beta runtime root: $root"
    }

    $processes = @(& $GetProcessRecords)
    $recordsByPid = @{}
    foreach ($record in $processes) {
        $processId = 0
        if ([int]::TryParse([string]$record.ProcessId, [ref]$processId) -and $processId -gt 0) {
            $recordsByPid[$processId] = $record
        }
    }

    $ownedProcessIds = [System.Collections.Generic.HashSet[int]]::new()
    $jarPattern = '(?i)' + [regex]::Escape($root) + '[\\/]+hs-script_[^\\/\s"]+\.jar'
    foreach ($record in $processes) {
        if ([string]$record.Name -notin @('java.exe', 'javaw.exe')) { continue }
        $processId = [int]$record.ProcessId
        if ([string]$record.CommandLine -match $jarPattern) {
            [void]$ownedProcessIds.Add($processId)
        }
    }

    $markerPath = Join-Path $root 'hs-script.pid.json'
    if (Test-Path -LiteralPath $markerPath -PathType Leaf) {
        $rawMarker = [System.IO.File]::ReadAllText($markerPath)
        try { $markerData = $rawMarker | ConvertFrom-Json -ErrorAction Stop }
        catch { throw "Beta runtime ownership marker is unreadable; refusing to assume idle: $markerPath" }
        $channelConfigPath = Join-Path $root 'release-channel.json'
        if (-not (Test-Path -LiteralPath $channelConfigPath -PathType Leaf)) {
            throw "Beta runtime channel configuration is missing beside ownership marker: $channelConfigPath"
        }
        try { $runtimeChannel = Get-Content -LiteralPath $channelConfigPath -Raw | ConvertFrom-Json -ErrorAction Stop }
        catch { throw "Beta runtime channel configuration is unreadable: $channelConfigPath" }
        $markerPid = 0
        $markerStartMatch = [regex]::Match($rawMarker, '"processStartTimeUtc"\s*:\s*"(?<value>[^"]+)"')
        if ([int]$markerData.schema -ne 1 -or
            ([string]$markerData.releaseChannel).ToLowerInvariant() -cne 'beta' -or
            ([string]$runtimeChannel.channel).ToLowerInvariant() -cne 'beta' -or
            [string]$markerData.runtimeDirectoryName -cne 'Hearthstone Script Beta' -or
            [string]$runtimeChannel.runtimeDirectoryName -cne 'Hearthstone Script Beta' -or
            -not [int]::TryParse([string]$markerData.processId, [ref]$markerPid) -or
            $markerPid -le 0 -or -not $markerStartMatch.Success -or
            [string]::IsNullOrWhiteSpace([string]$markerData.executablePath) -or
            (Get-HsScriptCanonicalRuntimeRoot ([string]$markerData.runtimeRoot)) -ine $root
        ) { throw "Beta runtime ownership marker is incomplete or mismatched; refusing to assume idle: $markerPath" }
        try { $markerStartUtc = [DateTimeOffset]::Parse($markerStartMatch.Groups['value'].Value).ToUniversalTime() }
        catch { throw "Beta runtime ownership marker has an invalid process start time: $markerPath" }
        $marker = [pscustomobject]@{
            RuntimeRoot = $root
            ProcessId = $markerPid
            ProcessStartTimeUtc = $markerStartUtc
            ExecutablePath = [System.IO.Path]::GetFullPath([string]$markerData.executablePath)
        }
        $processId = [int]$marker.ProcessId
        # A stale marker is safe to ignore only after the process snapshot proves
        # that PID is absent. Do not use `continue` here: this code is in the
        # function body, outside a loop, where PowerShell can terminate its caller.
        if ($recordsByPid.ContainsKey($processId)) {
            $record = $recordsByPid[$processId]
            if ([string]$record.Name -notin @('java.exe', 'javaw.exe')) {
                throw "Beta runtime marker PID $processId is owned by a non-Java process; refusing to stop it"
            }
            $identity = $null
            try { $identity = & $GetIdentity $processId } catch { }
            $identityStartUtc = if ($null -ne $identity -and [string]$identity.ProcessStartTimeUtc) {
                [DateTimeOffset]::Parse([string]$identity.ProcessStartTimeUtc).ToUniversalTime()
            } else { $null }
            $markerMatches = $null -ne $identityStartUtc -and
                [string]$identity.ExecutablePath -and
                [Math]::Abs(($identityStartUtc - $markerStartUtc).TotalMilliseconds) -le 1000 -and
                ([System.IO.Path]::GetFullPath([string]$identity.ExecutablePath) -ieq [string]$marker.ExecutablePath)
            if ($markerMatches) {
                [void]$ownedProcessIds.Add($processId)
            } elseif ([string]$record.CommandLine -notmatch $jarPattern) {
                throw "Beta runtime marker PID $processId cannot be verified; refusing to stop or assume idle"
            }
        }
    }

    foreach ($processId in @($ownedProcessIds) | Sort-Object) {
        & $StopProcess $processId
        Write-Output ([int]$processId)
    }
}
