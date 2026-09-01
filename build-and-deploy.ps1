[CmdletBinding()]
param(
    [string]$RuntimeRoot = "C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script",
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
[System.Reflection.Assembly]::LoadWithPartialName('System.IO.Compression.FileSystem') | Out-Null

$projectRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $MyInvocation.MyCommand.Path)).TrimEnd('\')
$runtimeRoot = [System.IO.Path]::GetFullPath($RuntimeRoot).TrimEnd('\')
$pomPath = Join-Path $projectRoot 'pom.xml'
$mavenWrapper = Join-Path $projectRoot 'mvnw.cmd'
$targetRoot = Join-Path $projectRoot 'hs-script-app\target'
$strategyTarget = Join-Path $projectRoot 'hs-script-base-strategy-plugin\target\hs-script-base-strategy-plugin.jar'
$cardPluginTarget = Join-Path $projectRoot 'hs-script-base-card-plugin\target\hs-script-base-card-plugin.jar'
$cardSdkTarget = Join-Path $projectRoot 'hs-script-card-sdk\target\hs-script-card-sdk-1.3.0.jar'
$manifestPath = Join-Path $runtimeRoot 'deployment-manifest.json'
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Get-PomVersion([string]$Text) {
    $match = [regex]::Match($Text, '(?s)(<artifactId>hs-script</artifactId>\s*<version>)([^<]+)(</version>)')
    if (-not $match.Success) { throw 'Root hs-script version was not found in pom.xml' }
    return $match.Groups[2].Value
}

function Get-BaseVersion([string]$Version) {
    $match = [regex]::Match($Version, '^v(\d+)\.(\d+)\.(\d+)')
    if (-not $match.Success) { throw "Unsupported application version: $Version" }
    return [version]::new([int]$match.Groups[1].Value, [int]$match.Groups[2].Value, [int]$match.Groups[3].Value)
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

if (-not (Test-Path -LiteralPath $pomPath -PathType Leaf)) { throw "pom.xml missing: $pomPath" }
if (-not (Test-Path -LiteralPath $mavenWrapper -PathType Leaf)) { throw "Maven wrapper missing: $mavenWrapper" }
if (-not (Test-Path -LiteralPath $runtimeRoot -PathType Container)) { throw "Runtime root missing: $runtimeRoot" }
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'hs-script-app\assembly.xml') -PathType Leaf)) { throw 'Full reactor assembly descriptor is missing' }

$pomText = [System.IO.File]::ReadAllText($pomPath)
$currentVersion = Get-PomVersion $pomText
$deployedVersion = $null
if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
    try {
        $deployedJarName = [string](Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json).appJar
        $deployedVersion = [regex]::Match($deployedJarName, '^hs-script_(v[^.]+\.[^.]+\.[^-]+[^.]*)\.jar$').Groups[1].Value
    } catch { $deployedVersion = $null }
}

if (-not [string]::IsNullOrWhiteSpace($deployedVersion) -and (Get-BaseVersion $currentVersion) -le (Get-BaseVersion $deployedVersion)) {
    $base = Get-BaseVersion $deployedVersion
    $now = Get-Date
    $zoneName = if ([System.TimeZoneInfo]::Local.IsDaylightSavingTime($now)) { 'PDT' } else { 'PST' }
    $nextVersion = "v$($base.Major).$($base.Minor).$($base.Build + 1)-local-$($now.ToString('yyyyMMdd-HHmmss'))$zoneName"
    $buildTimestampPacific = "$($now.ToString('yyyy-MM-dd HH:mm:ss')) $zoneName"
    foreach ($versionFile in Get-ChildItem -LiteralPath $projectRoot -Filter 'pom.xml' -File -Recurse) {
        $content = [System.IO.File]::ReadAllText($versionFile.FullName)
        $updated = $content.Replace("<version>$currentVersion</version>", "<version>$nextVersion</version>")
        if ($updated -ne $content) { [System.IO.File]::WriteAllText($versionFile.FullName, $updated, $utf8NoBom) }
    }
    $rootPomTimestampPattern = '(?s)(<local-build-timestamp-pacific>)[^<]*(</local-build-timestamp-pacific>)'
    $rootPomTextBeforeTimestamp = [System.IO.File]::ReadAllText($pomPath)
    $rootPomWithTimestamp = [regex]::new($rootPomTimestampPattern).Replace(
        $rootPomTextBeforeTimestamp,
        "`${1}$buildTimestampPacific`${2}",
        1
    )
    if ($rootPomWithTimestamp -eq $rootPomTextBeforeTimestamp) {
        throw 'Root POM local-build-timestamp-pacific property was not found while bumping the release version'
    }
    [System.IO.File]::WriteAllText($pomPath, $rootPomWithTimestamp, $utf8NoBom)
    $currentVersion = $nextVersion
    Write-Output "BUILD_TIMESTAMP_PACIFIC=$buildTimestampPacific"
}

