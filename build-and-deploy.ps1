[CmdletBinding()]
param(
    [string]$RuntimeRoot = "",
    [string]$Channel = "",
    [string]$ShortcutName = "",
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
[System.Reflection.Assembly]::LoadWithPartialName('System.IO.Compression.FileSystem') | Out-Null

$projectRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $MyInvocation.MyCommand.Path)).TrimEnd('\')
$sourceBranchOutput = @(& git -C $projectRoot branch --show-current 2>&1)
if ($LASTEXITCODE -ne 0) {
    $gitError = ($sourceBranchOutput -join [Environment]::NewLine).Trim()
    throw "Unable to determine the source Git branch before release preparation. Ensure Git is available and this project is inside a Git worktree. $gitError"
}
$sourceBranch = ($sourceBranchOutput -join [Environment]::NewLine).Trim()
if ([string]::IsNullOrWhiteSpace($sourceBranch)) {
    throw 'Cannot build or deploy from a detached Git worktree. Check out the intended named release branch, then rerun build-and-deploy.ps1.'
}
$channelConfigPath = Join-Path $projectRoot 'release-channel.json'
if (-not (Test-Path -LiteralPath $channelConfigPath -PathType Leaf)) { throw "Release channel configuration missing: $channelConfigPath" }
$channelConfig = Get-Content -LiteralPath $channelConfigPath -Raw | ConvertFrom-Json
$configuredChannel = ([string]$channelConfig.channel).ToLowerInvariant()
if ([string]::IsNullOrWhiteSpace($Channel)) {
    $Channel = $configuredChannel
} else {
    $Channel = $Channel.Trim().ToLowerInvariant()
    if ($Channel -eq 'releasecandidate') { $Channel = 'release-candidate' }
}
if ($Channel -notin @('stable', 'beta', 'release-candidate')) { throw "Unsupported release channel: $Channel" }
if ($Channel -ne $configuredChannel) { throw "Requested channel $Channel does not match release-channel.json channel $configuredChannel" }
$stableRoot = "C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script"
if ([string]::IsNullOrWhiteSpace($RuntimeRoot)) {
    $parent = Split-Path -Parent $stableRoot
    $RuntimeRoot = Join-Path $parent ([string]$channelConfig.runtimeDirectoryName)
}
if ([string]::IsNullOrWhiteSpace($ShortcutName)) { $ShortcutName = [string]$channelConfig.shortcutName }
$iconFileName = [string]$channelConfig.iconFileName
if ([string]::IsNullOrWhiteSpace($iconFileName)) { throw 'release-channel.json iconFileName is missing' }
$runtimeRoot = [System.IO.Path]::GetFullPath($RuntimeRoot).TrimEnd('\')
$runtimeExists = Test-Path -LiteralPath $runtimeRoot -PathType Container
$pomPath = Join-Path $projectRoot 'pom.xml'
$mavenWrapper = Join-Path $projectRoot 'mvnw.cmd'
$targetRoot = Join-Path $projectRoot 'hs-script-app\target'
$strategyTarget = Join-Path $projectRoot 'hs-script-base-strategy-plugin\target\hs-script-base-strategy-plugin.jar'
$cardPluginTarget = Join-Path $projectRoot 'hs-script-base-card-plugin\target\hs-script-base-card-plugin.jar'
$cardSdkTarget = Join-Path $projectRoot 'hs-script-card-sdk\target\hs-script-card-sdk-1.3.0.jar'
$debugRunnerSource = Join-Path $projectRoot 'hs-script-app\src\main\resources\bat\run-debug.ps1'
$debugRunnerCmdSource = Join-Path $projectRoot 'hs-script-app\src\main\resources\bat\run-debug.cmd'
$debugRunnerHardCapSource = Join-Path $projectRoot 'hs-script-app\src\main\resources\bat\debug-run-hardcap.psm1'
$debugRunnerHardCapWatchdogSource = Join-Path $projectRoot 'hs-script-app\src\main\resources\bat\debug-run-hardcap-watchdog.ps1'
$debugRunnerHardCapLauncherSource = Join-Path $projectRoot 'hs-script-app\src\main\resources\bat\debug-run-hardcap-launcher.ps1'
$launcherVbsSource = Join-Path $projectRoot 'hs-script-app\src\main\resources\bat\launch-as-admin.vbs'
$launcherPsSource = Join-Path $projectRoot 'hs-script-app\src\main\resources\bat\launch-newest-as-admin.ps1'
$launcherGuardianPolicySource = Join-Path $projectRoot 'hs-script-app\src\main\resources\bat\launcher-guardian-policy.ps1'
$betaTempPolicySource = Join-Path $projectRoot 'hs-script-app\src\main\resources\bat\beta-temp-policy.ps1'
$diagnosticRetentionSource = Join-Path $projectRoot 'hs-script-app\src\main\resources\bat\diagnostic-retention.ps1'
$deploymentContractSource = Join-Path $projectRoot 'hs-script-app\src\main\resources\bat\deployment-contract.ps1'
$paddleXConfigUtilsSource = Join-Path $projectRoot 'paddlex-config-utils.ps1'
$betaDeploymentProcessUtilsSource = Join-Path $projectRoot 'beta-deployment-process-utils.ps1'
$manifestPath = Join-Path $runtimeRoot 'deployment-manifest.json'
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
. (Join-Path $projectRoot 'build-version-utils.ps1')

foreach ($requiredSource in @($deploymentContractSource, $launcherGuardianPolicySource, $betaTempPolicySource, $diagnosticRetentionSource, $paddleXConfigUtilsSource, $betaDeploymentProcessUtilsSource, $debugRunnerHardCapWatchdogSource, $debugRunnerHardCapLauncherSource)) {
    if (-not (Test-Path -LiteralPath $requiredSource -PathType Leaf)) {
        throw "Required deployment source is missing: $requiredSource"
    }
}
. $deploymentContractSource
. $paddleXConfigUtilsSource
. $betaDeploymentProcessUtilsSource
$runtimeGate = $null
trap {
    $originalError = $_
    if ($null -ne $runtimeGate) {
        try { Exit-HsScriptRuntimeGate $runtimeGate } catch { }
        $runtimeGate = $null
    }
    throw $originalError.Exception
}
$runtimeGate = Enter-HsScriptRuntimeGate $runtimeRoot
if (-not $runtimeExists -and $Channel -ne 'stable') {
    New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
}

function Get-JarManifest([string]$JarPath) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry = $zip.GetEntry('META-INF/MANIFEST.MF')
        if ($null -eq $entry) { throw "JAR manifest missing: $JarPath" }
        $reader = [System.IO.StreamReader]::new($entry.Open())
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $zip.Dispose() }
}

