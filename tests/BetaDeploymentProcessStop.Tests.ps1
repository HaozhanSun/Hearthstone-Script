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
    $markerStart = '2026-10-27T19:00:00.0000000Z'
    $marker = @{
        schema = 1
        releaseChannel = 'beta'
        runtimeDirectoryName = 'Hearthstone Script Beta'
        runtimeRoot = $runtimeRoot
        processId = 105
        processStartTimeUtc = $markerStart
        executablePath = $javaPath
        deploymentId = 'hs-script_v4.16.700.jar|fixture'
    } | ConvertTo-Json
    [System.IO.File]::WriteAllText((Join-Path $runtimeRoot 'hs-script.pid.json'), $marker)
    $markerOnlyRecord = @([pscustomobject]@{ Name = 'javaw.exe'; ProcessId = 105; CommandLine = '' })
    $matchingIdentity = { param($ProcessId) [pscustomobject]@{ ProcessId = $ProcessId; ProcessStartTimeUtc = $markerStart; ExecutablePath = $javaPath } }
    $markerStopped = @(Stop-ManagedHsScriptProcesses -RuntimeRoot $runtimeRoot -GetProcessRecords { $markerOnlyRecord } -GetIdentity $matchingIdentity -StopProcess { param($ProcessId) $stopped.Add([int]$ProcessId) })
    Assert-Equal 1 $markerStopped.Count 'blank CommandLine can be stopped only through exact verified Beta marker identity'
    Assert-Equal 105 $markerStopped[0] 'verified marker PID selected'

    $staleRecord = @([pscustomobject]@{ Name = 'javaw.exe'; ProcessId = 105; CommandLine = '' })
    $staleIdentity = { param($ProcessId) [pscustomobject]@{ ProcessId = $ProcessId; ProcessStartTimeUtc = '2026-10-27T18:00:00.0000000Z'; ExecutablePath = $javaPath } }
    $staleRejected = $false
    try {
        Stop-ManagedHsScriptProcesses -RuntimeRoot $runtimeRoot -GetProcessRecords { $staleRecord } -GetIdentity $staleIdentity -StopProcess { param($ProcessId) $stopped.Add([int]$ProcessId) } | Out-Null
    } catch { $staleRejected = $_.Exception.Message -match 'cannot be verified' }
    Assert-Equal $true $staleRejected 'reused PID without Beta command line is refused'
    Assert-Equal 2 $stopped.Count 'stale PID is never stopped'

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
    '$stoppedRuntimeProcesses = @(Stop-ManagedHsScriptProcesses $runtimeRoot)',
    '$originalError = $_',
    'throw $originalError.Exception'
)) {
    if (-not $deployScript.Contains($required)) { throw "Beta deployment process-stop hook is missing: $required" }
}
if (-not $deployScript.Contains('$betaDeploymentProcessUtilsSource = Join-Path $projectRoot ''beta-deployment-process-utils.ps1''')) {
    throw 'Beta process-stop helper must be loaded from the Beta source root'
}

Write-Output 'BetaDeploymentProcessStop.Tests: PASS (Beta-only ownership, no-op, marker identity, stale PID refusal, original error propagation)'
