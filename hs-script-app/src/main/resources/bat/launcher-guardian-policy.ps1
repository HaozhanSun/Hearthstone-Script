$script:HsScriptGuardianHeartbeatMaxAgeSeconds = 90
$script:HsScriptGuardianAppLogMaxAgeSeconds = 180
$script:HsScriptGuardianGameStartupGraceSeconds = 120
$script:HsScriptGuardianAlertRepeatSeconds = 300
$script:HsScriptGuardianMaxRestartsPerHour = 3

function Get-HsScriptGuardianDecision {
    param(
        [bool]$ChildAlive,
        [string]$Intent,
        [double]$HeartbeatAgeSeconds,
        [bool]$CooldownElapsed,
        [int]$RestartsInWindow,
        [bool]$HearthstoneAlive
    )

    if ($ChildAlive) { return 'WAIT_FOR_CHILD' }
    if ($Intent -in @('STOPPED', 'PAUSED_MANUAL')) { return 'DO_NOT_RESTART' }
    if ($Intent -ne 'RUNNING' -or $HeartbeatAgeSeconds -lt 0 -or
        $HeartbeatAgeSeconds -gt $script:HsScriptGuardianHeartbeatMaxAgeSeconds) {
        return 'DO_NOT_RESTART_UNVERIFIED'
    }
    if ($RestartsInWindow -ge $script:HsScriptGuardianMaxRestartsPerHour) { return 'STOP_RESTART_BUDGET' }
    if (-not $CooldownElapsed) { return 'WAIT_FOR_COOLDOWN' }
    if ($HearthstoneAlive) { return 'RESTART_SCRIPT_PRESERVE_GAME' }
    return 'RESTART_SCRIPT_AND_ALLOW_START_PATH'
}

# Read-only decision while the supervised Java process is still alive.
function Get-HsScriptLiveGuardianDecision {
    param(
        [bool]$ChildAlive,
        [object]$Observation,
        [bool]$HearthstoneAlive,
        [double]$HearthstoneMissingSeconds
    )

    if (-not $ChildAlive) { return 'PROCESS_EXITED' }
    if (-not $Observation.IdentityMatches) { return 'ALERT_UNVERIFIED_STATE' }
    if ($Observation.PauseOrigin -eq 'MANUAL' -and $Observation.Paused) { return 'PRESERVE_MANUAL_PAUSE' }
    if ($Observation.Intent -eq 'STOPPED' -and -not $Observation.Working) { return 'WAIT_STOPPED' }
    if (-not $Observation.Working) { return 'ALERT_UNVERIFIED_STATE' }
    if ($Observation.HeartbeatAgeSeconds -lt 0 -or
        $Observation.HeartbeatAgeSeconds -gt $script:HsScriptGuardianHeartbeatMaxAgeSeconds) {
        return 'ALERT_STALE_HEARTBEAT'
    }
    if ($Observation.ReleaseChannel -eq 'beta' -and
        ($Observation.AppLogAgeSeconds -lt 0 -or $Observation.AppLogAgeSeconds -gt $script:HsScriptGuardianAppLogMaxAgeSeconds)) {
        return 'ALERT_STALE_APP_LOG'
    }
    if ($Observation.Intent -ne 'RUNNING') { return 'ALERT_UNVERIFIED_INTENT' }
    if ($Observation.Paused) { return 'PRESERVE_PAUSED' }
    if (-not $Observation.HealthFieldsPresent -or -not $Observation.HealthSnapshotKnown) {
        if ($Observation.StateAgeSeconds -lt 30) { return 'WAIT_HEALTH_SNAPSHOT' }
        return 'ALERT_HEALTH_SNAPSHOT_UNKNOWN'
    }
    if ($HearthstoneAlive) { return 'HEALTHY' }
    if ($Observation.ActiveGame -or $Observation.ActiveMatchmaking) {
        return 'ALERT_GAME_MISSING_PRESERVE_SESSION'
    }
    if ($HearthstoneMissingSeconds -lt $script:HsScriptGuardianGameStartupGraceSeconds) {
        return 'WAIT_GAME_STARTUP_GRACE'
    }
    return 'ALERT_GAME_MISSING'
}