function Get-JarEntrySha256([string]$JarPath, [string]$EntryName) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry = $zip.GetEntry($EntryName)
        if ($null -eq $entry) { throw "JAR entry missing: $EntryName in $JarPath" }
        $sha = [System.Security.Cryptography.SHA256]::Create()
        $stream = $entry.Open()
        try { return (([System.BitConverter]::ToString($sha.ComputeHash($stream)) -replace '-', '').ToLowerInvariant()) }
        finally { $stream.Dispose(); $sha.Dispose() }
    } finally { $zip.Dispose() }
}

function Test-UsableCardDatabase([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    if ((Get-Item -LiteralPath $Path).Length -le 0) { return $false }
    $python = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($null -eq $python) { return $false }
    $probe = @'
import sqlite3,sys
connection=sqlite3.connect(sys.argv[1])
row=connection.execute("select 1 from sqlite_master where type='table' and name='cards' limit 1").fetchone()
connection.close()
sys.exit(0 if row else 1)
'@
    # Capture stderr as data instead of allowing PowerShell's native-command
    # error promotion to abort the release before the exit code is checked.
    # A short retry also covers the brief AV/file-sharing window immediately
    # after extracting the assembled card database.
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $probeOutput = @(& $python.Source -c $probe $Path 2>&1)
        } catch {
            $probeOutput = @($_.Exception.Message)
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        if ($LASTEXITCODE -eq 0) { return $true }
        if ($attempt -lt 3) { Start-Sleep -Milliseconds 250 }
    }
    Write-Output "CARD_DB_PROBE_FAILED=$Path"
    if ($probeOutput.Count -gt 0) { Write-Output ($probeOutput -join [Environment]::NewLine) }
    return $false
}

