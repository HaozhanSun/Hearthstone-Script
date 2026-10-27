$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\..\main\resources\bat\launcher-guardian-policy.ps1')

function Assert-Equal([string]$Expected, [string]$Actual, [string]$Name) {
    if ($Expected -ne $Actual) { throw "$Name expected '$Expected' but got '$Actual'" }
}

function New-Observation {
    param(
        [string]$Intent = 'RUNNING',
        [double]$HeartbeatAgeSeconds = 5,
        [bool]$IdentityMatches = $true,
        [bool]$Working = $true,
        [bool]$Paused = $false,
        [string]$PauseOrigin = 'NONE',
        [bool]$ActiveGame = $false,
        [bool]$ActiveMatchmaking = $false,
        [bool]$HealthFieldsPresent = $true,
        [bool]$HealthSnapshotKnown = $true,
        [double]$StateAgeSeconds = 5
    )
        [pscustomobject]@{
            Intent = $Intent
            HeartbeatAgeSeconds = $HeartbeatAgeSeconds
            IdentityMatches = $IdentityMatches
            Working = $Working
            Paused = $Paused
            PauseOrigin = $PauseOrigin
            ActiveGame = $ActiveGame
            ActiveMatchmaking = $ActiveMatchmaking
            HealthFieldsPresent = $HealthFieldsPresent
            HealthSnapshotKnown = $HealthSnapshotKnown
            StateAgeSeconds = $StateAgeSeconds
        }
}

class TestGuardianProcess {
    [int]$WaitCalls = 0
    [int]$ExitCode = 0
    [bool] WaitForExit([int]$Milliseconds) {
        $this.WaitCalls++
        return $this.WaitCalls -ge 2
    }
}

Assert-Equal 'HEALTHY' (Get-HsScriptLiveGuardianDecision -ChildAlive $true `
    -Observation (New-Observation) -HearthstoneAlive $true -HearthstoneMissingSeconds 0) 'fresh heartbeat/game present'
Assert-Equal 'ALERT_STALE_HEARTBEAT' (Get-HsScriptLiveGuardianDecision -ChildAlive $true `
    -Observation (New-Observation -HeartbeatAgeSeconds 91) -HearthstoneAlive $true -HearthstoneMissingSeconds 0) 'stale heartbeat surfaced'
Assert-Equal 'ALERT_UNVERIFIED_STATE' (Get-HsScriptLiveGuardianDecision -ChildAlive $true `
    -Observation (New-Observation -IdentityMatches $false -Working $false -PauseOrigin 'MANUAL' -Paused $true) `
    -HearthstoneAlive $true -HearthstoneMissingSeconds 0) 'foreign state cannot impersonate a manual pause'
Assert-Equal 'ALERT_UNVERIFIED_STATE' (Get-HsScriptLiveGuardianDecision -ChildAlive $true `
    -Observation (New-Observation -Intent 'RUNNING' -Working $false) `
    -HearthstoneAlive $true -HearthstoneMissingSeconds 0) 'inconsistent running intent is surfaced'

Assert-Equal 'WAIT_GAME_STARTUP_GRACE' (Get-HsScriptLiveGuardianDecision -ChildAlive $true `
    -Observation (New-Observation) -HearthstoneAlive $false -HearthstoneMissingSeconds 30) 'game startup grace'
Assert-Equal 'ALERT_GAME_MISSING' (Get-HsScriptLiveGuardianDecision -ChildAlive $true `
    -Observation (New-Observation) -HearthstoneAlive $false -HearthstoneMissingSeconds 121) 'persistent game loss surfaced'
Assert-Equal 'ALERT_GAME_MISSING_PRESERVE_SESSION' (Get-HsScriptLiveGuardianDecision -ChildAlive $true `
    -Observation (New-Observation -ActiveMatchmaking $true) -HearthstoneAlive $false -HearthstoneMissingSeconds 121) 'queue evidence prevents recovery action'
Assert-Equal 'ALERT_GAME_MISSING_PRESERVE_SESSION' (Get-HsScriptLiveGuardianDecision -ChildAlive $true `
    -Observation (New-Observation -ActiveGame $true) -HearthstoneAlive $false -HearthstoneMissingSeconds 121) 'active game evidence prevents recovery action'

Assert-Equal 'PRESERVE_MANUAL_PAUSE' (Get-HsScriptLiveGuardianDecision -ChildAlive $true `
    -Observation (New-Observation -Paused $true -PauseOrigin 'MANUAL' -HeartbeatAgeSeconds 200) `
    -HearthstoneAlive $false -HearthstoneMissingSeconds 500) 'F2/manual pause is authoritative'
