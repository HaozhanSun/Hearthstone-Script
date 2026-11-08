$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $root 'hs-script-app/src/main/resources/bat/deployment-contract.ps1')
. (Join-Path $root 'beta-deployment-process-utils.ps1')

function Assert-Equal($Expected, $Actual, [string]$Label) {
    if ($Expected -ne $Actual) { throw "$Label expected=[$Expected] actual=[$Actual]" }
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("beta-deployment-stop-" + [guid]::NewGuid().ToString('N'))
$runtimeRoot = Join-Path $tempRoot 'Hearthstone Script Beta'
$otherRoot = Join-Path $tempRoot 'Hearthstone Script Release Candidate'
$javaPath = 'C:\Program Files\Java\jdk-25.0.4\bin\javaw.exe'
$betaJar = Join-Path $runtimeRoot 'hs-script_v4.16.700.jar'
$rcJar = Join-Path $otherRoot 'hs-script_v4.16.701.jar'
$stopped = [System.Collections.Generic.List[int]]::new()
function Write-TestBetaManifest([string]$RuntimeRoot, [string]$DeploymentId) {
    $relativeFiles = @{
        appJar = 'hs-script_v4.16.700.jar'
        strategyPluginLib = 'lib\hs-script-base-strategy-plugin.jar'
        strategyPlugin = 'plugin\hs-script-base-strategy-plugin.jar'
        cardSdk = 'lib\hs-script-card-sdk-1.3.0.jar'
    }
    $hashes = @{}
    foreach ($path in $relativeFiles.Values | Select-Object -Unique) {
        $fullPath = Join-Path $RuntimeRoot $path
        $parent = Split-Path -Parent $fullPath
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
        $content = if ($path -match 'hs-script-base-strategy-plugin\.jar$') { 'fixture=strategy' } else { "fixture=$path" }
        [System.IO.File]::WriteAllText($fullPath, $content)
        $hashes[$path] = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash
    }
    $manifest = [ordered]@{
        schema = 1
        releaseChannel = 'beta'
        runtimeDirectoryName = 'Hearthstone Script Beta'
        appJar = $relativeFiles.appJar
        appJarSha256 = $hashes[$relativeFiles.appJar]
        strategyPluginLib = $relativeFiles.strategyPluginLib
        strategyPluginSha256 = $hashes[$relativeFiles.strategyPluginLib]
        strategyPlugin = $relativeFiles.strategyPlugin
        cardSdk = $relativeFiles.cardSdk
        cardSdkSha256 = $hashes[$relativeFiles.cardSdk]
        deploymentId = $DeploymentId
    } | ConvertTo-Json
    [System.IO.File]::WriteAllText((Join-Path $RuntimeRoot 'deployment-manifest.json'), $manifest)
}
try {
    New-Item -ItemType Directory -Path $runtimeRoot, $otherRoot -Force | Out-Null
    $noProcesses = @(Stop-ManagedHsScriptProcesses -RuntimeRoot $runtimeRoot -GetProcessRecords { @() } -StopProcess { param($ProcessId) $stopped.Add([int]$ProcessId) })
    Assert-Equal 0 $noProcesses.Count 'empty runtime is a no-op'
    Assert-Equal 0 $stopped.Count 'no-op does not request process termination'

    $records = @(
        [pscustomobject]@{ Name = 'javaw.exe'; ProcessId = 101; CommandLine = "javaw.exe -jar `"$betaJar`"" },
        [pscustomobject]@{ Name = 'java.exe'; ProcessId = 102; CommandLine = "java.exe -jar `"$rcJar`"" },
        [pscustomobject]@{ Name = 'java.exe'; ProcessId = 103; CommandLine = 'java.exe -jar C:\tools\unrelated.jar' },
        [pscustomobject]@{ Name = 'Hearthstone.exe'; ProcessId = 104; CommandLine = $null }
    )
    $stoppedIds = @(Stop-ManagedHsScriptProcesses -RuntimeRoot $runtimeRoot -GetProcessRecords { $records } -StopProcess { param($ProcessId) $stopped.Add([int]$ProcessId) })
    Assert-Equal 1 $stoppedIds.Count 'only exact Beta runtime Java PID is selected'
    Assert-Equal 101 $stoppedIds[0] 'Beta PID selected'
    Assert-Equal 101 $stopped[0] 'termination callback receives Beta PID only'

    $config = @{ channel = 'beta'; runtimeDirectoryName = 'Hearthstone Script Beta' } | ConvertTo-Json
    [System.IO.File]::WriteAllText((Join-Path $runtimeRoot 'release-channel.json'), $config)
    $deploymentId = 'hs-script_v4.16.700.jar|fixture'
    Write-TestBetaManifest $runtimeRoot $deploymentId
    $markerStart = '2026-10-27T19:00:00.0000000Z'
    $gameAbsent = Assert-HsScriptBetaGameProcessAbsent -GetProcessRecords { @() }
    Assert-Equal $true $gameAbsent 'Beta restart proceeds only when no Hearthstone process can own a queue'
    $activeGameRestartBlocked = $false
    try {
        Assert-HsScriptBetaGameProcessAbsent -GetProcessRecords {
            @([pscustomobject]@{ Name = 'Hearthstone.exe'; ProcessId = 4404; CommandLine = '' })
        } | Out-Null
    } catch { $activeGameRestartBlocked = $_.Exception.Message -match 'active match or queue' }
    Assert-Equal $true $activeGameRestartBlocked 'any live Hearthstone PID fails closed without killing it'

    $launcherPath = Join-Path $runtimeRoot 'launch-as-admin.vbs'
    [System.IO.File]::WriteAllText($launcherPath, 'fixture launcher')
    $newDeploymentId = 'hs-script_v4.16.711.jar|fresh-fixture'
    $newStart = '2026-10-29T15:43:00.0000000Z'
    $launcherCalls = [System.Collections.Generic.List[string]]::new()
    $newIdentity = { param($ProcessId) [pscustomobject]@{
        ProcessId = $ProcessId; ProcessStartTimeUtc = $newStart; ExecutablePath = $javaPath
    } }
    $startNewRuntime = {
        param($Launcher, $WorkingDirectory)
        Assert-Equal $launcherPath $Launcher 'post-deploy launch uses canonical manifest-backed VBS'
        Assert-Equal $runtimeRoot $WorkingDirectory 'canonical launcher starts from Beta runtime root'
        $launcherCalls.Add($Launcher) | Out-Null
        $newMarker = @{
            processId = 77011; processStartTimeUtc = $newStart; executablePath = $javaPath
            deploymentId = $newDeploymentId
        } | ConvertTo-Json
        [System.IO.File]::WriteAllText((Join-Path $runtimeRoot 'hs-script.pid.json'), $newMarker)
    }.GetNewClosure()
    $restarted = Start-HsScriptBetaRuntimeAfterDeploy -RuntimeRoot $runtimeRoot `
        -DeploymentId $newDeploymentId -GetProcessRecords { @() } -StartCanonicalLauncher $startNewRuntime `
        -GetIdentity $newIdentity -Wait { param($Milliseconds) } -TimeoutSeconds 1
    Assert-Equal 1 $launcherCalls.Count 'canonical Beta launcher invoked after install'
    Assert-Equal $launcherPath $launcherCalls[0] 'post-deploy restart uses canonical Beta launcher'
    Assert-Equal 77011 $restarted.ProcessId 'restart PID is independently verified against its identity'
    Assert-Equal $newDeploymentId $restarted.DeploymentId 'restart verifies exact manifest deployment ID'

    # Elevated process executable-path reads may be denied. The fallback still
    # requires exact marker PID/start time, javaw basename, and a hash-verified
    # current Beta manifest/deployment ID.
    Write-TestBetaManifest $runtimeRoot $newDeploymentId
    $fallbackStart = '2026-10-29T16:53:00.0000000Z'
    $fallbackPid = 77012
    $fallbackStartMarker = @{
        processId = $fallbackPid; processStartTimeUtc = $fallbackStart
        executablePath = $javaPath; deploymentId = $newDeploymentId
    } | ConvertTo-Json
    $fallbackStartLauncher = {
        param($Launcher, $WorkingDirectory)
        [System.IO.File]::WriteAllText((Join-Path $runtimeRoot 'hs-script.pid.json'), $fallbackStartMarker)
    }.GetNewClosure()
    $fallbackStartIdentity = { param($ProcessId) [pscustomobject]@{
        ProcessId = $ProcessId; ProcessStartTimeUtc = $fallbackStart; ExecutablePath = ''
    } }
    $fallbackStartRecords = { @([pscustomobject]@{ Name = 'javaw.exe'; ProcessId = $fallbackPid; CommandLine = '' }) }
    $fallbackRestart = Start-HsScriptBetaRuntimeAfterDeploy -RuntimeRoot $runtimeRoot `
        -DeploymentId $newDeploymentId -GetProcessRecords $fallbackStartRecords `
        -StartCanonicalLauncher $fallbackStartLauncher -GetIdentity $fallbackStartIdentity `
        -GetProcessStartTime { param($ProcessId) $fallbackStart } -Wait { param($Milliseconds) } -TimeoutSeconds 1
    Assert-Equal $fallbackPid $fallbackRestart.ProcessId 'path-denied fallback verifies exact PID'
    Assert-Equal 'marker-starttime-processname-current-beta-manifest-hash' $fallbackRestart.Verification 'fallback verification is explicit'

    function Assert-FallbackRestartRefused([string]$Label, [scriptblock]$GetRecords, [scriptblock]$GetStartTime, [scriptblock]$StartLauncher = $fallbackStartLauncher, [string]$ExpectedDeploymentId = $newDeploymentId) {
        $refused = $false
        try {
            Start-HsScriptBetaRuntimeAfterDeploy -RuntimeRoot $runtimeRoot -DeploymentId $ExpectedDeploymentId `
                -GetProcessRecords $GetRecords -StartCanonicalLauncher $StartLauncher `
                -GetIdentity $fallbackStartIdentity -GetProcessStartTime $GetStartTime `
                -Wait { param($Milliseconds) } -TimeoutSeconds 1 | Out-Null
        } catch { $refused = $_.Exception.Message -match 'did not produce a verifiable process marker' }
        Assert-Equal $true $refused $Label
    }
    Assert-FallbackRestartRefused 'wrong PID in process snapshot is refused' `
        { @([pscustomobject]@{ Name = 'javaw.exe'; ProcessId = 77013; CommandLine = '' }) } `
        { param($ProcessId) $fallbackStart }
    Assert-FallbackRestartRefused 'wrong process start time is refused' $fallbackStartRecords `
        { param($ProcessId) '2026-10-29T16:52:58.0000000Z' }
    $currentManifestPath = Join-Path $runtimeRoot 'deployment-manifest.json'
    $validManifestRaw = [System.IO.File]::ReadAllText($currentManifestPath)
    $wrongChannelManifest = ($validManifestRaw | ConvertFrom-Json | Select-Object * | ConvertTo-Json).Replace('"releaseChannel": "beta"', '"releaseChannel": "release-candidate"')
    [System.IO.File]::WriteAllText($currentManifestPath, $wrongChannelManifest)
    Assert-FallbackRestartRefused 'wrong channel/current manifest is refused' $fallbackStartRecords `
        { param($ProcessId) $fallbackStart }
    [System.IO.File]::WriteAllText($currentManifestPath, $validManifestRaw)
    $appJarPath = Join-Path $runtimeRoot 'hs-script_v4.16.700.jar'
    [System.IO.File]::AppendAllText($appJarPath, '-tampered')
    Assert-FallbackRestartRefused 'bad current artifact hash is refused' $fallbackStartRecords `
        { param($ProcessId) $fallbackStart }
    [System.IO.File]::WriteAllText($appJarPath, 'fixture=hs-script_v4.16.700.jar')
    # Restore the exact fixture manifest after restoring the artifact bytes.
    Write-TestBetaManifest $runtimeRoot $newDeploymentId
    $emptyStartLauncher = { param($Launcher, $WorkingDirectory) Remove-Item -LiteralPath (Join-Path $runtimeRoot 'hs-script.pid.json') -Force -ErrorAction SilentlyContinue }.GetNewClosure()
    Assert-FallbackRestartRefused 'missing marker is refused' { @() } { param($ProcessId) $fallbackStart } $emptyStartLauncher
    Write-TestBetaManifest $runtimeRoot $deploymentId

    $wrongChannelRestartBlocked = $false
    try {
        Start-HsScriptBetaRuntimeAfterDeploy -RuntimeRoot $otherRoot -DeploymentId $newDeploymentId `
            -GetProcessRecords { @() } -StartCanonicalLauncher { } -TimeoutSeconds 1 | Out-Null
    } catch { $wrongChannelRestartBlocked = $_.Exception.Message -match 'non-Beta runtime root' }
    Assert-Equal $true $wrongChannelRestartBlocked 'restart helper refuses non-Beta runtime roots'

    $marker = @{
        schema = 1
        releaseChannel = 'beta'
        runtimeDirectoryName = 'Hearthstone Script Beta'
        runtimeRoot = $runtimeRoot
        processId = 105
        processStartTimeUtc = $markerStart
        executablePath = $javaPath
        deploymentId = $deploymentId
    } | ConvertTo-Json
    [System.IO.File]::WriteAllText((Join-Path $runtimeRoot 'hs-script.pid.json'), $marker)
    $markerOnlyRecord = @([pscustomobject]@{ Name = 'javaw.exe'; ProcessId = 105; CommandLine = '' })
    $matchingIdentity = { param($ProcessId) [pscustomobject]@{ ProcessId = $ProcessId; ProcessStartTimeUtc = $markerStart; ExecutablePath = $javaPath } }
    $markerStopped = @(Stop-ManagedHsScriptProcesses -RuntimeRoot $runtimeRoot -GetProcessRecords { $markerOnlyRecord } -GetIdentity $matchingIdentity -StopProcess { param($ProcessId) $stopped.Add([int]$ProcessId) })
    Assert-Equal 1 $markerStopped.Count 'blank CommandLine can be stopped only through exact verified Beta marker identity'
    Assert-Equal 105 $markerStopped[0] 'verified marker PID selected'

    # Medium-integrity fallback: executable path lookup fails, but an exact
    # process start time, javaw name, Beta channel config, and current manifest
    # deployment ID all agree with the marker.
    $fallbackMarker = @{
        schema = 1; releaseChannel = 'beta'; runtimeDirectoryName = 'Hearthstone Script Beta'
        runtimeRoot = $runtimeRoot; processId = 107; processStartTimeUtc = $markerStart
        executablePath = $javaPath; deploymentId = $deploymentId
    } | ConvertTo-Json
    [System.IO.File]::WriteAllText((Join-Path $runtimeRoot 'hs-script.pid.json'), $fallbackMarker)
    $fallbackRecord = @([pscustomobject]@{ Name = 'javaw.exe'; ProcessId = 107; CommandLine = '' })
    $fallbackStopped = @(Stop-ManagedHsScriptProcesses -RuntimeRoot $runtimeRoot `
        -GetProcessRecords { $fallbackRecord } -GetIdentity { throw 'Access denied to elevated ExecutablePath' } `
        -GetProcessStartTime { param($ProcessId) $markerStart } `
        -StopProcess { param($ProcessId) $stopped.Add([int]$ProcessId) })
    Assert-Equal 1 $fallbackStopped.Count 'validated marker fallback identifies exact elevated Beta process'
    Assert-Equal 107 $fallbackStopped[0] 'fallback PID selected'

    $mismatchedStart = $false
    try {
        Stop-ManagedHsScriptProcesses -RuntimeRoot $runtimeRoot -GetProcessRecords { $fallbackRecord } `
            -GetIdentity { throw 'Access denied' } -GetProcessStartTime { param($ProcessId) '2026-10-27T15:37:59.9528539Z' } `
            -StopProcess { param($ProcessId) $stopped.Add([int]$ProcessId) } | Out-Null
    } catch { $mismatchedStart = $_.Exception.Message -match 'cannot be verified' }
    Assert-Equal $true $mismatchedStart 'PID reuse with a different start time is refused'

    $mismatchedName = $false
    $javaRecord = @([pscustomobject]@{ Name = 'java.exe'; ProcessId = 107; CommandLine = '' })
    try {
        Stop-ManagedHsScriptProcesses -RuntimeRoot $runtimeRoot -GetProcessRecords { $javaRecord } `
            -GetIdentity { throw 'Access denied' } -GetProcessStartTime { param($ProcessId) $markerStart } `
            -StopProcess { param($ProcessId) $stopped.Add([int]$ProcessId) } | Out-Null
    } catch { $mismatchedName = $_.Exception.Message -match 'cannot be verified' }
    Assert-Equal $true $mismatchedName 'java.exe cannot inherit a javaw.exe marker'

    $wrongManifestMarker = $fallbackMarker.Replace($deploymentId, 'other-deployment')
    [System.IO.File]::WriteAllText((Join-Path $runtimeRoot 'hs-script.pid.json'), $wrongManifestMarker)
    $manifestMismatch = $false
    try {
        Stop-ManagedHsScriptProcesses -RuntimeRoot $runtimeRoot -GetProcessRecords { $fallbackRecord } `
            -GetIdentity { throw 'Access denied' } -GetProcessStartTime { param($ProcessId) $markerStart } `
            -StopProcess { param($ProcessId) $stopped.Add([int]$ProcessId) } | Out-Null
    } catch { $manifestMismatch = $_.Exception.Message -match 'cannot be verified' }
    Assert-Equal $true $manifestMismatch 'fallback requires marker deployment ID to match current verified Beta manifest'

    [System.IO.File]::WriteAllText((Join-Path $runtimeRoot 'hs-script.pid.json'), $fallbackMarker)
    $manifestPath = Join-Path $runtimeRoot 'deployment-manifest.json'
    $hiddenManifestPath = "$manifestPath.test-hidden"
    Move-Item -LiteralPath $manifestPath -Destination $hiddenManifestPath
    $missingManifestRefused = $false
    try {
        Stop-ManagedHsScriptProcesses -RuntimeRoot $runtimeRoot -GetProcessRecords { $fallbackRecord } `
            -GetIdentity { throw 'Access denied' } -GetProcessStartTime { param($ProcessId) $markerStart } `
            -StopProcess { param($ProcessId) $stopped.Add([int]$ProcessId) } | Out-Null
    } catch { $missingManifestRefused = $_.Exception.Message -match 'cannot be verified' }
    Move-Item -LiteralPath $hiddenManifestPath -Destination $manifestPath
    Assert-Equal $true $missingManifestRefused 'missing current manifest refuses fallback ownership'

    [System.IO.File]::WriteAllText((Join-Path $runtimeRoot 'hs-script.pid.json'), '{"schema":')
    $corruptMarkerRefused = $false
    try { Stop-ManagedHsScriptProcesses -RuntimeRoot $runtimeRoot -GetProcessRecords { $fallbackRecord } | Out-Null }
    catch { $corruptMarkerRefused = $_.Exception.Message -match 'unreadable' }
    Assert-Equal $true $corruptMarkerRefused 'corrupt ownership marker fails closed'

    Remove-Item -LiteralPath (Join-Path $runtimeRoot 'hs-script.pid.json') -Force
    $foreignRcRecord = @([pscustomobject]@{ Name = 'javaw.exe'; ProcessId = 44880; CommandLine = '' })
    $foreignRcStopped = @(Stop-ManagedHsScriptProcesses -RuntimeRoot $runtimeRoot `
        -GetProcessRecords { $foreignRcRecord } `
        -GetWindowTitle { param($ProcessId) if ($ProcessId -eq 44880) { 'HS Script Release Candidate' } else { '' } } `
        -StopProcess { param($ProcessId) $stopped.Add([int]$ProcessId) })
    Assert-Equal 0 $foreignRcStopped.Count 'exact RC titled JVM is excluded as a foreign process'

    $unknownTitleRefused = $false
    try {
        Stop-ManagedHsScriptProcesses -RuntimeRoot $runtimeRoot `
            -GetProcessRecords { $foreignRcRecord } `
            -GetWindowTitle { param($ProcessId) 'Unknown Java Window' } | Out-Null
    } catch { $unknownTitleRefused = $_.Exception.Message -match 'no Beta ownership marker' }
    Assert-Equal $true $unknownTitleRefused 'unknown titled JVM still fails closed without a Beta marker'

    [System.IO.File]::WriteAllText((Join-Path $runtimeRoot 'hs-script.pid.json'), $marker)
    $markerStoppedWithRcTitle = @(Stop-ManagedHsScriptProcesses -RuntimeRoot $runtimeRoot `
        -GetProcessRecords { $markerOnlyRecord } -GetIdentity $matchingIdentity `
        -GetWindowTitle { param($ProcessId) 'HS Script Release Candidate' } `
        -StopProcess { param($ProcessId) $stopped.Add([int]$ProcessId) })
    Assert-Equal 1 $markerStoppedWithRcTitle.Count 'exact Beta marker ownership is not suppressed by a foreign-title probe'
    Assert-Equal 105 $markerStoppedWithRcTitle[0] 'verified Beta marker PID remains selected'

    Remove-Item -LiteralPath (Join-Path $runtimeRoot 'hs-script.pid.json') -Force
    $missingMarkerRefused = $false
    try {
        Stop-ManagedHsScriptProcesses -RuntimeRoot $runtimeRoot `
            -GetProcessRecords { @([pscustomobject]@{ Name = 'javaw.exe'; ProcessId = 108; CommandLine = '' }) } | Out-Null
    } catch { $missingMarkerRefused = $_.Exception.Message -match 'no Beta ownership marker' }
    Assert-Equal $true $missingMarkerRefused 'missing marker with an unidentified JVM does not assume idle'

    $unrelatedJava = @(Stop-ManagedHsScriptProcesses -RuntimeRoot $runtimeRoot `
        -GetProcessRecords { @([pscustomobject]@{ Name = 'javaw.exe'; ProcessId = 109; CommandLine = 'javaw.exe -jar C:\tools\unrelated.jar' }) } `
        -StopProcess { param($ProcessId) $stopped.Add([int]$ProcessId) })
    Assert-Equal 0 $unrelatedJava.Count 'visible unrelated Java process is not stopped'

    $absentMarker = @{
        schema = 1
        releaseChannel = 'beta'
        runtimeDirectoryName = 'Hearthstone Script Beta'
        runtimeRoot = $runtimeRoot
        processId = 106
        processStartTimeUtc = $markerStart
        executablePath = $javaPath
        deploymentId = 'hs-script_v4.16.700.jar|stale-fixture'
    } | ConvertTo-Json
    [System.IO.File]::WriteAllText((Join-Path $runtimeRoot 'hs-script.pid.json'), $absentMarker)
    $continuationProbe = Join-Path $tempRoot 'stale-marker-continuation-probe.ps1'
    @'
