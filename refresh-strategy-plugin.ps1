[CmdletBinding()]
param(
    [ValidateSet('Stable', 'Beta', 'ReleaseCandidate', 'release-candidate')]
    [string]$Channel = 'Beta',
    [string]$Reason = 'manual-strategy-refresh',
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $MyInvocation.MyCommand.Path)).TrimEnd('\')
$channelConfig = Get-Content (Join-Path $projectRoot 'release-channel.json') -Raw | ConvertFrom-Json
$configuredChannel = ([string]$channelConfig.channel).ToLowerInvariant()
$requestedChannel = $Channel.ToLowerInvariant()
if ($requestedChannel -eq 'releasecandidate') { $requestedChannel = 'release-candidate' }
if ($requestedChannel -ne $configuredChannel) {
    throw "Requested channel $requestedChannel does not match release-channel.json channel $configuredChannel"
}

$runtimeParent = 'C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs'
$runtimeRoot = Join-Path $runtimeParent ([string]$channelConfig.runtimeDirectoryName)
$pluginRoot = Join-Path $runtimeRoot 'plugin'
$rootPom = Join-Path $projectRoot 'pom.xml'
$strategyPom = Join-Path $projectRoot 'hs-script-base-strategy-plugin\pom.xml'
$mavenWrapper = Join-Path $projectRoot 'mvnw.cmd'
$pluginArtifact = Join-Path $projectRoot 'hs-script-base-strategy-plugin\target\hs-script-base-strategy-plugin.jar'
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Get-StrategyVersion([string]$text) {
    $match = [regex]::Match($text, '(?s)(<hs-script-base-strategy-plugin-version>)([^<]+)(</hs-script-base-strategy-plugin-version>)')
    if (-not $match.Success) { throw 'Strategy plugin version property was not found' }
    return $match.Groups[2].Value.Trim()
}

if (-not (Test-Path -LiteralPath $runtimeRoot -PathType Container)) { throw "Runtime root missing: $runtimeRoot" }
if (-not (Test-Path -LiteralPath $pluginRoot -PathType Container)) { New-Item -ItemType Directory -Path $pluginRoot -Force | Out-Null }

$pomText = [System.IO.File]::ReadAllText($rootPom)
$currentVersionText = Get-StrategyVersion $pomText
$currentVersion = [version]$currentVersionText
$nextVersion = "{0}.{1}.{2}" -f $currentVersion.Major, $currentVersion.Minor, ($currentVersion.Build + 1)
$updatedPom = $pomText.Replace(
    "<hs-script-base-strategy-plugin-version>$currentVersionText</hs-script-base-strategy-plugin-version>",
    "<hs-script-base-strategy-plugin-version>$nextVersion</hs-script-base-strategy-plugin-version>"
)
if ($updatedPom -eq $pomText) { throw 'Strategy plugin version update did not change pom.xml' }
[System.IO.File]::WriteAllText($rootPom, $updatedPom, $utf8NoBom)
$strategyPomText = [System.IO.File]::ReadAllText($strategyPom)
$strategyPomUpdated = [regex]::Replace(
    $strategyPomText,
    '(?s)(<artifactId>hs-script-base-strategy-plugin</artifactId>\s*<version>)[^<]+(</version>)',
    "`${1}$nextVersion`${2}",
    1
)
if ($strategyPomUpdated -eq $strategyPomText) { throw 'Strategy plugin module version update did not change hs-script-base-strategy-plugin/pom.xml' }
[System.IO.File]::WriteAllText($strategyPom, $strategyPomUpdated, $utf8NoBom)

$mavenArgs = @('-f', $rootPom, '-pl', 'hs-script-base-strategy-plugin', '-am', '-Pjvm', '-Djava.version=24', "-Dbuild-channel=$Channel")
if (-not $SkipTests) {
    & $mavenWrapper @($mavenArgs + @('-DforkCount=0', '-Dtest=PirateWarriorMctsModelTest,PirateWarriorOfflineReplayTest,PirateDemonHunterMctsExperimentModelTest,ElementalMageMctsStrategyTest,StrategyRefreshCoordinatorTest,StrategyRefreshCommandWatcherTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test'))
    if ($LASTEXITCODE -ne 0) { throw "Strategy plugin tests failed with exit code $LASTEXITCODE" }
}
& $mavenWrapper @($mavenArgs + @('-DskipTests', 'package'))
if ($LASTEXITCODE -ne 0) { throw "Strategy plugin build failed with exit code $LASTEXITCODE" }
if (-not (Test-Path -LiteralPath $pluginArtifact -PathType Leaf)) { throw "Strategy plugin artifact missing: $pluginArtifact" }

$hash = (Get-FileHash -LiteralPath $pluginArtifact -Algorithm SHA256).Hash.ToLowerInvariant()
$runtimeArtifactName = "hs-script-base-strategy-plugin-$nextVersion.jar"
$runtimeArtifact = Join-Path $pluginRoot $runtimeArtifactName
$stagingArtifact = "$runtimeArtifact.$([guid]::NewGuid().ToString('N')).tmp"
Copy-Item -LiteralPath $pluginArtifact -Destination $stagingArtifact -Force
Move-Item -LiteralPath $stagingArtifact -Destination $runtimeArtifact -Force

$manifest = [ordered]@{
    schema = 1
    channel = $requestedChannel
    strategyPlugin = $runtimeArtifactName
    strategyPluginVersion = $nextVersion
    sha256 = $hash
    sourceArtifact = $pluginArtifact
    refreshedAt = (Get-Date).ToUniversalTime().ToString('o')
}
[System.IO.File]::WriteAllText(
    (Join-Path $runtimeRoot 'strategy-deployment-manifest.json'),
    ($manifest | ConvertTo-Json),
    $utf8NoBom
)

$requestPath = Join-Path $runtimeRoot 'strategy-refresh.request'
$requestTemp = "$requestPath.$([guid]::NewGuid().ToString('N')).tmp"
[System.IO.File]::WriteAllText($requestTemp, "strategy-plugin-$nextVersion`n$Reason`n$hash`n", $utf8NoBom)
Move-Item -LiteralPath $requestTemp -Destination $requestPath -Force

Write-Output "STRATEGY_PLUGIN_VERSION=$nextVersion"
Write-Output "STRATEGY_PLUGIN=$runtimeArtifact"
Write-Output "STRATEGY_PLUGIN_SHA256=$hash"
Write-Output "REFRESH_REQUEST=$requestPath"
Write-Output 'STRATEGY_REFRESH_QUEUED=true'