function Format-HsScriptGuardianLiveLogLine {
    param([object]$Run, [object]$Observation, [bool]$GameAlive, [string]$Action)
    return ("guardianLive pid={0} intent={1} heartbeatAgeSeconds={2:N1} gameAlive={3} " +
        "paused={4} pauseOrigin={5} activeGame={6} activeMatchmaking={7} action={8}") -f `
        $Run.Identity.ProcessId, $Observation.Intent, $Observation.HeartbeatAgeSeconds, $GameAlive, `
        $Observation.Paused, $Observation.PauseOrigin, $Observation.ActiveGame, `
        $Observation.ActiveMatchmaking, $Action
}

function Test-HsScriptStartTimeMatch {
    param(
        [string]$RecordedUtc,
        [string]$ObservedUtc,
        [long]$ToleranceMilliseconds = 1000
    )
    try {
        $recorded = [DateTimeOffset]::Parse($RecordedUtc).ToUniversalTime()
        $observed = [DateTimeOffset]::Parse($ObservedUtc).ToUniversalTime()
        return [Math]::Abs(($recorded - $observed).TotalMilliseconds) -le $ToleranceMilliseconds
    } catch { return $false }
}

function Enter-HsScriptGuardianMutex {
    param([Parameter(Mandatory)][string]$RuntimeKey)
    $mutex = [Threading.Mutex]::new($false, "Local\HsScriptGuardian_$RuntimeKey")
    $acquired = $false
    try { $acquired = $mutex.WaitOne(0) }
    catch [Threading.AbandonedMutexException] { $acquired = $true }
    return [pscustomobject]@{ Mutex = $mutex; Acquired = $acquired }
}

function Get-HsScriptGuardianObservation {
    param(
        [object]$State,
        [object]$Run,
        [DateTimeOffset]$NowUtc
    )
    $intent = 'UNKNOWN'
    $heartbeatAt = [DateTimeOffset]::MinValue
    $identityMatches = $null -ne $State -and
        [int64]$State.pid -eq [int64]$Run.Identity.ProcessId -and
        (Test-HsScriptStartTimeMatch -RecordedUtc ([string]$State.processStartTimeUtc) `
            -ObservedUtc ([string]$Run.Identity.ProcessStartTimeUtc)) -and
        [string]$State.deploymentId -eq [string]$Run.Deployment.DeploymentId
    $working = $false
    $releaseChannel = ''
    $appLogAge = [double]::PositiveInfinity
    $paused = $false
    $pauseOrigin = 'UNKNOWN'
    $activeGame = $false
    $activeMatchmaking = $false
    $healthSnapshotKnown = $false
    $healthFieldsPresent = $false
    if ($identityMatches) {
        try {
            $heartbeatAt = [DateTimeOffset]::Parse([string]$State.heartbeatAtUtc).ToUniversalTime()
            $intent = [string]$State.intent
            $releaseChannel = ([string]$State.releaseChannel).ToLowerInvariant()
            if ($releaseChannel -eq 'beta' -and $null -ne $State.PSObject.Properties['appLogAgeMs'] -and
                $null -ne $State.appLogAgeMs) { $appLogAge = [double]$State.appLogAgeMs / 1000.0 }
            $working = [bool]$State.working
            $paused = [bool]$State.paused
            $pauseOrigin = [string]$State.pauseOrigin
            $healthFieldsPresent = $null -ne $State.PSObject.Properties['gameAlive'] -and
                $null -ne $State.PSObject.Properties['activeGame'] -and
                $null -ne $State.PSObject.Properties['activeMatchmaking'] -and
                $null -ne $State.PSObject.Properties['healthSnapshotKnown']
            if ($healthFieldsPresent) {
                $activeGame = [bool]$State.activeGame
                $activeMatchmaking = [bool]$State.activeMatchmaking
                $healthSnapshotKnown = [bool]$State.healthSnapshotKnown
            }
        } catch { $intent = 'UNKNOWN' }
    }
    $age = if ($heartbeatAt -eq [DateTimeOffset]::MinValue) { [double]::PositiveInfinity } else { ($NowUtc - $heartbeatAt).TotalSeconds }
    $stateAge = if ($null -eq $State) { [double]::PositiveInfinity } else {
        try { ($NowUtc - [DateTimeOffset]::Parse([string]$State.recordedAtUtc).ToUniversalTime()).TotalSeconds }
        catch { $age }
    }
    if ($age -lt 0 -or $age -gt $script:HsScriptGuardianHeartbeatMaxAgeSeconds) { $intent = 'UNKNOWN' }
    return [pscustomobject]@{
        Intent = $intent
        HeartbeatAgeSeconds = $age
        StateAgeSeconds = $stateAge
        IdentityMatches = [bool]$identityMatches
        Working = $working
        Paused = $paused
        PauseOrigin = $pauseOrigin
        ActiveGame = $activeGame
        ActiveMatchmaking = $activeMatchmaking
        HealthFieldsPresent = $healthFieldsPresent
        HealthSnapshotKnown = $healthSnapshotKnown
        ReleaseChannel = $releaseChannel
        AppLogAgeSeconds = $appLogAge
    }
}