param([string]$ProjectRoot, [string]$RuntimeRoot)
. (Join-Path $ProjectRoot 'hs-script-app/src/main/resources/bat/deployment-contract.ps1')
. (Join-Path $ProjectRoot 'beta-deployment-process-utils.ps1')
$result = @(Stop-ManagedHsScriptProcesses -RuntimeRoot $RuntimeRoot -GetProcessRecords { @() } -StopProcess { param($ProcessId) throw "unexpected stop $ProcessId" })
Write-Output ("CALLER_RESUMED stoppedCount={0}" -f $result.Count)
'@ | Set-Content -LiteralPath $continuationProbe -Encoding UTF8
    $probeOutput = @(& (Join-Path $PSHOME 'pwsh.exe') -NoProfile -File $continuationProbe $root $runtimeRoot 2>&1)
    $probeExitCode = $LASTEXITCODE
    Assert-Equal 0 $probeExitCode 'absent marker PID returns normally from the helper'
    if (-not (($probeOutput -join "`n") -match 'CALLER_RESUMED stoppedCount=0')) {
        throw "Caller did not resume after stale marker handling: $($probeOutput -join ' | ')"
    }
    [System.IO.File]::WriteAllText((Join-Path $runtimeRoot 'hs-script.pid.json'), $marker)

    $staleRecord = @([pscustomobject]@{ Name = 'javaw.exe'; ProcessId = 105; CommandLine = '' })
    $staleIdentity = { param($ProcessId) [pscustomobject]@{ ProcessId = $ProcessId; ProcessStartTimeUtc = '2026-10-27T18:00:00.0000000Z'; ExecutablePath = $javaPath } }
    $staleRejected = $false
    try {
        Stop-ManagedHsScriptProcesses -RuntimeRoot $runtimeRoot -GetProcessRecords { $staleRecord } -GetIdentity $staleIdentity -StopProcess { param($ProcessId) $stopped.Add([int]$ProcessId) } | Out-Null
    } catch { $staleRejected = $_.Exception.Message -match 'cannot be verified' }
    Assert-Equal $true $staleRejected 'reused PID without Beta command line is refused'
    Assert-Equal 4 $stopped.Count 'foreign RC is never stopped; only the four verified Beta marker/JAR PIDs are selected'

    $nonBetaRejected = $false
    try { Stop-ManagedHsScriptProcesses -RuntimeRoot $otherRoot -GetProcessRecords { @() } | Out-Null }
    catch { $nonBetaRejected = $_.Exception.Message -match 'non-Beta runtime root' }
    Assert-Equal $true $nonBetaRejected 'helper refuses RC/Stable roots'
} finally {
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}