Assert-Equal 'ALERT_HEALTH_SNAPSHOT_UNKNOWN' (Get-HsScriptLiveGuardianDecision -ChildAlive $true `
    -Observation (New-Observation -HealthFieldsPresent $false -HealthSnapshotKnown $false -StateAgeSeconds 31) `
    -HearthstoneAlive $true -HearthstoneMissingSeconds 0) 'missing health telemetry is visible, not inferred safe'
Assert-Equal 'PROCESS_EXITED' (Get-HsScriptLiveGuardianDecision -ChildAlive $false `
    -Observation (New-Observation) -HearthstoneAlive $false -HearthstoneMissingSeconds 0) 'Java exit is observed'

Assert-Equal 'RESTART_SCRIPT_AND_ALLOW_START_PATH' (Get-HsScriptGuardianDecision -ChildAlive $false `
    -Intent 'RUNNING' -HeartbeatAgeSeconds 10 -CooldownElapsed $true -RestartsInWindow 0 -HearthstoneAlive $false) 'bounded canonical relaunch decision'
Assert-Equal 'DO_NOT_RESTART' (Get-HsScriptGuardianDecision -ChildAlive $false `
    -Intent 'PAUSED_MANUAL' -HeartbeatAgeSeconds 10 -CooldownElapsed $true -RestartsInWindow 0 -HearthstoneAlive $false) 'manual pause prevents relaunch'
Assert-Equal 'STOP_RESTART_BUDGET' (Get-HsScriptGuardianDecision -ChildAlive $false `
    -Intent 'RUNNING' -HeartbeatAgeSeconds 10 -CooldownElapsed $true -RestartsInWindow 3 -HearthstoneAlive $false) 'restart budget is bounded'

$testNow = [DateTimeOffset]::Parse('2026-10-26T12:00:00Z')
$fakeProcess = [TestGuardianProcess]::new()
$fakeRun = [pscustomobject]@{
    Process = $fakeProcess
    Identity = [pscustomobject]@{ ProcessId = 4242; ProcessStartTimeUtc = '2026-10-26T11:00:00Z' }
    Deployment = [pscustomobject]@{ DeploymentId = 'test-deployment' }
}
$readCount = 0
$liveLogs = [System.Collections.Generic.List[string]]::new()
$notifications = [System.Collections.Generic.List[string]]::new()
$unexpectedStartCount = 0
$loopResult = Invoke-HsScriptGuardianLoop -InitialRun $fakeRun `
    -StartRun { $script:unexpectedStartCount++; throw 'live child must never be relaunched' } `
    -ReadState {
        $script:readCount++
        $intent = if ($script:readCount -eq 1) { 'RUNNING' } else { 'STOPPED' }
        $heartbeat = if ($script:readCount -eq 1) { $testNow.AddSeconds(-120) } else { $testNow }
        [pscustomobject]@{
            pid = 4242
            processStartTimeUtc = '2026-10-26T11:00:00Z'
            deploymentId = 'test-deployment'
            intent = $intent
            working = $intent -eq 'RUNNING'
            paused = $false
            pauseOrigin = 'NONE'
            heartbeatAtUtc = $heartbeat.ToString('o')
            recordedAtUtc = $testNow.ToString('o')
            gameAlive = $true
            activeGame = $false
            activeMatchmaking = $false
            healthSnapshotKnown = $true
        }
    } `
    -IsHearthstoneAlive { $true } `
    -Notify { param($title, $message) $notifications.Add("$title|$message") } `
    -WriteLog { param($line) $liveLogs.Add($line) } `
    -NowUtc { $testNow } -Sleep { } -PollMilliseconds 1
Assert-Equal '0' ([string]$unexpectedStartCount) 'live child is never relaunched for stale heartbeat'
Assert-Equal '1' ([string]$notifications.Count) 'stale live heartbeat produces visible alert'
Assert-Equal '1' ([string]@($liveLogs | Where-Object { $_ -match 'action=ALERT_STALE_HEARTBEAT' }).Count) 'stale heartbeat is logged'
Assert-Equal 'DO_NOT_RESTART' $loopResult.Action 'guardian waits once child explicitly stops'

Write-Output 'PASS launcher guardian policy cases: live/stale heartbeat, process exit, Hearthstone grace/missing, F2, active game/queue preservation, unknown telemetry, and restart bounds'