function Get-PomProperty([string]$Text, [string]$PropertyName) {
    $pattern = "(?s)<$([regex]::Escape($PropertyName))>\s*([^<]+?)\s*</$([regex]::Escape($PropertyName))>"
    $match = [regex]::Match($Text, $pattern)
    if (-not $match.Success) { throw "POM property was not found: $PropertyName" }
    return $match.Groups[1].Value.Trim()
}

function Assert-UsableCardDatabase([string]$Path, [string]$Context) {
    if (-not (Test-UsableCardDatabase $Path)) {
        throw "$Context does not contain a usable SQLite cards table: $Path"
    }
}

if (-not (Test-Path -LiteralPath $pomPath -PathType Leaf)) { throw "pom.xml missing: $pomPath" }
if (-not (Test-Path -LiteralPath $mavenWrapper -PathType Leaf)) { throw "Maven wrapper missing: $mavenWrapper" }
if (-not (Test-Path -LiteralPath $runtimeRoot -PathType Container)) { throw "Runtime root missing: $runtimeRoot" }
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'hs-script-app\assembly.xml') -PathType Leaf)) { throw 'Full reactor assembly descriptor is missing' }
foreach ($runner in @($debugRunnerSource, $debugRunnerCmdSource, $debugRunnerHardCapSource, $debugRunnerHardCapWatchdogSource, $debugRunnerHardCapLauncherSource)) {
    if (-not (Test-Path -LiteralPath $runner -PathType Leaf)) { throw "Debug runner source missing: $runner" }
}

$pomText = [System.IO.File]::ReadAllText($pomPath)
$currentVersion = Get-PomVersion $pomText
$strategyPluginVersion = Get-PomProperty $pomText 'hs-script-base-strategy-plugin-version'
$deployedVersion = $null
if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
    $deployedJarName = [string](Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json).appJar
    $deployedVersion = Get-DeployedVersionFromJarName $deployedJarName
}

    $now = Get-Date
    $zoneName = if ([System.TimeZoneInfo]::Local.IsDaylightSavingTime($now)) { 'PDT' } else { 'PST' }
    $releaseMetadata = Get-ReleaseBuildMetadata $currentVersion $deployedVersion $now $zoneName
    $nextVersion = $releaseMetadata.Version
    if ($Channel -eq 'beta') {
        $collisionSafeVersion = Get-BetaCollisionSafeArtifactVersion $nextVersion $now $targetRoot
        if ($collisionSafeVersion -cne $nextVersion) {
            Write-Output "BETA_ARTIFACT_COLLISION_BUMP=$nextVersion->$collisionSafeVersion"
            $nextVersion = $collisionSafeVersion
        }
    }
    $buildTimestampPacific = $releaseMetadata.BuildTimestampPacific

if (-not [string]::IsNullOrWhiteSpace($deployedVersion)) {
    if ($nextVersion -eq $currentVersion) {
        Write-Output "BUILD_VERSION_ALREADY_NEWER=$currentVersion deployed=$deployedVersion"
    } else {
        foreach ($versionFile in Get-ChildItem -LiteralPath $projectRoot -Filter 'pom.xml' -File -Recurse) {
            $content = [System.IO.File]::ReadAllText($versionFile.FullName)
            $updated = $content.Replace("<version>$currentVersion</version>", "<version>$nextVersion</version>")
            if ($updated -ne $content) { [System.IO.File]::WriteAllText($versionFile.FullName, $updated, $utf8NoBom) }
        }
    }
}