# Reconcile only the exact exited child. A newer launcher/process owns any
# marker or heartbeat with a different PID, start time, or deployment ID.
function Set-HsScriptExitedChildState {
    param(
        [Parameter(Mandatory)][string]$MarkerPath,
        [Parameter(Mandatory)][string]$GuardianStatePath,
        [Parameter(Mandatory)][object]$Run,
        [Parameter(Mandatory)][string]$Action,
        [Parameter(Mandatory)][int]$ExitCode,
        [string]$AppLogPath
    )
    $marker = $null
    try { $marker = Get-Content -LiteralPath $MarkerPath -Raw | ConvertFrom-Json -ErrorAction Stop } catch { return $false }
    if ([int64]$marker.processId -ne [int64]$Run.Identity.ProcessId -or
        -not (Test-HsScriptStartTimeMatch -RecordedUtc ([string]$marker.processStartTimeUtc) -ObservedUtc ([string]$Run.Identity.ProcessStartTimeUtc)) -or
        [string]$marker.deploymentId -ne [string]$Run.Deployment.DeploymentId) { return $false }
    $now = [DateTimeOffset]::UtcNow.ToString('o')
    $lastWrite = $null
    if ($AppLogPath -and (Test-Path -LiteralPath $AppLogPath -PathType Leaf)) {
        $lastWrite = (Get-Item -LiteralPath $AppLogPath).LastWriteTimeUtc.ToString('o')
    }
    foreach ($entry in @(@('processState', 'EXITED'), @('exitCode', $ExitCode), @('terminatedAtUtc', $now), @('lastAppLogWriteAtUtc', $lastWrite), @('guardianAction', $Action))) {
        $marker | Add-Member -NotePropertyName $entry[0] -NotePropertyValue $entry[1] -Force
    }
    $temp = "$MarkerPath.$([guid]::NewGuid().ToString('N')).tmp"
    $marker | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $temp -Encoding UTF8
    Move-Item -LiteralPath $temp -Destination $MarkerPath -Force

    $state = $null
    try { $state = Get-Content -LiteralPath $GuardianStatePath -Raw | ConvertFrom-Json -ErrorAction Stop } catch { return $true }
    if ([int64]$state.pid -ne [int64]$Run.Identity.ProcessId -or
        -not (Test-HsScriptStartTimeMatch -RecordedUtc ([string]$state.processStartTimeUtc) -ObservedUtc ([string]$Run.Identity.ProcessStartTimeUtc)) -or
        [string]$state.deploymentId -ne [string]$Run.Deployment.DeploymentId) { return $true }
    $state.intent = 'STOPPED'; $state.working = $false; $state.healthSnapshotKnown = $false
    foreach ($entry in @(@('processState', 'EXITED'), @('exitCode', $ExitCode), @('terminatedAtUtc', $now), @('lastAppLogWriteAtUtc', $lastWrite), @('guardianAction', $Action), @('reason', "child-exited:$Action"))) {
        $state | Add-Member -NotePropertyName $entry[0] -NotePropertyValue $entry[1] -Force
    }
    $stateTemp = "$GuardianStatePath.$([guid]::NewGuid().ToString('N')).tmp"
    $state | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $stateTemp -Encoding UTF8
    Move-Item -LiteralPath $stateTemp -Destination $GuardianStatePath -Force
    return $true
}