$mavenBaseArgs = @('-f', $pomPath, '-pl', 'hs-script-app', '-am', '-Pjvm', '-Djava.version=24', '-Dproject.build.outputTimestamp=0')
if (-not $SkipTests) {
    $testArgs = $mavenBaseArgs + @('-DforkCount=0', '-Dtest=CardTimingPolicyTest,MctsReplayTraceTest,MctsRoundScreenshotTest,SurrenderPolicyTest,PaddleXRankDetectorTest,GameUtilSurrenderGuardTest,ScreenStateRecoveryTest,UnknownStateScreenshotTest,TurnEndActionGuardTest,PirateDemonHunterMctsExperimentModelTest,StartupRunWindowTest,WorkTimeJitterTest,WorkTimeWindowTest,WorkTimePresetDefaultsTest,WorkTimeRuleSetTest,WorkTimeRuleTest,GlobalHotkeyListenerTest,UiLogFormatterTest,PowerLogListenerTest,GameStarterWindowTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test')
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

$markerPath = Join-Path $runtimeRoot 'hs-script.pid.json'
if (Test-Path -LiteralPath $markerPath -PathType Leaf) {
    try {
        $marker = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json
        $process = Get-Process -Id ([int]$marker.pid) -ErrorAction Stop
        if ($process.ProcessName -in @('java', 'javaw')) {
            Stop-Process -Id $process.Id -Force
            Start-Sleep -Milliseconds 500
        }
    } catch { }
    Remove-Item -LiteralPath $markerPath -Force -ErrorAction SilentlyContinue
}

$staging = Join-Path ([System.IO.Path]::GetTempPath()) ("hs-script-deploy-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $staging -Force | Out-Null
try {
    [System.IO.Compression.ZipFile]::ExtractToDirectory($builtZip, $staging)
    foreach ($item in @('resources', 'lib', 'hs_cards.db', 'logback.xml', 'create-aot.bat', 'debug-hs-script.bat', 'hs-script.bat', 'unlock.bat', 'card-update-util.exe', 'force-stop.exe', 'hs-script.exe', 'inject-util.exe', 'install-drive.exe', 'update.exe', (Split-Path -Leaf $builtJar))) {
        $source = Join-Path $staging $item
        if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination $runtimeRoot -Recurse -Force }
    }
    $stagedPlugins = Join-Path $staging 'plugin'
    if (Test-Path -LiteralPath $stagedPlugins -PathType Container) {
        foreach ($pluginJar in Get-ChildItem -LiteralPath $stagedPlugins -Filter '*.jar' -File) {
            Copy-Item -LiteralPath $pluginJar.FullName -Destination (Join-Path $runtimeRoot "plugin\$($pluginJar.Name)") -Force
        }
    }
    $runtimeLib = Join-Path $runtimeRoot 'lib'
    $runtimePlugin = Join-Path $runtimeRoot 'plugin'
    Copy-Item -LiteralPath $strategyTarget -Destination (Join-Path $runtimeLib 'hs-script-base-strategy-plugin-1.1.6.jar') -Force
    Copy-Item -LiteralPath $cardPluginTarget -Destination (Join-Path $runtimeLib 'hs-script-base-card-plugin-1.1.4.jar') -Force
    Copy-Item -LiteralPath $strategyTarget -Destination (Join-Path $runtimePlugin 'hs-script-base-strategy-plugin.jar') -Force
    Copy-Item -LiteralPath $cardPluginTarget -Destination (Join-Path $runtimePlugin 'hs-script-base-card-plugin.jar') -Force
    Copy-Item -LiteralPath $cardSdkTarget -Destination (Join-Path $runtimeLib 'hs-script-card-sdk-1.3.0.jar') -Force
    Copy-Item -LiteralPath $builtZip -Destination (Join-Path $runtimeRoot (Split-Path -Leaf $builtZip)) -Force
} finally {
    if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
}

$deployedJar = Join-Path $runtimeRoot (Split-Path -Leaf $builtJar)
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
$strategyLib = Join-Path $runtimeRoot 'lib\hs-script-base-strategy-plugin-1.1.6.jar'
$cardSdkLib = Join-Path $runtimeRoot 'lib\hs-script-card-sdk-1.3.0.jar'
$strategyPlugin = Join-Path $runtimeRoot 'plugin\hs-script-base-strategy-plugin.jar'
if (-not (Test-Path -LiteralPath $strategyLib -PathType Leaf) -or -not (Test-Path -LiteralPath $cardSdkLib -PathType Leaf) -or -not (Test-Path -LiteralPath $strategyPlugin -PathType Leaf)) { throw 'Required strategy/card runtime artifacts are missing' }
$strategyHash = (Get-FileHash -LiteralPath $strategyLib -Algorithm SHA256).Hash.ToLowerInvariant()
$cardSdkHash = (Get-FileHash -LiteralPath $cardSdkLib -Algorithm SHA256).Hash.ToLowerInvariant()
$manifest = [ordered]@{
    schema = 1
    deploymentId = "$(Split-Path -Leaf $deployedJar)|$($appHash.Substring(0, 16))"
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    appJar = Split-Path -Leaf $deployedJar
    appJarSha256 = $appHash
    strategyPluginLib = 'lib\hs-script-base-strategy-plugin-1.1.6.jar'
    strategyPlugin = 'plugin\hs-script-base-strategy-plugin.jar'
    strategyPluginSha256 = $strategyHash
    cardSdk = 'lib\hs-script-card-sdk-1.3.0.jar'
    cardSdkSha256 = $cardSdkHash
    mctsArgClassSha256 = Get-JarEntrySha256 $cardSdkLib 'club/xiaojiawei/hsscriptcardsdk/bean/MCTSArg.class'
    runtimeLibs = $runtimeLibHashes
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

& (Join-Path $projectRoot 'sync-shortcuts.ps1') -RuntimeRoot $runtimeRoot
if ($LASTEXITCODE -ne 0) { throw "Shortcut synchronization failed with exit code $LASTEXITCODE" }

Write-Output "DEPLOYED_JAR=$deployedJar"
Write-Output "DEPLOYMENT_ID=$($manifest.deploymentId)"
Write-Output "DEPLOYMENT_MANIFEST=$manifestPath"
Write-Output "APP_SHA256=$appHash"
Write-Output 'BUILD_AND_DEPLOY_COMPLETE'