$rootPomTextBeforeTimestamp = [System.IO.File]::ReadAllText($pomPath)
$rootPomWithTimestamp = Set-PomBuildTimestampPacific $rootPomTextBeforeTimestamp $buildTimestampPacific
if ($rootPomWithTimestamp -ne $rootPomTextBeforeTimestamp) {
    [System.IO.File]::WriteAllText($pomPath, $rootPomWithTimestamp, $utf8NoBom)
}
$currentVersion = $nextVersion
$pomText = [System.IO.File]::ReadAllText($pomPath)
$strategyPluginVersion = Get-PomProperty $pomText 'hs-script-base-strategy-plugin-version'
Write-Output "BUILD_TIMESTAMP_PACIFIC=$buildTimestampPacific"

$mavenBaseArgs = @('-f', $pomPath, '-pl', 'hs-script-app', '-am', '-Pjvm', '-Djava.version=24', '-Dproject.build.outputTimestamp=0', "-Dbuild-channel=$Channel")
if (-not $SkipTests) {
    $testArgs = $mavenBaseArgs + @('-DforkCount=0', '-Dtest=AbstractLogListenerTest,CardTimingPolicyTest,CardIdentityCoverageTest,ParsedCardActionFactoryTest,MonteCarloTreeNodeDeferredActionTest,MctsReplayTraceTest,MctsRoundScreenshotTest,SurrenderPolicyTest,GameUtilSurrenderGuardTest,ScreenStateRecoveryTest,ScreenStateRoiSelectorTest,ScreenRecoveryFocusRetryPolicyTest,UnknownStateScreenshotTest,TurnEndActionGuardTest,PirateDemonHunterMctsExperimentModelTest,PirateWarriorMctsGoldenScenarioTest,PirateWarriorMctsModelTest,PirateWarriorOfflineReplayTest,ElementalMageMctsStrategyTest,DebugRunLeaseTest,DebugRunHardCapSupervisorTest,DebugRunUiContractTest,BuildChannelUiContractTest,ActionDispatchGateTest,MulliganRankDispatchBarrierTest,RankEligibilityPolicyTest,CurrentRankDetectorCancellationTest,NeverSurrenderPolicyTest,MulliganRankPreflightTest,StuckScreenUnblockCallsiteIntegrationTest,TerminalResultDismissalFixtureTest,CardBackRewardOverlayRecoveryTest,EventQuestOverlayRecoveryPolicyTest,SeasonRankAwardRecoveryDispatchTest,MatchmakingGuardPolicyTest,MatchmakingSearchRecoveryTest,ScheduleOverrideLogGateTest,StartupRunWindowTest,WorkTimeJitterTest,WorkTimeRuleSetTest,WorkTimeRuleTest,GlobalHotkeyListenerTest,UiLogFormatterTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test')
    Write-Output 'TARGETED_TESTS=enabled'
    & $mavenWrapper @testArgs
    if ($LASTEXITCODE -ne 0) { throw "Targeted regression tests failed with exit code $LASTEXITCODE" }
}

$packageArgs = $mavenBaseArgs + @('-DskipTests', 'package')
Write-Output "BUILD_VERSION=$currentVersion"
& $mavenWrapper @packageArgs
if ($LASTEXITCODE -ne 0) { throw "Maven reactor package failed with exit code $LASTEXITCODE" }

$builtJar = Join-Path $targetRoot "hs-script_$currentVersion.jar"
$builtZip = Join-Path $targetRoot "hs-script_$currentVersion.zip"
foreach ($artifact in @($builtJar, $builtZip, $strategyTarget, $cardPluginTarget, $cardSdkTarget)) {
    if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) { throw "Expected build artifact missing: $artifact" }
}

$buildInfoArchive = [System.IO.Compression.ZipFile]::OpenRead($builtJar)
try {
    $buildInfoEntry = $buildInfoArchive.GetEntry('build.info')
    if ($null -eq $buildInfoEntry) { throw "build.info missing from built JAR: $builtJar" }
    $buildInfoReader = [System.IO.StreamReader]::new($buildInfoEntry.Open())
    try { $embeddedBuildTimestampPacific = Get-BuildInfoTimestampPacific $buildInfoReader.ReadToEnd() }
    finally { $buildInfoReader.Dispose() }
} finally {
    $buildInfoArchive.Dispose()
}
if ($embeddedBuildTimestampPacific -cne $buildTimestampPacific) {
    throw "Built JAR timestamp mismatch: expected '$buildTimestampPacific' but found '$embeddedBuildTimestampPacific'"
}
Write-Output "ARTIFACT_TIMESTAMP_VERIFIED=$embeddedBuildTimestampPacific"