function Invoke-HsScriptGuardianLoop {
    param(
        [Parameter(Mandatory)][object]$InitialRun,
        [Parameter(Mandatory)][scriptblock]$StartRun,
        [Parameter(Mandatory)][scriptblock]$ReadState,
        [Parameter(Mandatory)][scriptblock]$IsHearthstoneAlive,
        [Parameter(Mandatory)][scriptblock]$WriteLog,
        [scriptblock]$Notify = { param($title, $message) },
        [scriptblock]$Sleep = { param($seconds) Start-Sleep -Seconds $seconds },
        [scriptblock]$NowUtc = { [DateTimeOffset]::UtcNow },
        [int]$PollMilliseconds = 5000
    )
    $run = $InitialRun
    $restarts = [System.Collections.Generic.List[DateTimeOffset]]::new()
    $lastRestartAt = [DateTimeOffset]::MinValue
    $gameMissingSince = $null
    $nextAlertAt = [DateTimeOffset]::MinValue
    $lastLiveAlert = ''
    while ($true) {
        if (-not $run.Process.WaitForExit($PollMilliseconds)) {
            $now = [DateTimeOffset](& $NowUtc)
            $state = & $ReadState $run
            $observation = Get-HsScriptGuardianObservation -State $state -Run $run -NowUtc $now
            $gameAlive = [bool](& $IsHearthstoneAlive)
            if ($gameAlive) {
                $gameMissingSince = $null
            } elseif ($null -eq $gameMissingSince) {
                $gameMissingSince = $now
            }
            $missingSeconds = if ($null -eq $gameMissingSince) { 0.0 } else { ($now - $gameMissingSince).TotalSeconds }
            $liveDecision = Get-HsScriptLiveGuardianDecision -ChildAlive:$true -Observation $observation `
                -HearthstoneAlive:$gameAlive -HearthstoneMissingSeconds:$missingSeconds
            $isAlert = $liveDecision.StartsWith('ALERT_')
            if ($isAlert -and ($liveDecision -ne $lastLiveAlert -or $now -ge $nextAlertAt)) {
                $line = Format-HsScriptGuardianLiveLogLine -Run $run -Observation $observation `
                    -GameAlive:$gameAlive -Action $liveDecision
                & $WriteLog $line | Out-Null
                $message = switch ($liveDecision) {
                    'ALERT_STALE_HEARTBEAT' { 'The Release Candidate process is still alive, but its health heartbeat is stale. Nothing was stopped or restarted. Inspect the app log/process, then use Start only after confirming the state.' }
                    'ALERT_STALE_APP_LOG' { 'The Beta process is still alive and its guardian heartbeat is fresh, but hs_script.log has stopped updating. No process was stopped or restarted. Inspect the selected Beta log path and app health.' }
                    'ALERT_GAME_MISSING_PRESERVE_SESSION' { 'Hearthstone is missing while the last fresh app state reported an active game or matchmaking. No process was stopped or relaunched. Inspect Hearthstone and its Power.log before taking action.' }
                    'ALERT_GAME_MISSING' { 'Hearthstone has been absent beyond the startup grace while the Release Candidate remains in RUNNING intent. The guardian did not kill or duplicate the live script. Inspect the app; its internal starter may still be recovering.' }
                    default { 'The Release Candidate guardian cannot verify safe runtime state. Nothing was stopped or restarted. Inspect the app process and guardian-state.json.' }
                }
                & $Notify 'Hearthstone Release Candidate needs attention' $message | Out-Null
                $lastLiveAlert = $liveDecision
                $nextAlertAt = $now.AddSeconds($script:HsScriptGuardianAlertRepeatSeconds)
            } elseif (-not $isAlert) {
                $lastLiveAlert = ''
                $nextAlertAt = [DateTimeOffset]::MinValue
            }
            continue
        }
        $now = [DateTimeOffset](& $NowUtc)
        $state = & $ReadState $run
        $observation = Get-HsScriptGuardianObservation -State $state -Run $run -NowUtc $now
        while ($restarts.Count -gt 0 -and ($now - $restarts[0]).TotalMinutes -ge 60) { $restarts.RemoveAt(0) }
        $cooldownElapsed = $lastRestartAt -eq [DateTimeOffset]::MinValue -or ($now - $lastRestartAt).TotalSeconds -ge 30
        $gameAlive = [bool](& $IsHearthstoneAlive)
        $decision = Get-HsScriptGuardianDecision -ChildAlive:$false -Intent $observation.Intent `
            -HeartbeatAgeSeconds $observation.HeartbeatAgeSeconds -CooldownElapsed:$cooldownElapsed `
            -RestartsInWindow $restarts.Count -HearthstoneAlive:$gameAlive
        $line = "childExited pid={0} exitCode={1} intent={2} heartbeatAgeSeconds={3:N1} gameAlive={4} action={5}" -f `
            $run.Identity.ProcessId, $run.Process.ExitCode, $observation.Intent, $observation.HeartbeatAgeSeconds, $gameAlive, $decision
        & $WriteLog $line | Out-Null
        if ($decision -eq 'WAIT_FOR_COOLDOWN') {
            & $Sleep 5
            continue
        }
        if ($decision -notin @('RESTART_SCRIPT_PRESERVE_GAME', 'RESTART_SCRIPT_AND_ALLOW_START_PATH')) {
            & $WriteLog "guardianStopped action=$decision reason=bounded-or-explicit-stop" | Out-Null
            return [pscustomobject]@{ Action = $decision; RestartCount = $restarts.Count; FinalRun = $run }
        }
        $now = [DateTimeOffset](& $NowUtc)
        $restarts.Add($now)
        $lastRestartAt = $now
        $run = & $StartRun $true
    }
}
