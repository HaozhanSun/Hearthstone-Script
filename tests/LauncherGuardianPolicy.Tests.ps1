$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\hs-script-app\src\main\resources\bat\launcher-guardian-policy.ps1')

function Assert-Equal($Expected, $Actual, [string]$Label) {
    if ($Expected -cne $Actual) { throw "$Label`: expected=[$Expected] actual=[$Actual]" }
}

$now = [DateTimeOffset]::UtcNow
$start = $now.AddHours(-1).ToString('o')
$run = [pscustomobject]@{
    Identity = [pscustomobject]@{ ProcessId = 43012; ProcessStartTimeUtc = $start }
    Deployment = [pscustomobject]@{ DeploymentId = 'beta-deploy-1' }
}
$freshBeta = [pscustomobject]@{
    pid = 43012; processStartTimeUtc = $start; deploymentId = 'beta-deploy-1'
    releaseChannel = 'beta'; intent = 'RUNNING'; working = $true; paused = $false; pauseOrigin = 'NONE'
    gameAlive = $true; activeGame = $false; activeMatchmaking = $false; healthSnapshotKnown = $true
    heartbeatAtUtc = $now.AddSeconds(-10).ToString('o'); recordedAtUtc = $now.AddSeconds(-10).ToString('o')
    appLogAgeMs = 240000
}
$betaObservation = Get-HsScriptGuardianObservation -State $freshBeta -Run $run -NowUtc $now
Assert-Equal 'ALERT_STALE_APP_LOG' (Get-HsScriptLiveGuardianDecision -ChildAlive:$true -Observation $betaObservation -HearthstoneAlive:$true -HearthstoneMissingSeconds:0) 'Beta fresh heartbeat with stale hs_script.log is unhealthy'

$freshRc = $freshBeta | Select-Object *
$freshRc.releaseChannel = 'release-candidate'
$freshRc.appLogAgeMs = 240000
$rcObservation = Get-HsScriptGuardianObservation -State $freshRc -Run $run -NowUtc $now
Assert-Equal 'HEALTHY' (Get-HsScriptLiveGuardianDecision -ChildAlive:$true -Observation $rcObservation -HearthstoneAlive:$true -HearthstoneMissingSeconds:0) 'RC behavior ignores Beta app-log age'

$formatted = Format-HsScriptGuardianLiveLogLine -Run $run -Observation $betaObservation -GameAlive:$true -Action 'ALERT_STALE_APP_LOG'
foreach ($value in @('pid=43012', 'intent=RUNNING', 'heartbeatAgeSeconds=10.0', 'gameAlive=True', 'action=ALERT_STALE_APP_LOG')) {
    if (-not $formatted.Contains($value)) { throw "Guardian health log did not interpolate [$value]: $formatted" }
}
if ($formatted.Contains('{0}') -or $formatted.Contains('{1}')) { throw "Guardian health log contains literal format tokens: $formatted" }

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("guardian-policy-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempRoot | Out-Null
try {
    $markerPath = Join-Path $tempRoot 'hs-script.pid.json'
    $statePath = Join-Path $tempRoot 'guardian-state.json'
    $marker = [ordered]@{ processId = 43012; processStartTimeUtc = $start; deploymentId = 'beta-deploy-1'; processState = 'RUNNING' }
    $state = [ordered]@{ pid = 43012; processStartTimeUtc = $start; deploymentId = 'beta-deploy-1'; releaseChannel = 'beta'; intent = 'RUNNING'; working = $true; healthSnapshotKnown = $true }
    $marker | ConvertTo-Json | Set-Content -LiteralPath $markerPath -Encoding UTF8
    $state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
    # The PID is intentionally absent: this helper is called only after the process handle observes exit.
    $updated = Set-HsScriptExitedChildState -MarkerPath $markerPath -GuardianStatePath $statePath -Run $run -Action 'DO_NOT_RESTART_UNVERIFIED' -ExitCode 7
    Assert-Equal $true $updated 'Matching stale PID marker is reconciled after process exit'
    $readMarker = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json
    $readState = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    Assert-Equal 'EXITED' $readMarker.processState 'PID marker reports terminated process'
    Assert-Equal 7 $readMarker.exitCode 'PID marker preserves exit code'
    Assert-Equal 'STOPPED' $readState.intent 'Guardian state cannot remain RUNNING after confirmed exit'
    Assert-Equal $false $readState.working 'Guardian working flag is false after exit'
    Assert-Equal $false $readState.healthSnapshotKnown 'Post-exit health is explicitly unknown'

    $newerRun = [pscustomobject]@{
        Identity = [pscustomobject]@{ ProcessId = 43013; ProcessStartTimeUtc = $start }
        Deployment = [pscustomobject]@{ DeploymentId = 'beta-deploy-2' }
    }
    $untouched = Set-HsScriptExitedChildState -MarkerPath $markerPath -GuardianStatePath $statePath -Run $newerRun -Action 'DO_NOT_RESTART' -ExitCode 0
    Assert-Equal $false $untouched 'Old exit cannot overwrite a newer PID marker'
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force
}

Write-Output 'LauncherGuardianPolicy.Tests.ps1: 14 assertions passed'