if ($Channel -eq 'beta') {
    # Fail closed while Hearthstone is present. A live client may own a match,
    # a queue, or a result transition; deployment must never force-close it.
    Assert-HsScriptBetaGameProcessAbsent | Out-Null
    Write-Output 'BETA_RESTART_PREFLIGHT=GAME_ABSENT'
}

$stoppedRuntimeProcesses = @(Stop-ManagedHsScriptProcesses $runtimeRoot)
if ($stoppedRuntimeProcesses.Count -gt 0) {
    Write-Output "STOPPED_RUNTIME_PROCESSES=$($stoppedRuntimeProcesses -join ',')"
    foreach ($stoppedPid in $stoppedRuntimeProcesses) {
        try { Wait-Process -Id ([int]$stoppedPid) -Timeout 10 -ErrorAction SilentlyContinue } catch { }
    }
}
$remainingRuntimeProcesses = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -in @('java.exe', 'javaw.exe') -and
    ([string]$_.CommandLine).IndexOf(([System.IO.Path]::GetFullPath($runtimeRoot).TrimEnd('\')), [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -and
    ([string]$_.CommandLine) -match 'hs-script_.*\.jar'
})
if ($remainingRuntimeProcesses.Count -gt 0) {
    throw "Refusing to deploy while the channel runtime is still running: $($remainingRuntimeProcesses.ProcessId -join ',')"
}

$staging = Join-Path ([System.IO.Path]::GetTempPath()) ("hs-script-deploy-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $staging -Force | Out-Null
# A newly-created channel runtime has no extracted plugin/lib directories yet.
# Create both destinations before copying assembled artifacts so stable and
# beta installs follow the same deploy contract on a clean runtime.
$runtimeLib = Join-Path $runtimeRoot 'lib'
$runtimePlugin = Join-Path $runtimeRoot 'plugin'
New-Item -ItemType Directory -Path $runtimeLib, $runtimePlugin -Force | Out-Null
try {
    [System.IO.Compression.ZipFile]::ExtractToDirectory($builtZip, $staging)
    $stagedCardDb = Join-Path $staging 'hs_cards.db'
    if (-not (Test-Path -LiteralPath $stagedCardDb -PathType Leaf)) {
        throw "Assembled deployment is missing hs_cards.db: $builtZip"
    }
    if ((Get-Item -LiteralPath $stagedCardDb).Length -le 0) {
        throw "Assembled deployment contains an empty hs_cards.db: $builtZip"
    }
    Assert-UsableCardDatabase $stagedCardDb 'Assembled deployment'
    $runtimeCardDb = Join-Path $runtimeRoot 'hs_cards.db'
    if (Test-Path -LiteralPath $runtimeCardDb -PathType Leaf) {
        $backupName = "hs_cards.db.backup-$((Get-Date).ToString('yyyyMMdd-HHmmssfff'))-$([guid]::NewGuid().ToString('N')).bak"
        $backupPath = Join-Path $runtimeRoot $backupName
        Copy-Item -LiteralPath $runtimeCardDb -Destination $backupPath -Force
        Write-Output "CARD_DB_BACKUP=$backupPath"
    }
    Copy-Item -LiteralPath $stagedCardDb -Destination $runtimeCardDb -Force
    Assert-UsableCardDatabase $runtimeCardDb 'Deployed runtime'
    Write-Output "CARD_DB_VERIFIED=$runtimeCardDb"
    foreach ($item in @('resources', 'lib', 'logback.xml', 'create-aot.bat', 'debug-hs-script.bat', 'hs-script.bat', 'unlock.bat', 'card-update-util.exe', 'force-stop.exe', 'hs-script.exe', 'inject-util.exe', 'install-drive.exe', 'update.exe', (Split-Path -Leaf $builtJar))) {
        $source = Join-Path $staging $item
        if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination $runtimeRoot -Recurse -Force }
    }
    $stagedPlugins = Join-Path $staging 'plugin'
    if (Test-Path -LiteralPath $stagedPlugins -PathType Container) {
        foreach ($pluginJar in Get-ChildItem -LiteralPath $stagedPlugins -Filter '*.jar' -File) {
            Copy-Item -LiteralPath $pluginJar.FullName -Destination (Join-Path $runtimeRoot "plugin\$($pluginJar.Name)") -Force
        }
    }
    # Keep only the current versioned strategy plugin in lib. The launcher
    # assembles its classpath from this directory; leaving an older numbered
    # copy beside the current one lets classpath order select stale strategy
    # code even when the manifest and plugin/ copy are correct.
    if (Test-Path -LiteralPath $runtimeLib -PathType Container) {
        Get-ChildItem -LiteralPath $runtimeLib -Filter 'hs-script-base-strategy-plugin-*.jar' -File |
            Where-Object { $_.Name -ne "hs-script-base-strategy-plugin-$strategyPluginVersion.jar" } |
            ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
    }
    Copy-Item -LiteralPath $strategyTarget -Destination (Join-Path $runtimeLib "hs-script-base-strategy-plugin-$strategyPluginVersion.jar") -Force
    Copy-Item -LiteralPath $cardPluginTarget -Destination (Join-Path $runtimeLib 'hs-script-base-card-plugin-1.1.4.jar') -Force
    Copy-Item -LiteralPath $strategyTarget -Destination (Join-Path $runtimePlugin 'hs-script-base-strategy-plugin.jar') -Force
    Copy-Item -LiteralPath $cardPluginTarget -Destination (Join-Path $runtimePlugin 'hs-script-base-card-plugin.jar') -Force
    Copy-Item -LiteralPath $cardSdkTarget -Destination (Join-Path $runtimeLib 'hs-script-card-sdk-1.3.0.jar') -Force
    Copy-Item -LiteralPath $builtZip -Destination (Join-Path $runtimeRoot (Split-Path -Leaf $builtZip)) -Force
    # The E2E runner is a release-side script rather than an assembly entry.
    # Copy it explicitly so a new runner property cannot be stranded in the
    # source tree while the runtime keeps an older script.
    Copy-Item -LiteralPath $debugRunnerSource -Destination (Join-Path $runtimeRoot 'run-debug.ps1') -Force
    Copy-Item -LiteralPath $debugRunnerCmdSource -Destination (Join-Path $runtimeRoot 'run-debug.cmd') -Force
    Copy-Item -LiteralPath $debugRunnerHardCapSource -Destination (Join-Path $runtimeRoot 'debug-run-hardcap.psm1') -Force
    Copy-Item -LiteralPath $debugRunnerHardCapWatchdogSource -Destination (Join-Path $runtimeRoot 'debug-run-hardcap-watchdog.ps1') -Force
    Copy-Item -LiteralPath $debugRunnerHardCapLauncherSource -Destination (Join-Path $runtimeRoot 'debug-run-hardcap-launcher.ps1') -Force
    Copy-Item -LiteralPath $launcherVbsSource -Destination (Join-Path $runtimeRoot 'launch-as-admin.vbs') -Force
    Copy-Item -LiteralPath $launcherPsSource -Destination (Join-Path $runtimeRoot 'launch-newest-as-admin.ps1') -Force
    Copy-Item -LiteralPath $launcherGuardianPolicySource -Destination (Join-Path $runtimeRoot 'launcher-guardian-policy.ps1') -Force
    Copy-Item -LiteralPath $betaTempPolicySource -Destination (Join-Path $runtimeRoot 'beta-temp-policy.ps1') -Force
    Copy-Item -LiteralPath $diagnosticRetentionSource -Destination (Join-Path $runtimeRoot 'diagnostic-retention.ps1') -Force
    Copy-Item -LiteralPath $deploymentContractSource -Destination (Join-Path $runtimeRoot 'deployment-contract.ps1') -Force
    Copy-Item -LiteralPath $channelConfigPath -Destination (Join-Path $runtimeRoot 'release-channel.json') -Force
} finally {
    if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
}

Assert-UsableCardDatabase $runtimeCardDb 'Post-deployment runtime'

$paddleXSyncArgs = @{ Root = $runtimeRoot }
if ($Channel -eq 'beta') {
    # Preserve Beta's historical GAME_PATH/PLATFORM_PATH values while keeping
    # module/python/cache repair and module-path validation shared with RC.
    $paddleXSyncArgs.PreserveGamePlatformPaths = $true
}
Sync-PaddleXOcrConfig @paddleXSyncArgs
$verifiedPaddleXModulePath = Assert-PaddleXOcrModulePath $runtimeRoot
Write-Output "PADDLEX_MODULE_PATH_VERIFIED=$verifiedPaddleXModulePath"

$deployedJar = Join-Path $runtimeRoot (Split-Path -Leaf $builtJar)
$iconPath = Join-Path $runtimeRoot $iconFileName
if ($Channel -in @('beta', 'release-candidate')) {
    $iconGenerator = Join-Path $projectRoot 'tools\create-channel-icon.ps1'
    if (-not (Test-Path -LiteralPath $iconGenerator -PathType Leaf)) { throw "Channel icon generator missing: $iconGenerator" }
    $badgeText = if ($Channel -eq 'release-candidate') { 'RC' } else { 'B' }
    # The generator is a PowerShell script, so LASTEXITCODE may still contain
    # the exit code of an earlier native build command. Use the invocation
    # status for this child script and reserve LASTEXITCODE for native tools.
    $global:LASTEXITCODE = 0
    & $iconGenerator -SourceExe (Join-Path $runtimeRoot 'hs-script.exe') -OutputPath $iconPath -BadgeText $badgeText
    if (-not $?) { throw "Channel icon generation failed: $iconGenerator" }
}
if (-not (Test-Path -LiteralPath $iconPath -PathType Leaf)) { throw "Application icon missing: $iconPath" }
$appHash = (Get-FileHash -LiteralPath $deployedJar -Algorithm SHA256).Hash.ToLowerInvariant()
$manifestText = Get-JarManifest $deployedJar
$unwrappedManifest = $manifestText -replace "\r?\n ", ''
$classPathLine = ($unwrappedManifest -split "\r?\n" | Where-Object { $_ -like 'Class-Path:*' } | Select-Object -First 1)
if ([string]::IsNullOrWhiteSpace($classPathLine)) { throw "Application JAR has no Class-Path: $deployedJar" }
$runtimeLibHashes = [ordered]@{}
foreach ($entryName in ($classPathLine.Substring('Class-Path:'.Length).Trim() -split '\s+')) {
    $relative = $entryName.Replace('/', '\')
    $path = Join-Path $runtimeRoot $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Runtime Class-Path entry missing: $relative" }
    $runtimeLibHashes[$relative] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
}
$strategyLibRelative = "lib\hs-script-base-strategy-plugin-$strategyPluginVersion.jar"
$strategyLib = Join-Path $runtimeRoot $strategyLibRelative
$cardSdkLib = Join-Path $runtimeRoot 'lib\hs-script-card-sdk-1.3.0.jar'
$strategyPlugin = Join-Path $runtimeRoot 'plugin\hs-script-base-strategy-plugin.jar'
if (-not (Test-Path -LiteralPath $strategyLib -PathType Leaf) -or -not (Test-Path -LiteralPath $cardSdkLib -PathType Leaf) -or -not (Test-Path -LiteralPath $strategyPlugin -PathType Leaf)) { throw 'Required strategy/card runtime artifacts are missing' }
$strategyHash = (Get-FileHash -LiteralPath $strategyLib -Algorithm SHA256).Hash.ToLowerInvariant()
$cardSdkHash = (Get-FileHash -LiteralPath $cardSdkLib -Algorithm SHA256).Hash.ToLowerInvariant()
$gitErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $sourceCommitOutput = @(& git -C $projectRoot rev-parse HEAD 2>&1)
    $sourceCommitExit = $LASTEXITCODE
    $sourceBranchOutput = @(& git -C $projectRoot branch --show-current 2>&1)
    $sourceBranchExit = $LASTEXITCODE
} finally { $ErrorActionPreference = $gitErrorAction }
$sourceCommit = ($sourceCommitOutput | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
$sourceBranch = ($sourceBranchOutput | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
if ($sourceCommitExit -ne 0 -or $sourceCommit -notmatch '^[0-9a-fA-F]{40}$') {
    throw "Unable to record the exact source commit for the deployment manifest: $sourceCommit"
}
if ($sourceBranchExit -ne 0 -or [string]::IsNullOrWhiteSpace($sourceBranch)) {
    throw "Unable to record the source branch for the deployment manifest"
}
$promotionSourceBranch = [string]$channelConfig.promotionSourceBranch
$promotionSourceCommit = [string]$channelConfig.promotionSourceCommit
if ([string]::IsNullOrWhiteSpace($promotionSourceBranch)) { $promotionSourceBranch = $null }
if ([string]::IsNullOrWhiteSpace($promotionSourceCommit)) { $promotionSourceCommit = $null }
$manifest = [ordered]@{
    schema = 1
    releaseChannel = $Channel
    runtimeDirectoryName = [string]$channelConfig.runtimeDirectoryName
    shortcutName = $ShortcutName
    iconFile = $iconFileName
    deploymentId = "$(Split-Path -Leaf $deployedJar)|$($appHash.Substring(0, 16))"
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    buildTimestampPacific = $buildTimestampPacific
    sourceBranch = $sourceBranch.Trim()
    sourceCommit = $sourceCommit.Trim()
    promotionSourceBranch = $promotionSourceBranch
    promotionSourceCommit = $promotionSourceCommit
    appJar = Split-Path -Leaf $deployedJar
    appJarSha256 = $appHash
    strategyPluginLib = $strategyLibRelative
    strategyPlugin = 'plugin\hs-script-base-strategy-plugin.jar'
    strategyPluginSha256 = $strategyHash
    cardSdk = 'lib\hs-script-card-sdk-1.3.0.jar'
    cardSdkSha256 = $cardSdkHash
    mctsArgClassSha256 = Get-JarEntrySha256 $cardSdkLib 'club/xiaojiawei/hsscriptcardsdk/bean/MCTSArg.class'
    runtimeLibs = $runtimeLibHashes
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

& (Join-Path $projectRoot 'sync-shortcuts.ps1') -RuntimeRoot $runtimeRoot -ShortcutName $ShortcutName `
    -IconPath $iconPath -Description ([string]$channelConfig.displayName) -Channel $Channel
if (-not $?) { throw "Shortcut synchronization failed" }

# Keep channel diagnostics within retention age even while the app is closed.
# The checked-in entrypoint binds each task to this deployment's manifest and
# exact runtime root; this release path installs and verifies, but does not run,
# the scheduled cleanup.
$retentionEntrypoint = Join-Path $runtimeRoot 'diagnostic-retention.ps1'
& $retentionEntrypoint -Channel $Channel -RuntimeRoot $runtimeRoot -InstallTask -VerifyTask
if (-not $?) { throw 'Diagnostic retention task installation or verification failed' }

Exit-HsScriptRuntimeGate $runtimeGate
$runtimeGate = $null

if ($Channel -eq 'beta') {
    $betaRestart = Start-HsScriptBetaRuntimeAfterDeploy `
        -RuntimeRoot $runtimeRoot `
        -DeploymentId ([string]$manifest.deploymentId)
    Write-Output "BETA_APP_RESTART_VERIFIED pid=$($betaRestart.ProcessId) deploymentId=$($betaRestart.DeploymentId) " +
        "processStartTimeUtc=$($betaRestart.ProcessStartTimeUtc) executable=$($betaRestart.ExecutablePath) " +
        "marker=$($betaRestart.MarkerPath)"
}

Write-Output "DEPLOYED_JAR=$deployedJar"
Write-Output "DEPLOYMENT_ID=$($manifest.deploymentId)"
Write-Output "DEPLOYMENT_MANIFEST=$manifestPath"
Write-Output "APP_SHA256=$appHash"
Write-Output 'BUILD_AND_DEPLOY_COMPLETE'