$originalFailure = ''
try {
    & {
        $runtimeGate = $null
        trap {
            $originalError = $_
            if ($null -ne $runtimeGate) {
                try { Exit-HsScriptRuntimeGate $runtimeGate } catch { }
                $runtimeGate = $null
            }
            throw $originalError.Exception
        }
        throw 'Beta release original failure sentinel'
    }
} catch { $originalFailure = $_.Exception.Message }
Assert-Equal 'Beta release original failure sentinel' $originalFailure 'release trap preserves underlying error'

$deployScript = [System.IO.File]::ReadAllText((Join-Path $root 'build-and-deploy.ps1'))
foreach ($required in @(
    '. $betaDeploymentProcessUtilsSource',
    'Assert-HsScriptBetaGameProcessAbsent | Out-Null',
    '$stoppedRuntimeProcesses = @(Stop-ManagedHsScriptProcesses $runtimeRoot)',
    'Start-HsScriptBetaRuntimeAfterDeploy',
    "BETA_APP_RESTART_VERIFIED pid=",
    '$originalError = $_',
    'throw $originalError.Exception'
)) {
    if (-not $deployScript.Contains($required)) { throw "Beta deployment process-stop hook is missing: $required" }
}
if (-not $deployScript.Contains('$betaDeploymentProcessUtilsSource = Join-Path $projectRoot ''beta-deployment-process-utils.ps1''')) {
    throw 'Beta process-stop helper must be loaded from the Beta source root'
}

Write-Output 'BetaDeploymentProcessStop.Tests: PASS (Beta-only ownership, exact foreign RC title exclusion, unknown-title refusal, game-preserving restart guard, canonical launch, exact deployment/PID verification, exact marker fallback, PID/name/manifest refusal, missing/corrupt marker, unrelated JVM, original error propagation)'
