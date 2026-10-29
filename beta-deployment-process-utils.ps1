$ErrorActionPreference = 'Stop'

function Assert-HsScriptBetaGameProcessAbsent(
    [scriptblock]$GetProcessRecords = { @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop) }
) {
    $processes = @(& $GetProcessRecords)
    $gamePids = @($processes | Where-Object { [string]$_.Name -ieq 'Hearthstone.exe' } |
        ForEach-Object { [int]$_.ProcessId })
    if ($gamePids.Count -gt 0) {
        throw "Refusing Beta deployment restart while Hearthstone is running (PID(s): $($gamePids -join ',')). The client may own an active match or queue; no game process was stopped."
    }
    return $true
}

function Start-HsScriptBetaRuntimeAfterDeploy(
    [string]$RuntimeRoot,
    [string]$DeploymentId,
    [scriptblock]$GetProcessRecords = { @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop) },
    [scriptblock]$StartCanonicalLauncher = $null,
    [scriptblock]$GetIdentity = ${function:Get-HsScriptProcessIdentity},
    [scriptblock]$GetProcessStartTime = ${function:Get-HsScriptProcessStartTimeUtc},
    [scriptblock]$Wait = { param($Milliseconds) Start-Sleep -Milliseconds $Milliseconds },
    [int]$TimeoutSeconds = 60
) {
    $root = Get-HsScriptCanonicalRuntimeRoot $RuntimeRoot
    if ((Split-Path -Leaf $root) -cne 'Hearthstone Script Beta') {
        throw "Beta restart refuses a non-Beta runtime root: $root"
    }
    if ([string]::IsNullOrWhiteSpace($DeploymentId)) { throw 'Beta restart requires the exact manifest deployment ID' }
    Assert-HsScriptBetaGameProcessAbsent -GetProcessRecords $GetProcessRecords | Out-Null
    $launcher = Join-Path $root 'launch-as-admin.vbs'
    if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) { throw "Canonical Beta launcher is missing: $launcher" }
    if ($null -eq $StartCanonicalLauncher) {
        $StartCanonicalLauncher = {
            param($LauncherPath, $WorkingDirectory)
            $wscript = Join-Path $env:WINDIR 'System32\wscript.exe'
            if (-not (Test-Path -LiteralPath $wscript -PathType Leaf)) { throw "wscript.exe was not found: $wscript" }
            Start-Process -FilePath $wscript -ArgumentList @('//B', "`"$LauncherPath`"") `
                -WorkingDirectory $WorkingDirectory -WindowStyle Hidden | Out-Null
        }
    }
    & $StartCanonicalLauncher $launcher $root

    $markerPath = Join-Path $root 'hs-script.pid.json'
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds([Math]::Max(1, $TimeoutSeconds))
    do {
        if (Test-Path -LiteralPath $markerPath -PathType Leaf) {
            try { $marker = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json -ErrorAction Stop }
            catch { $marker = $null }
            if ($null -ne $marker -and [string]$marker.deploymentId -ceq $DeploymentId) {
                $processId = 0
                if ([int]::TryParse([string]$marker.processId, [ref]$processId) -and $processId -gt 0) {
                    $identity = $null
                    try { $identity = & $GetIdentity $processId } catch { }
                    $startMatches = $false
                    try {
                        $markerStartValue = $marker.processStartTimeUtc
                        $recordedStart = if ($markerStartValue -is [DateTime]) {
                            [DateTimeOffset]::new($markerStartValue.ToUniversalTime())
                        } else {
                            [DateTimeOffset]::Parse([string]$markerStartValue).ToUniversalTime()
                        }
                        $observedStart = [DateTimeOffset]::Parse([string]$identity.ProcessStartTimeUtc).ToUniversalTime()
                        $startMatches = [Math]::Abs(($recordedStart - $observedStart).TotalMilliseconds) -le 1000
                    } catch { }
                    if ($null -ne $identity -and
                        [string]$identity.ProcessStartTimeUtc -and [string]$identity.ExecutablePath -and
                        $startMatches -and
                        [string]$marker.executablePath -and
                        ([System.IO.Path]::GetFullPath([string]$marker.executablePath) -ieq
                            [System.IO.Path]::GetFullPath([string]$identity.ExecutablePath))
                    ) {
                        return [pscustomobject]@{
                            ProcessId = $processId
                            DeploymentId = [string]$marker.deploymentId
                            ProcessStartTimeUtc = [string]$identity.ProcessStartTimeUtc
                            ExecutablePath = [System.IO.Path]::GetFullPath([string]$identity.ExecutablePath)
                            MarkerPath = $markerPath
                        }
                    }
                    # An elevated javaw process can deny both MainModule and CIM
                    # image-path reads. Fall back only when the process snapshot,
                    # marker start time, Java basename, and fully hash-validated
                    # current Beta deployment all agree. This cannot accept a stale
                    # marker for a reused PID or a previous/channel-mismatched build.
                    if ($null -eq $identity -or [string]::IsNullOrWhiteSpace([string]$identity.ExecutablePath)) {
                        $records = @(& $GetProcessRecords)
                        $record = $records | Where-Object { [int]$_.ProcessId -eq $processId } | Select-Object -First 1
                        $fallbackStartText = $null
                        try { $fallbackStartText = & $GetProcessStartTime $processId } catch { }
                        $fallbackStart = $null
                        try {
                            if ([string]$fallbackStartText) {
                                $fallbackStart = [DateTimeOffset]::Parse([string]$fallbackStartText).ToUniversalTime()
                            }
                        } catch { }
                        $recordedStart = $null
                        try {
                            $startValue = $marker.processStartTimeUtc
                            $recordedStart = if ($startValue -is [DateTime]) {
                                [DateTimeOffset]::new($startValue.ToUniversalTime())
                            } else { [DateTimeOffset]::Parse([string]$startValue).ToUniversalTime() }
                        } catch { }
                        $expectedExecutableName = [System.IO.Path]::GetFileName([string]$marker.executablePath)
                        $processNameMatches = $null -ne $record -and
                            [string]$record.Name -ieq $expectedExecutableName -and
                            $expectedExecutableName -in @('java.exe', 'javaw.exe')
                        $startTimeMatches = $null -ne $recordedStart -and $null -ne $fallbackStart -and
                            [Math]::Abs(($recordedStart - $fallbackStart).TotalMilliseconds) -le 1000
                        $deploymentMatches = $false
                        try {
                            $currentDeployment = Resolve-Deployment $root
                            $deploymentMatches = ([string]$currentDeployment.Manifest.releaseChannel).ToLowerInvariant() -ceq 'beta' -and
                                [string]$currentDeployment.DeploymentId -ceq $DeploymentId -and
                                [string]$marker.deploymentId -ceq $currentDeployment.DeploymentId
                        } catch { }
                        if ($processNameMatches -and $startTimeMatches -and $deploymentMatches) {
                            return [pscustomobject]@{
                                ProcessId = $processId
                                DeploymentId = [string]$marker.deploymentId
                                ProcessStartTimeUtc = $fallbackStart.ToString('o')
                                ExecutablePath = "java-fallback:$expectedExecutableName"
                                MarkerPath = $markerPath
                                Verification = 'marker-starttime-processname-current-beta-manifest-hash'
                            }
                        }
                    }
                }
            }
        }
        & $Wait 500
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Canonical Beta launcher did not produce a verifiable process marker for deployment $DeploymentId within $TimeoutSeconds seconds: $markerPath"
}

function Stop-ManagedHsScriptProcesses(
    [string]$RuntimeRoot,
    [scriptblock]$GetProcessRecords = { @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop) },
    [scriptblock]$GetIdentity = ${function:Get-HsScriptProcessIdentity},
    [scriptblock]$GetProcessStartTime = ${function:Get-HsScriptProcessStartTimeUtc},
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
        } elseif ([string]::IsNullOrWhiteSpace([string]$record.CommandLine) -and
            -not (Test-Path -LiteralPath (Join-Path $root 'hs-script.pid.json') -PathType Leaf)
        ) {
            throw "Unidentified Java PID $processId with no command line and no Beta ownership marker; refusing to assume idle"
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
            [string]::IsNullOrWhiteSpace([string]$markerData.deploymentId) -or
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
            } elseif (-not [string]$identity.ExecutablePath) {
                # Medium-integrity callers may be denied the elevated process
                # image path. Permit only the exact marker process after a
                # separate start-time read, matching executable basename, and
                # a fully hash-validated current Beta manifest/channel.
                $fallbackStartText = $null
                try { $fallbackStartText = & $GetProcessStartTime $processId } catch { }
                $fallbackStartUtc = $null
                try {
                    if ([string]$fallbackStartText) {
                        $fallbackStartUtc = [DateTimeOffset]::Parse([string]$fallbackStartText).ToUniversalTime()
                    }
                } catch { }
                $expectedName = [System.IO.Path]::GetFileName($marker.ExecutablePath)
                $startMatches = $null -ne $fallbackStartUtc -and
                    $fallbackStartUtc.UtcDateTime.Ticks -eq $markerStartUtc.UtcDateTime.Ticks
                $nameMatches = [string]$record.Name -ieq $expectedName -and
                    $expectedName -in @('java.exe', 'javaw.exe')
                $manifestMatches = $false
                try {
                    $deployment = Resolve-Deployment $root
                    $manifestMatches = ([string]$deployment.Manifest.releaseChannel).ToLowerInvariant() -ceq 'beta' -and
                        [string]$deployment.DeploymentId -ceq [string]$markerData.deploymentId
                } catch { }
                if ($startMatches -and $nameMatches -and $manifestMatches -and
                    ([string]$runtimeChannel.channel).ToLowerInvariant() -ceq 'beta'
                ) {
                    [void]$ownedProcessIds.Add($processId)
                } elseif ([string]$record.CommandLine -notmatch $jarPattern) {
                    throw "Beta runtime marker PID $processId cannot be verified; refusing to stop or assume idle"
                }
            } elseif ([string]$record.CommandLine -notmatch $jarPattern) {
                throw "Beta runtime marker PID $processId cannot be verified; refusing to stop or assume idle"
            }
        }
    }

    foreach ($record in $processes) {
        if ([string]$record.Name -in @('java.exe', 'javaw.exe') -and
            [string]::IsNullOrWhiteSpace([string]$record.CommandLine) -and
            -not $ownedProcessIds.Contains([int]$record.ProcessId)
        ) {
            throw "Unidentified Java PID $($record.ProcessId) has no visible command line or exact Beta marker identity; refusing to assume idle"
        }
    }

    foreach ($processId in @($ownedProcessIds) | Sort-Object) {
        & $StopProcess $processId
        Write-Output ([int]$processId)
    }
}
