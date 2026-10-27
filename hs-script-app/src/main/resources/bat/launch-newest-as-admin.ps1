[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaPath = 'C:\Program Files\Java\jdk-25.0.4\bin\javaw.exe'
$logDirectory = Join-Path $scriptDirectory 'log'
. (Join-Path $scriptDirectory 'deployment-contract.ps1')
. (Join-Path $scriptDirectory 'launcher-guardian-policy.ps1')
. (Join-Path $scriptDirectory 'beta-temp-policy.ps1')
$deployment = Resolve-Deployment $scriptDirectory
if (-not (Test-Path -LiteralPath $javaPath)) { throw "javaw.exe was not found: $javaPath" }

$runtimeHasher = [System.Security.Cryptography.SHA256]::Create()
$runtimeHashBytes = $runtimeHasher.ComputeHash(
    [System.Text.Encoding]::UTF8.GetBytes([System.IO.Path]::GetFullPath($scriptDirectory).ToLowerInvariant())
)
$runtimeKey = ([System.BitConverter]::ToString($runtimeHashBytes)).Replace('-', '').Substring(0, 24)
$runtimeHasher.Dispose()
if (([string]$deployment.Manifest.releaseChannel).ToLowerInvariant() -eq 'beta') {
    $startupVolumes = @(Get-CimInstance -ClassName Win32_LogicalDisk -Filter 'DriveType=3' -ErrorAction Stop |
        Select-Object DeviceID, FreeSpace, DriveType)
    $logDirectory = Get-HsScriptBetaLogDirectory `
        -Channel ([string]$deployment.Manifest.releaseChannel) `
        -RuntimeKey $runtimeKey `
        -RuntimeDirectory $scriptDirectory `
        -FixedVolumes $startupVolumes
}
$selectionLog = Join-Path $logDirectory 'launcher-selection.log'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
if (([string]$deployment.Manifest.releaseChannel).ToLowerInvariant() -eq 'beta') {
    Add-Content -LiteralPath $selectionLog -Value ("{0} betaLogDirectory={1} minFreeBytes=5368709120" -f (Get-Date -Format o), $logDirectory)
}
$guardianMutexState = Enter-HsScriptGuardianMutex -RuntimeKey $runtimeKey
if (-not $guardianMutexState.Acquired) {
    Add-Content -LiteralPath $selectionLog -Value ("{0} guardianAlreadyRunning action=NO_DUPLICATE_LAUNCH" -f (Get-Date -Format o))
    $guardianMutexState.Mutex.Dispose()
    exit 0
}
$guardianMutex = $guardianMutexState.Mutex

function Start-HsScriptChild([object]$ResolvedDeployment, [bool]$GuardianRestart) {
    $runtimeGate = Enter-HsScriptRuntimeGate $scriptDirectory
    try {
        if (-not $GuardianRestart) {
            $idle = Assert-HsScriptRuntimeIdle $scriptDirectory
        }
        $javaTempArgument = ''
        $betaArtifactArgument = ''
        $betaLoggingArgument = ''
        if (([string]$ResolvedDeployment.Manifest.releaseChannel).ToLowerInvariant() -eq 'beta') {
            $fixedVolumes = @(Get-CimInstance -ClassName Win32_LogicalDisk -Filter 'DriveType=3' -ErrorAction Stop |
                Select-Object DeviceID, FreeSpace, DriveType)
            $javaTempDirectory = Get-HsScriptBetaJavaTempDirectory `
                -Channel ([string]$ResolvedDeployment.Manifest.releaseChannel) `
                -RuntimeKey $runtimeKey `
                -RuntimeDirectory $scriptDirectory `
                -FixedVolumes $fixedVolumes
            New-Item -ItemType Directory -Force -Path $javaTempDirectory | Out-Null
            $javaTempArgument = (Get-HsScriptJavaTempJvmArgument `
                -Channel ([string]$ResolvedDeployment.Manifest.releaseChannel) `
                -Directory $javaTempDirectory) + ' '
            Add-Content -LiteralPath $selectionLog -Value ("{0} betaJavaTempDirectory={1}" -f (Get-Date -Format o), $javaTempDirectory)

            $artifactRoot = Get-HsScriptBetaArtifactRoot `
                -Channel ([string]$ResolvedDeployment.Manifest.releaseChannel) `
                -RuntimeKey $runtimeKey `
                -RuntimeDirectory $scriptDirectory `
                -FixedVolumes $fixedVolumes
            if (-not [string]::IsNullOrWhiteSpace([string]$artifactRoot)) {
                New-Item -ItemType Directory -Force -Path $artifactRoot | Out-Null
                $artifactRoot = [System.IO.Path]::GetFullPath($artifactRoot)
                $betaArtifactArgument = '-Dhs.script.artifact-root="' + $artifactRoot + '" ' +
                    '-Dhs.script.mcts-replay.dir="' + (Join-Path $artifactRoot 'mcts-replay') + '" ' +
                    '-Dhs.script.unknown-state.dir="' + (Join-Path $artifactRoot 'unknown-states') + '" ' +
                    '-Dhs.script.result-screenshot.dir="' + (Join-Path $artifactRoot 'game-results') + '" ' +
                    '-Dhs.script.mulligan-screenshot.dir="' + (Join-Path $artifactRoot 'mulligan') + '" '
                Add-Content -LiteralPath $selectionLog -Value ("{0} betaArtifactRoot={1} minFreeBytes=5368709120" -f (Get-Date -Format o), $artifactRoot)
            } else {
                $betaArtifactArgument = '-Dhs.script.artifact-storage.disabled=true '
                Add-Content -LiteralPath $selectionLog -Value ("{0} betaArtifactRoot=DISABLED reason=no-non-runtime-volume-meets-capacity" -f (Get-Date -Format o))
            }
            $betaAppLogDirectory = Join-Path $logDirectory 'app'
            New-Item -ItemType Directory -Force -Path $betaAppLogDirectory | Out-Null
            $guardianStatePath = Join-Path $logDirectory 'guardian-state.json'
            $betaLoggingArgument = '-Dhs.script.log.dir="' + [System.IO.Path]::GetFullPath($betaAppLogDirectory) + '" ' +
                '-Dhs.script.guardian-state.path="' + [System.IO.Path]::GetFullPath($guardianStatePath) + '" '
        }
        $arguments = $javaTempArgument + $betaArtifactArgument + $betaLoggingArgument + '-Dhs.script.launch.source=shortcut -Djna.library.path="' +
            $scriptDirectory + '" -Dhs.script.deployment.id="' + $ResolvedDeployment.DeploymentId +
            '" -Dhs.script.deployment.manifest="' + $ResolvedDeployment.ManifestPath + '" -jar "' +
            $ResolvedDeployment.AppJar + '"'
        $child = Start-Process -FilePath $javaPath -ArgumentList $arguments -WorkingDirectory $scriptDirectory -PassThru
        $identity = Get-HsScriptProcessIdentity ([int]$child.Id)
        if ($null -eq $identity -or [string]::IsNullOrWhiteSpace([string]$identity.ProcessStartTimeUtc) -or
            [string]::IsNullOrWhiteSpace([string]$identity.ExecutablePath)) {
            throw "Launched JVM identity could not be verified; refusing another launch: pid=$($child.Id)"
        }
        $marker = [ordered]@{
            schema = 1
            releaseChannel = ([string]$ResolvedDeployment.Manifest.releaseChannel).ToLowerInvariant()
            runtimeDirectoryName = [string]$ResolvedDeployment.Manifest.runtimeDirectoryName
            runtimeRoot = [System.IO.Path]::GetFullPath($scriptDirectory).TrimEnd('\')
            processId = [int]$identity.ProcessId
            processStartTimeUtc = [string]$identity.ProcessStartTimeUtc
            executablePath = [System.IO.Path]::GetFullPath([string]$identity.ExecutablePath)
            deploymentId = $ResolvedDeployment.DeploymentId
            appJar = [string]$ResolvedDeployment.Manifest.appJar
            processState = 'RUNNING'
            recordedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        }
        $markerPath = Join-Path $scriptDirectory 'hs-script.pid.json'
        $temporaryMarker = "$markerPath.$([guid]::NewGuid().ToString('N')).tmp"
        $marker | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $temporaryMarker -Encoding UTF8
        Move-Item -LiteralPath $temporaryMarker -Destination $markerPath -Force
        $guardianStatePath = Join-Path $logDirectory 'guardian-state.json'
        $stateMatchesChild = $false
        if (Test-Path -LiteralPath $guardianStatePath -PathType Leaf) {
            try {
                $priorGuardianState = Get-Content -LiteralPath $guardianStatePath -Raw | ConvertFrom-Json -ErrorAction Stop
                $stateMatchesChild = [int64]$priorGuardianState.pid -eq [int64]$identity.ProcessId -and
                    (Test-HsScriptStartTimeMatch -RecordedUtc ([string]$priorGuardianState.processStartTimeUtc) `
                        -ObservedUtc ([string]$identity.ProcessStartTimeUtc))
            } catch { $stateMatchesChild = $false }
        }
        if (-not $stateMatchesChild) {
            $initialGuardianState = [ordered]@{
                schema = 2
                pid = [int]$identity.ProcessId
                processStartTimeUtc = [string]$identity.ProcessStartTimeUtc
                deploymentId = $ResolvedDeployment.DeploymentId
                releaseChannel = ([string]$ResolvedDeployment.Manifest.releaseChannel).ToLowerInvariant()
                intent = 'RUNNING'
                working = $true
                paused = $false
                pauseOrigin = 'NONE'
                gameAlive = (@(Get-Process -Name 'Hearthstone' -ErrorAction SilentlyContinue).Count -gt 0)
                activeGame = $false
                activeMatchmaking = $false
                healthSnapshotKnown = $false
                heartbeatAtUtc = (Get-Date).ToUniversalTime().ToString('o')
                recordedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
                reason = 'launcher-start'
            }
            $stateTemporary = "$guardianStatePath.$([guid]::NewGuid().ToString('N')).tmp"
            $initialGuardianState | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $stateTemporary -Encoding UTF8
            Move-Item -LiteralPath $stateTemporary -Destination $guardianStatePath -Force
        }
        Add-Content -LiteralPath $selectionLog -Value ("{0} startedPid={1} deploymentId={2} guardianRestart={3} marker={4}" -f (Get-Date -Format o), $identity.ProcessId, $ResolvedDeployment.DeploymentId, $GuardianRestart, $markerPath)
        return [pscustomobject]@{ Process = $child; Identity = $identity; Deployment = $ResolvedDeployment }
    } finally {
        Exit-HsScriptRuntimeGate $runtimeGate
    }
}

try {
    $run = Start-HsScriptChild $deployment $false
    $guardianNotifier = $null
    try {
        Add-Type -AssemblyName System.Windows.Forms -ErrorAction Stop
        Add-Type -AssemblyName System.Drawing -ErrorAction Stop
        $guardianNotifier = [System.Windows.Forms.NotifyIcon]::new()
        $guardianNotifier.Icon = [System.Drawing.SystemIcons]::Warning
        $guardianNotifier.Text = 'Hearthstone RC guardian'
        $guardianNotifier.Visible = $true
    } catch {
        Add-Content -LiteralPath $selectionLog -Value ("{0} guardianNotificationUnavailable={1}" -f `
            (Get-Date -Format o), $_.Exception.Message)
    }
    $guardianResult = Invoke-HsScriptGuardianLoop -InitialRun $run `
        -StartRun {
            param($GuardianRestart)
            $currentDeployment = Resolve-Deployment $scriptDirectory
            Start-HsScriptChild $currentDeployment ([bool]$GuardianRestart)
        } `
        -ReadState {
            param($CurrentRun)
            $currentStatePath = Join-Path $logDirectory 'guardian-state.json'
            if (-not (Test-Path -LiteralPath $currentStatePath -PathType Leaf)) { return $null }
            try { Get-Content -LiteralPath $currentStatePath -Raw | ConvertFrom-Json -ErrorAction Stop }
            catch { return $null }
        } `
        -IsHearthstoneAlive { @(Get-Process -Name 'Hearthstone' -ErrorAction SilentlyContinue).Count -gt 0 } `
        -Notify {
            param($title, $message)
            if ($null -ne $guardianNotifier) {
                try { $guardianNotifier.ShowBalloonTip(10000, $title, $message, [System.Windows.Forms.ToolTipIcon]::Warning) }
                catch { Add-Content -LiteralPath $selectionLog -Value ("{0} guardianNotificationFailed={1}" -f (Get-Date -Format o), $_.Exception.Message) }
            }
        } `
        -WriteLog {
            param($line)
            Add-Content -LiteralPath $selectionLog -Value ("{0} {1}" -f (Get-Date -Format o), $line)
        }
    $finalRun = $guardianResult.FinalRun
    $statePath = Join-Path $logDirectory 'guardian-state.json'
    $appLogPath = Join-Path (Join-Path $logDirectory 'app') 'hs_script.log'
    $reconciled = Set-HsScriptExitedChildState -MarkerPath (Join-Path $scriptDirectory 'hs-script.pid.json') `
        -GuardianStatePath $statePath -Run $finalRun -Action ([string]$guardianResult.Action) `
        -ExitCode ([int]$finalRun.Process.ExitCode) -AppLogPath $appLogPath
    Add-Content -LiteralPath $selectionLog -Value ("{0} childExitReconciled pid={1} action={2} markerUpdated={3}" -f `
        (Get-Date -Format o), $finalRun.Identity.ProcessId, $guardianResult.Action, $reconciled)
} finally {
    if ($null -ne $guardianNotifier) {
        $guardianNotifier.Visible = $false
        $guardianNotifier.Dispose()
    }
    $guardianMutex.ReleaseMutex()
    $guardianMutex.Dispose()
}
