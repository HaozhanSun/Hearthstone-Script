$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$promotionScript = Join-Path $root 'promote-beta-to-release-candidate.ps1'

function Invoke-TestGit {
    param([Parameter(Mandatory = $true)][string]$Root, [Parameter(Mandatory = $true)][string[]]$Arguments)
    $output = @(& git -C $Root @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "git $($Arguments -join ' ') failed: $($output -join [Environment]::NewLine)" }
    return (($output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine).Trim()
}

function Assert-Throws {
    param([Parameter(Mandatory = $true)][scriptblock]$Action, [Parameter(Mandatory = $true)][string]$MessageFragment)
    try { & $Action; throw 'Expected action to fail' }
    catch {
        if ($_.Exception.Message -eq 'Expected action to fail' -or
            $_.Exception.Message -notlike "*$MessageFragment*") {
            throw "Expected failure containing '$MessageFragment' but got: $($_.Exception.Message)"
        }
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

function New-TestJar {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][string]$Version, [Parameter(Mandatory = $true)][string]$Channel, [Parameter(Mandatory = $true)][string]$Timestamp)
    $archive = [System.IO.Compression.ZipFile]::Open($Path, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $entry = $archive.CreateEntry('build.info')
        $writer = [System.IO.StreamWriter]::new($entry.Open())
        try {
            $writer.Write("version=$Version`nartifactId=hs-script-app`nchannel=$Channel`nbuildTimestampPacific=$Timestamp`n")
        } finally { $writer.Dispose() }
    } finally { $archive.Dispose() }
}

function New-TestRuntimeManifest {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$JarName,
        [Parameter(Mandatory = $true)][string]$Channel,
        [Parameter(Mandatory = $true)][string]$RuntimeName,
        [Parameter(Mandatory = $true)][string]$SourceBranch,
        [Parameter(Mandatory = $true)][string]$SourceCommit,
        [string]$PromotionSourceBranch = '',
        [string]$PromotionSourceCommit = '',
        [string]$Timestamp = '2026-10-23 16:00:00 PDT'
    )
    New-Item -ItemType Directory -Path $Root -Force | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $Root 'hs-script-release-candidate.ico'), 'rc-icon')
    New-TestJar -Path (Join-Path $Root $JarName) -Version ($JarName -replace '^hs-script_(.*)\.jar$', '$1') -Channel $Channel -Timestamp $Timestamp
    [System.IO.File]::WriteAllText((Join-Path $Root 'strategy-lib.jar'), 'strategy-artifact')
    Copy-Item -LiteralPath (Join-Path $Root 'strategy-lib.jar') -Destination (Join-Path $Root 'strategy-plugin.jar')
    [System.IO.File]::WriteAllText((Join-Path $Root 'card-sdk.jar'), 'card-sdk-artifact')
    $jarHash = (Get-FileHash -LiteralPath (Join-Path $Root $JarName) -Algorithm SHA256).Hash.ToLowerInvariant()
    $strategyHash = (Get-FileHash -LiteralPath (Join-Path $Root 'strategy-lib.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
    $cardSdkHash = (Get-FileHash -LiteralPath (Join-Path $Root 'card-sdk.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest = [ordered]@{
        schema = 1
        releaseChannel = $Channel
        runtimeDirectoryName = $RuntimeName
        deploymentId = "fixture-$JarName"
        iconFile = 'hs-script-release-candidate.ico'
        sourceBranch = $SourceBranch
        sourceCommit = $SourceCommit
        promotionSourceBranch = $PromotionSourceBranch
        promotionSourceCommit = $PromotionSourceCommit
        buildTimestampPacific = $Timestamp
        appJar = $JarName
        appJarSha256 = $jarHash
        strategyPluginLib = 'strategy-lib.jar'
        strategyPlugin = 'strategy-plugin.jar'
        strategyPluginSha256 = $strategyHash
        cardSdk = 'card-sdk.jar'
        cardSdkSha256 = $cardSdkHash
    }
    $manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Root 'deployment-manifest.json') -Encoding UTF8
    return $manifest
}

function New-TestRcLaunchersAndShortcuts {
    param([Parameter(Mandatory = $true)][string]$Root, [Parameter(Mandatory = $true)][string]$ShortcutBase)
    [System.IO.File]::WriteAllText((Join-Path $Root 'launch-as-admin.vbs'), 'launch-newest-as-admin.ps1')
    [System.IO.File]::WriteAllText((Join-Path $Root 'launch-newest-as-admin.ps1'), 'Resolve-Deployment $scriptDirectory -jar hs-script.jar -Dhs.script.deployment.id')
    [System.IO.File]::WriteAllText((Join-Path $Root 'deployment-contract.ps1'), 'function Resolve-Deployment {}')
    $shell = New-Object -ComObject WScript.Shell
    try {
        foreach ($directoryName in @('Desktop', 'Start Menu', 'Taskbar')) {
            $directory = Join-Path $ShortcutBase $directoryName
            New-Item -ItemType Directory -Path $directory -Force | Out-Null
            $shortcut = $shell.CreateShortcut((Join-Path $directory 'Hearthstone Script Release Candidate.lnk'))
            $shortcut.TargetPath = Join-Path $env:SystemRoot 'System32\wscript.exe'
            $shortcut.Arguments = '"' + (Join-Path $Root 'launch-as-admin.vbs') + '"'
            $shortcut.WorkingDirectory = $Root
            $shortcut.IconLocation = (Join-Path $Root 'hs-script-release-candidate.ico') + ',0'
            $shortcut.Save()
        }
    } finally { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($shell) }
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('hs-beta-rc-promotion-' + [guid]::NewGuid().ToString('N'))
$project = Join-Path $tempRoot 'beta-source'
$betaRuntime = Join-Path $tempRoot 'Hearthstone Script Beta'
$rcRuntime = Join-Path $tempRoot 'Hearthstone Script Release Candidate'
$pendingRcRuntime = Join-Path (Join-Path $tempRoot 'pending') 'Hearthstone Script Release Candidate'
$rcWorktree = Join-Path $tempRoot 'rc-worktree'
$pendingRcWorktree = Join-Path $tempRoot 'pending-rc-worktree'
$legacyRcRuntime = Join-Path $tempRoot 'legacy\Hearthstone Script Release Candidate'
$legacyRcWorktree = Join-Path $tempRoot 'legacy-rc-worktree'
$legacyShortcutBase = Join-Path $tempRoot 'legacy-shortcuts'
$stableRuntime = Join-Path $tempRoot 'Hearthstone Script'
$shortcutBase = Join-Path $tempRoot 'shortcuts'
$fakeBuild = Join-Path $tempRoot 'fake-build.ps1'
$invocationLog = Join-Path $tempRoot 'build-invocations.log'
$evidencePath = Join-Path $tempRoot 'online-e2e-gate.json'
$screenshotPath = Join-Path $tempRoot 'final-win.png'
New-Item -ItemType Directory -Path $project, $betaRuntime, $stableRuntime -Force | Out-Null
Set-Content -LiteralPath (Join-Path $stableRuntime 'stable-marker.txt') -Value 'stable-preserved' -NoNewline
Set-Content -LiteralPath $screenshotPath -Value 'offline-evidence' -NoNewline

try {
    Invoke-TestGit -Root $project -Arguments @('init', '-b', 'beta/test-promotion') | Out-Null
    Invoke-TestGit -Root $project -Arguments @('config', 'user.email', 'promotion-test@example.invalid') | Out-Null
    Invoke-TestGit -Root $project -Arguments @('config', 'user.name', 'Promotion Test') | Out-Null
    $config = @'
{
  "schema": 1,
  "channel": "beta",
  "branch": "beta/test-promotion",
  "displayName": "Hearthstone Script Beta",
  "runtimeDirectoryName": "Hearthstone Script Beta",
  "shortcutName": "Hearthstone Script Beta.lnk",
  "iconFileName": "hs-script-beta.ico",
  "artifactLabel": "beta"
}
'@
    [System.IO.File]::WriteAllText((Join-Path $project 'release-channel.json'), $config)
    [System.IO.File]::WriteAllText((Join-Path $project 'build-and-deploy.ps1'), @'
param([string]$Channel)
& (Join-Path $PSScriptRoot 'sync-shortcuts.ps1') -RuntimeRoot $runtimeRoot -Channel $Channel
'@)
    [System.IO.File]::WriteAllText((Join-Path $project 'sync-shortcuts.ps1'), @'
[CmdletBinding()]
param([string]$Channel)
$shortcutDirectories = @(
    [Environment]::GetFolderPath("Desktop"),
    (Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"),
    (Join-Path $env:APPDATA "Microsoft\Internet Explorer\Quick Launch\User Pinned\TaskBar")
)
'@)
    [System.IO.File]::WriteAllText((Join-Path $project 'pom.xml'), @'
<project>
  <version>v4.16.669</version>
  <properties>
    <local-build-timestamp-pacific>2026-10-23 15:00:00 PDT</local-build-timestamp-pacific>
  </properties>
</project>
'@)
    New-Item -ItemType Directory -Path (Join-Path $project 'hs-script-app') -Force | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $project 'hs-script-app\pom.xml'), @'
<project>
  <parent>
    <groupId>fixture</groupId>
    <artifactId>hs-script</artifactId>
    <version>v4.16.669</version>
  </parent>
</project>
'@)
    Invoke-TestGit -Root $project -Arguments @('add', '--all') | Out-Null
    Invoke-TestGit -Root $project -Arguments @('commit', '-m', 'beta promotion fixture') | Out-Null
    $betaHead = Invoke-TestGit -Root $project -Arguments @('rev-parse', 'HEAD')

    $jarName = 'hs-script_v4.16.670-local-20261022-225531PDT.jar'
    $betaManifest = New-TestRuntimeManifest -Root $betaRuntime -JarName $jarName -Channel 'beta' -RuntimeName 'Hearthstone Script Beta' -SourceBranch 'beta/test-promotion' -SourceCommit $betaHead
    $betaManifestPath = Join-Path $betaRuntime 'deployment-manifest.json'
    $betaManifestBefore = (Get-FileHash -LiteralPath $betaManifestPath -Algorithm SHA256).Hash
    $stableMarkerBefore = Get-Content -LiteralPath (Join-Path $stableRuntime 'stable-marker.txt') -Raw

    [ordered]@{
        schema = 1
        status = 'PASS'
        releaseChannel = 'beta'
        sourceBranch = 'beta/test-promotion'
        sourceCommit = $betaHead
        artifactName = $jarName
        artifactSha256 = [string]$betaManifest.appJarSha256
        gamesCompleted = 3
        terminalMarkers = 3
        scriptProcessStable = $true
        finalScreenshotPath = $screenshotPath
    } | ConvertTo-Json | Set-Content -LiteralPath $evidencePath -Encoding UTF8

$fakeBuildText = @'
param([string]$Channel)
if ($Channel -cne 'release-candidate') { throw "wrong channel: $Channel" }
$root = $env:PROMOTION_TEST_RC_ROOT
$log = $env:PROMOTION_TEST_INVOCATION
Add-Content -LiteralPath $log -Value (Get-Date).ToString('o')
New-Item -ItemType Directory -Path $root -Force | Out-Null
$timestamp = '2026-10-23 16:00:00 PDT'
$jar = Join-Path $root 'hs-script_v4.16.670-local-20261022-225531PDT.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::Open($jar, [System.IO.Compression.ZipArchiveMode]::Create)
try { $entry = $archive.CreateEntry('build.info'); $writer = [System.IO.StreamWriter]::new($entry.Open()); try { $writer.Write("version=v4.16.670-local-20261022-225531PDT`nchannel=release-candidate`nbuildTimestampPacific=$timestamp`n") } finally { $writer.Dispose() } } finally { $archive.Dispose() }
 [System.IO.File]::WriteAllText((Join-Path $root 'strategy-lib.jar'), 'strategy-artifact')
 Copy-Item -LiteralPath (Join-Path $root 'strategy-lib.jar') -Destination (Join-Path $root 'strategy-plugin.jar')
 [System.IO.File]::WriteAllText((Join-Path $root 'card-sdk.jar'), 'card-sdk-artifact')
 [System.IO.File]::WriteAllText((Join-Path $root 'hs-script-release-candidate.ico'), 'rc-icon')
$hash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
$strategyHash = (Get-FileHash -LiteralPath (Join-Path $root 'strategy-lib.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
$cardSdkHash = (Get-FileHash -LiteralPath (Join-Path $root 'card-sdk.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
$config = Get-Content -LiteralPath (Join-Path (Get-Location) 'release-channel.json') -Raw | ConvertFrom-Json
$sourceCommit = ((& git rev-parse HEAD) | Out-String).Trim()
$sourceBranch = ((& git branch --show-current) | Out-String).Trim()
[ordered]@{
  schema = 1
  releaseChannel = 'release-candidate'
  runtimeDirectoryName = 'Hearthstone Script Release Candidate'
  sourceBranch = $sourceBranch
  sourceCommit = $sourceCommit
  promotionSourceBranch = [string]$config.promotionSourceBranch
  promotionSourceCommit = [string]$config.promotionSourceCommit
  buildTimestampPacific = $timestamp
  iconFile = 'hs-script-release-candidate.ico'
  appJar = 'hs-script_v4.16.670-local-20261022-225531PDT.jar'
  appJarSha256 = $hash
  strategyPluginLib = 'strategy-lib.jar'
  strategyPlugin = 'strategy-plugin.jar'
  strategyPluginSha256 = $strategyHash
  cardSdk = 'card-sdk.jar'
  cardSdkSha256 = $cardSdkHash
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'deployment-manifest.json') -Encoding UTF8
[System.IO.File]::WriteAllText((Join-Path $root 'launch-as-admin.vbs'), 'launch-newest-as-admin.ps1')
[System.IO.File]::WriteAllText((Join-Path $root 'launch-newest-as-admin.ps1'), 'Resolve-Deployment $scriptDirectory -jar hs-script.jar -Dhs.script.deployment.id')
[System.IO.File]::WriteAllText((Join-Path $root 'deployment-contract.ps1'), 'function Resolve-Deployment {}')
$shell = New-Object -ComObject WScript.Shell
try {
    foreach ($directoryName in @('Desktop', 'Start Menu', 'Taskbar')) {
        $directory = Join-Path $env:PROMOTION_TEST_SHORTCUT_ROOT $directoryName
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        $shortcut = $shell.CreateShortcut((Join-Path $directory 'Hearthstone Script Release Candidate.lnk'))
        $shortcut.TargetPath = Join-Path $env:SystemRoot 'System32\wscript.exe'
        $shortcut.Arguments = '"' + (Join-Path $root 'launch-as-admin.vbs') + '"'
        $shortcut.WorkingDirectory = $root
        $shortcut.IconLocation = (Join-Path $root 'hs-script-release-candidate.ico') + ',0'
        $shortcut.Save()
    }
    $duplicate = $shell.CreateShortcut((Join-Path (Join-Path $env:PROMOTION_TEST_SHORTCUT_ROOT 'Taskbar') 'Hearthstone Script Release Candidate (2).lnk'))
    $duplicate.TargetPath = Join-Path $env:SystemRoot 'System32\wscript.exe'
    $duplicate.Arguments = '"' + (Join-Path $root 'launch-as-admin.vbs') + '"'
    $duplicate.WorkingDirectory = $root
    $duplicate.IconLocation = (Join-Path $root 'hs-script-release-candidate.ico') + ',0'
    $duplicate.Save()
} finally { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($shell) }
'@
    [System.IO.File]::WriteAllText($fakeBuild, $fakeBuildText)
    $env:PROMOTION_TEST_RC_ROOT = $rcRuntime
    $env:PROMOTION_TEST_INVOCATION = $invocationLog
$env:PROMOTION_TEST_SHORTCUT_ROOT = $shortcutBase

$taskbarDirectory = Join-Path $shortcutBase 'Taskbar'
New-Item -ItemType Directory -Path $taskbarDirectory -Force | Out-Null
$preservedChannelPins = @{}
$shell = New-Object -ComObject WScript.Shell
try {
    foreach ($channelPin in @(
        @{ Name = 'Hearthstone Script Beta.lnk'; Runtime = (Join-Path $tempRoot 'Hearthstone Script Beta'); Launcher = 'beta-launch.vbs' },
        @{ Name = 'Hearthstone Script.lnk'; Runtime = $stableRuntime; Launcher = 'stable-launch.vbs' }
    )) {
        $launcherPath = Join-Path $channelPin.Runtime $channelPin.Launcher
        $pin = $shell.CreateShortcut((Join-Path $taskbarDirectory $channelPin.Name))
        $pin.TargetPath = Join-Path $env:SystemRoot 'System32\wscript.exe'
        $pin.Arguments = '"' + $launcherPath + '"'
        $pin.WorkingDirectory = $channelPin.Runtime
        $pin.IconLocation = $launcherPath + ',0'
        $pin.Save()
        $pinPath = Join-Path $taskbarDirectory $channelPin.Name
        $preservedChannelPins[$pinPath] = (Get-FileHash -LiteralPath $pinPath -Algorithm SHA256).Hash
    }
} finally { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($shell) }

    $pendingInvocationLog = Join-Path $tempRoot 'pending-build-invocations.log'
    $env:PROMOTION_TEST_RC_ROOT = $pendingRcRuntime
    $env:PROMOTION_TEST_INVOCATION = $pendingInvocationLog
    $pendingOutput = & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $pendingRcRuntime -PromotionWorktree $pendingRcWorktree -ReleaseCandidateBranch 'beta/release-candidate-pending-test' -BuildScript $fakeBuild -ShortcutRoot $shortcutBase
    $pendingText = $pendingOutput -join "`n"
    if ($pendingText -notmatch 'PROMOTION_COMPLETE.*onlineE2e=PENDING stableReady=False') { throw 'Evidence-free RC candidate was not labelled E2E-pending and not Stable-ready' }
    $pendingManifest = Get-Content -LiteralPath (Join-Path $pendingRcRuntime 'deployment-manifest.json') -Raw | ConvertFrom-Json
    if ($pendingManifest.onlineE2eStatus -ne 'PENDING' -or $pendingManifest.stableReady -ne $false -or $pendingManifest.stableReadiness -ne 'NOT_READY_E2E_PENDING') { throw 'Pending E2E state was not recorded safely in the deployed manifest' }
    if ($pendingManifest.onlineE2eBetaArtifactName -ne $jarName -or $pendingManifest.onlineE2eBetaArtifactSha256 -ne $betaManifest.appJarSha256 -or $pendingManifest.onlineE2eEvidenceSha256 -ne '') { throw 'Pending manifest did not bind status to the exact Beta artifact or unexpectedly claim evidence' }
    $pendingConfig = Get-Content -LiteralPath (Join-Path $pendingRcWorktree 'release-channel.json') -Raw | ConvertFrom-Json
    if ($pendingConfig.onlineE2eStatus -ne 'PENDING' -or $pendingConfig.stableReady -ne $false) { throw 'Pending E2E state was not recorded in RC branch metadata' }
    $duplicateTaskbarPath = Join-Path $taskbarDirectory 'Hearthstone Script Release Candidate (2).lnk'
    if (Test-Path -LiteralPath $duplicateTaskbarPath) { throw 'RC duplicate Taskbar pin remained active after deployment' }
    $pinBackupRoots = @(Get-ChildItem -LiteralPath (Join-Path $pendingRcRuntime 'shortcut-pin-backups') -Directory -ErrorAction SilentlyContinue)
    if ($pinBackupRoots.Count -ne 1) { throw 'Expected one recoverable RC Taskbar duplicate-pin backup after pending candidate deployment' }
    $pinBackupManifest = Get-Content -LiteralPath (Join-Path $pinBackupRoots[0].FullName 'backup-manifest.json') -Raw | ConvertFrom-Json
    if ($pinBackupManifest.entries.Count -ne 1 -or $pinBackupManifest.entries[0].originalPath -cne $duplicateTaskbarPath) { throw 'Duplicate-pin backup manifest does not identify the exact RC Taskbar link' }
    $backedUpPin = Join-Path $pinBackupRoots[0].FullName $pinBackupManifest.entries[0].backupRelativePath
    if (-not (Test-Path -LiteralPath $backedUpPin -PathType Leaf) -or (Get-FileHash -LiteralPath $backedUpPin -Algorithm SHA256).Hash.ToLowerInvariant() -cne $pinBackupManifest.entries[0].sha256) { throw 'RC duplicate Taskbar pin backup is missing or its hash changed' }
    foreach ($preservedPin in $preservedChannelPins.Keys) {
        if ((Get-FileHash -LiteralPath $preservedPin -Algorithm SHA256).Hash -cne $preservedChannelPins[$preservedPin]) { throw "RC pin cleanup modified a Beta/Stable Taskbar shortcut: $preservedPin" }
    }

    $badEvidencePath = Join-Path $tempRoot 'wrong-artifact-evidence.json'
    $badEvidence = Get-Content -LiteralPath $evidencePath -Raw | ConvertFrom-Json
    $badEvidence.artifactSha256 = ('0' * 64)
    $badEvidence | ConvertTo-Json | Set-Content -LiteralPath $badEvidencePath -Encoding UTF8
    Assert-Throws -Action {
        & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot (Join-Path $tempRoot 'bad-evidence-rc') -PromotionWorktree (Join-Path $tempRoot 'bad-evidence-worktree') -BuildScript $fakeBuild -VerificationEvidencePath $badEvidencePath -ShortcutRoot $shortcutBase | Out-Null
    } -MessageFragment 'Online E2E evidence artifact hash mismatch'

    $env:PROMOTION_TEST_RC_ROOT = $rcRuntime
    $env:PROMOTION_TEST_INVOCATION = $invocationLog

    & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $rcRuntime -PromotionWorktree $rcWorktree -BuildScript $fakeBuild -VerificationEvidencePath $evidencePath -ShortcutRoot $shortcutBase | Out-Null
    if (-not (Test-Path -LiteralPath (Join-Path $rcWorktree 'release-channel.json') -PathType Leaf)) { throw 'RC worktree metadata was not created' }
    $rcConfig = Get-Content -LiteralPath (Join-Path $rcWorktree 'release-channel.json') -Raw | ConvertFrom-Json
    if ($rcConfig.channel -ne 'release-candidate' -or $rcConfig.branch -ne 'beta/release-candidate-v4.16.670-local-20261022-225531PDT') { throw 'RC metadata was not isolated or branch-bound' }
    $promotedShortcutSync = Get-Content -LiteralPath (Join-Path $rcWorktree 'sync-shortcuts.ps1') -Raw
    if ($promotedShortcutSync -notmatch '\[string\]\$Channel' -or $promotedShortcutSync -notmatch '\$desktopFallback = \[Environment\]::GetFolderPath') { throw 'RC snapshot did not retain the channel contract and receive the isolated Desktop fallback' }
    if ($promotedShortcutSync -match '\[Environment\]::GetFolderPath\(["'']Desktop["'']\),') { throw 'RC snapshot retained the unsafe empty Desktop path expression' }
    if ($rcConfig.promotionSourceCommit -ne $betaHead -or $rcConfig.promotionSourceBranch -ne 'beta/test-promotion') { throw 'RC promotion source metadata is incomplete' }
    if ($rcConfig.onlineE2eStatus -ne 'PASS' -or $rcConfig.onlineE2eBetaArtifactName -ne $jarName -or $rcConfig.onlineE2eBetaArtifactSha256 -ne $betaManifest.appJarSha256 -or $rcConfig.stableReady -ne $false -or $rcConfig.stableReadiness -ne 'REQUIRES_INDEPENDENT_MAIN_GATE_REVIEW') { throw 'RC source metadata did not bind successful evidence while preserving independent Stable gate review' }
    $rcManifest = Get-Content -LiteralPath (Join-Path $rcRuntime 'deployment-manifest.json') -Raw | ConvertFrom-Json
    $expectedEvidenceHash = (Get-FileHash -LiteralPath $evidencePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($rcManifest.onlineE2eStatus -ne 'PASS' -or $rcManifest.onlineE2eEvidenceSha256 -ne $expectedEvidenceHash -or $rcManifest.stableReady -ne $false -or $rcManifest.stableReadiness -ne 'REQUIRES_INDEPENDENT_MAIN_GATE_REVIEW') { throw 'Deployed manifest did not record artifact-bound E2E evidence and Stable gate status' }
    if ((Get-FileHash -LiteralPath $betaManifestPath -Algorithm SHA256).Hash -cne $betaManifestBefore) { throw 'Promotion modified the Beta manifest' }
    if ((Get-Content -LiteralPath (Join-Path $stableRuntime 'stable-marker.txt') -Raw) -cne $stableMarkerBefore) { throw 'Promotion modified the Stable runtime' }
    if ((Get-Content -LiteralPath $invocationLog).Count -ne 1) { throw 'Initial promotion did not invoke the build path exactly once' }

    $preflightOutput = & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $rcRuntime -PromotionWorktree $rcWorktree -BuildScript $fakeBuild -VerificationEvidencePath $evidencePath -ShortcutRoot $shortcutBase -PreflightOnly
    if (($preflightOutput -join "`n") -notmatch 'PREFLIGHT_OK') { throw 'Preflight did not report success' }
    if ((Get-Content -LiteralPath $invocationLog).Count -ne 1) { throw 'Preflight invoked the build path' }

    $canonicalTaskbarShortcut = Join-Path $taskbarDirectory 'Hearthstone Script Release Candidate.lnk'
    $shell = New-Object -ComObject WScript.Shell
    try {
        $brokenShortcut = $shell.CreateShortcut($canonicalTaskbarShortcut)
        $brokenShortcut.WorkingDirectory = ''
        $brokenShortcut.Save()
    } finally { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($shell) }
    Assert-Throws -Action {
        & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $rcRuntime -PromotionWorktree $rcWorktree -BuildScript $fakeBuild -VerificationEvidencePath $evidencePath -ShortcutRoot $shortcutBase -AllowLegacyRcUpgrade -PreflightOnly | Out-Null
    } -MessageFragment "Shortcut '$canonicalTaskbarShortcut' has empty field 'WorkingDirectory'"
    $shell = New-Object -ComObject WScript.Shell
    try {
        $restoredShortcut = $shell.CreateShortcut($canonicalTaskbarShortcut)
        $restoredShortcut.TargetPath = Join-Path $env:SystemRoot 'System32\wscript.exe'
        $restoredShortcut.Arguments = '"' + (Join-Path $rcRuntime 'launch-as-admin.vbs') + '"'
        $restoredShortcut.WorkingDirectory = $rcRuntime
        $restoredShortcut.IconLocation = (Join-Path $rcRuntime 'hs-script-release-candidate.ico') + ',0'
        $restoredShortcut.Save()
    } finally { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($shell) }

    $sourcePomPath = Join-Path $project 'pom.xml'
    $sourcePom = [System.IO.File]::ReadAllText($sourcePomPath)
    $generatedRootPom = $sourcePom.Replace('<version>v4.16.669</version>', '<version>v4.16.670-local-20261022-225531PDT</version>').Replace('2026-10-23 15:00:00 PDT', '2026-10-23 16:00:00 PDT')
    [System.IO.File]::WriteAllText($sourcePomPath, $generatedRootPom)
    $modulePomPath = Join-Path $project 'hs-script-app\pom.xml'
    $modulePom = [System.IO.File]::ReadAllText($modulePomPath)
    $moduleGeneratedPom = $modulePom.Replace('v4.16.669', 'v4.16.670-local-20261022-225531PDT')
    [System.IO.File]::WriteAllText($modulePomPath, $moduleGeneratedPom)
    $repeatOutput = & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $rcRuntime -PromotionWorktree $rcWorktree -BuildScript $fakeBuild -VerificationEvidencePath $evidencePath -ShortcutRoot $shortcutBase
    if (($repeatOutput -join "`n") -notmatch 'PROMOTION_ALREADY_COMPLETE') { throw 'Repeat promotion was not idempotent' }
    if ((Get-Content -LiteralPath $invocationLog).Count -ne 1) { throw 'Repeat promotion rebuilt an already-complete RC' }

    [System.IO.File]::WriteAllText($modulePomPath, $moduleGeneratedPom.Replace('v4.16.670-local-20261022-225531PDT', 'v4.16.999-local-20261022-225531PDT'))
    Assert-Throws -Action {
        & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $rcRuntime -PromotionWorktree $rcWorktree -BuildScript $fakeBuild -VerificationEvidencePath $evidencePath -ShortcutRoot $shortcutBase | Out-Null
    } -MessageFragment 'does not match deployed version'
    [System.IO.File]::WriteAllText($modulePomPath, $moduleGeneratedPom)

    [System.IO.File]::WriteAllText($sourcePomPath, $generatedRootPom.Replace('2026-10-23 16:00:00 PDT', '2026-10-23 16:01:00 PDT'))
    Assert-Throws -Action {
        & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $rcRuntime -PromotionWorktree $rcWorktree -BuildScript $fakeBuild -VerificationEvidencePath $evidencePath -ShortcutRoot $shortcutBase | Out-Null
    } -MessageFragment 'does not match deployed version'
    [System.IO.File]::WriteAllText($sourcePomPath, $generatedRootPom)

    Set-Content -LiteralPath (Join-Path $project 'unexpected.txt') -Value 'reject this change'
    Assert-Throws -Action {
        & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $rcRuntime -PromotionWorktree $rcWorktree -BuildScript $fakeBuild -VerificationEvidencePath $evidencePath -ShortcutRoot $shortcutBase | Out-Null
    } -MessageFragment 'unsupported changes; only generated POM version/timestamp deltas matching the deployed Beta manifest are allowed'
    Remove-Item -LiteralPath (Join-Path $project 'unexpected.txt') -Force

    $extraShortcut = Join-Path (Join-Path $shortcutBase 'Desktop') 'RC-stale-copy.lnk'
    $shell = New-Object -ComObject WScript.Shell
    try {
        $staleShortcut = $shell.CreateShortcut($extraShortcut)
        $staleShortcut.TargetPath = Join-Path $env:SystemRoot 'System32\wscript.exe'
        $staleShortcut.Arguments = '"' + (Join-Path $rcRuntime 'launch-as-admin.vbs') + '"'
        $staleShortcut.WorkingDirectory = $rcRuntime
        $staleShortcut.Save()
    } finally { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($shell) }
    Assert-Throws -Action {
        & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $rcRuntime -PromotionWorktree $rcWorktree -BuildScript $fakeBuild -VerificationEvidencePath $evidencePath -ShortcutRoot $shortcutBase | Out-Null
    } -MessageFragment 'duplicate or stale shortcuts'
    Remove-Item -LiteralPath $extraShortcut -Force

    $staleRuntime = Join-Path $tempRoot 'stale-rc-parent\Hearthstone Script Release Candidate'
    New-Item -ItemType Directory -Path $staleRuntime -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $staleRuntime 'stale.txt') -Value 'do-not-overwrite'
    Assert-Throws -Action {
        & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $staleRuntime -PromotionWorktree (Join-Path $tempRoot 'unused-stale-rc-worktree') -BuildScript $fakeBuild -VerificationEvidencePath $evidencePath -ShortcutRoot $shortcutBase | Out-Null
    } -MessageFragment 'has no manifest; refusing to overwrite stale files'

    $badMetadataRuntime = Join-Path $tempRoot 'bad-metadata-parent\Hearthstone Script Beta'
    $badMetadataManifest = New-TestRuntimeManifest -Root $badMetadataRuntime -JarName $jarName -Channel 'beta' -RuntimeName 'Hearthstone Script Beta' -SourceBranch 'beta/test-promotion' -SourceCommit $betaHead
    Remove-Item -LiteralPath (Join-Path $badMetadataRuntime $jarName) -Force
    New-TestJar -Path (Join-Path $badMetadataRuntime $jarName) -Version 'v4.16.670-local-20261022-225531PDT' -Channel 'stable' -Timestamp ([string]$badMetadataManifest.buildTimestampPacific)
    $badMetadataManifest.appJarSha256 = (Get-FileHash -LiteralPath (Join-Path $badMetadataRuntime $jarName) -Algorithm SHA256).Hash.ToLowerInvariant()
    $badMetadataManifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $badMetadataRuntime 'deployment-manifest.json') -Encoding UTF8
    Assert-Throws -Action {
        & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $badMetadataRuntime -ReleaseCandidateRuntimeRoot (Join-Path $tempRoot 'bad-metadata-rc-runtime') -PromotionWorktree (Join-Path $tempRoot 'bad-metadata-rc-worktree') -BuildScript $fakeBuild -VerificationEvidencePath $evidencePath -ShortcutRoot $shortcutBase | Out-Null
    } -MessageFragment 'Beta artifact embedded channel mismatch'

    $invalidRuntime = Join-Path $tempRoot 'invalid-beta-parent\Hearthstone Script Beta'
    New-Item -ItemType Directory -Path $invalidRuntime -Force | Out-Null
    $invalidJarName = 'hs-script_v4.16.670-local-20261022-225531UTC.jar'
    New-TestRuntimeManifest -Root $invalidRuntime -JarName $invalidJarName -Channel 'beta' -RuntimeName 'Hearthstone Script Beta' -SourceBranch 'beta/test-promotion' -SourceCommit $betaHead | Out-Null
    Assert-Throws -Action {
        & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $invalidRuntime -ReleaseCandidateRuntimeRoot (Join-Path $tempRoot 'invalid-rc-runtime') -PromotionWorktree (Join-Path $tempRoot 'invalid-rc-worktree') -BuildScript $fakeBuild -VerificationEvidencePath $evidencePath -ShortcutRoot $shortcutBase | Out-Null
    } -MessageFragment 'Beta artifact must use a numeric release version'

    $missingRuntime = Join-Path $tempRoot 'missing-beta-parent\Hearthstone Script Beta'
    Assert-Throws -Action { & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $missingRuntime -ReleaseCandidateRuntimeRoot $rcRuntime -PromotionWorktree (Join-Path $tempRoot 'unused-worktree') -BuildScript $fakeBuild -VerificationEvidencePath $evidencePath -ShortcutRoot $shortcutBase | Out-Null } -MessageFragment 'Beta deployment manifest is missing'

    Set-Content -LiteralPath (Join-Path $project 'dirty.txt') -Value 'dirty'
    Assert-Throws -Action { & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $rcRuntime -PromotionWorktree $rcWorktree -BuildScript $fakeBuild -VerificationEvidencePath $evidencePath -ShortcutRoot $shortcutBase | Out-Null } -MessageFragment 'unsupported changes; only generated POM version/timestamp deltas matching the deployed Beta manifest are allowed'
    Remove-Item -LiteralPath (Join-Path $project 'dirty.txt') -Force

    New-Item -ItemType Directory -Path (Join-Path $legacyRcRuntime 'config'), (Join-Path $legacyRcRuntime 'data'), (Join-Path $legacyRcRuntime 'log'), (Join-Path $legacyRcRuntime 'plugin'), (Join-Path $legacyRcRuntime 'rollback-backups') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $legacyRcRuntime 'config\user.ini') -Value 'keep-config' -NoNewline
    Set-Content -LiteralPath (Join-Path $legacyRcRuntime 'config\script.ini') -Value 'keep-script-config' -NoNewline
    Set-Content -LiteralPath (Join-Path $legacyRcRuntime 'data\user.db') -Value 'keep-data' -NoNewline
    Set-Content -LiteralPath (Join-Path $legacyRcRuntime 'log\user.log') -Value 'keep-log' -NoNewline
    Set-Content -LiteralPath (Join-Path $legacyRcRuntime 'plugin\custom-plugin.jar') -Value 'keep-plugin' -NoNewline
    Set-Content -LiteralPath (Join-Path $legacyRcRuntime 'rollback-backups\old.bak') -Value 'keep-backup' -NoNewline
    Set-Content -LiteralPath (Join-Path $legacyRcRuntime 'hs_cards.db.backup-legacy.bak') -Value 'keep-card-backup' -NoNewline
    $legacyJarName = 'hs-script_v4.16.669-local-20261022-181002PDT.jar'
    $legacyManifest = New-TestRuntimeManifest -Root $legacyRcRuntime -JarName $legacyJarName -Channel 'release-candidate' -RuntimeName 'Hearthstone Script Release Candidate' -SourceBranch 'beta/test-promotion' -SourceCommit $betaHead -Timestamp '2026-10-22 18:10:02 PDT'
    New-TestRcLaunchersAndShortcuts -Root $legacyRcRuntime -ShortcutBase $legacyShortcutBase
    $legacySentinels = @(
        (Join-Path $legacyRcRuntime 'config\user.ini'),
        (Join-Path $legacyRcRuntime 'config\script.ini'),
        (Join-Path $legacyRcRuntime 'data\user.db'),
        (Join-Path $legacyRcRuntime 'log\user.log'),
        (Join-Path $legacyRcRuntime 'plugin\custom-plugin.jar'),
        (Join-Path $legacyRcRuntime 'rollback-backups\old.bak'),
        (Join-Path $legacyRcRuntime 'hs_cards.db.backup-legacy.bak')
    )
    $legacySentinelHashes = @{}
    foreach ($sentinel in $legacySentinels) { $legacySentinelHashes[$sentinel] = (Get-FileHash -LiteralPath $sentinel -Algorithm SHA256).Hash }
    $env:PROMOTION_TEST_RC_ROOT = $legacyRcRuntime
    $legacyPreflight = & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $legacyRcRuntime -PromotionWorktree $legacyRcWorktree -ReleaseCandidateBranch 'beta/release-candidate-legacy-upgrade-test' -BuildScript $fakeBuild -VerificationEvidencePath $evidencePath -ShortcutRoot $legacyShortcutBase -AllowLegacyRcUpgrade -PreflightOnly
    if (($legacyPreflight -join "`n") -notmatch 'PREFLIGHT_OK.*legacyRcUpgrade=True') { throw 'Legacy RC preflight did not report the reviewed upgrade path' }
    if ((Get-ChildItem -LiteralPath (Split-Path -Parent $legacyRcRuntime) -Directory -Filter 'Hearthstone Script Release Candidate.rollback-*' -ErrorAction SilentlyContinue).Count -ne 0) { throw 'Preflight created a rollback snapshot' }
    $legacyOutput = & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $legacyRcRuntime -PromotionWorktree $legacyRcWorktree -ReleaseCandidateBranch 'beta/release-candidate-legacy-upgrade-test' -BuildScript $fakeBuild -VerificationEvidencePath $evidencePath -ShortcutRoot $legacyShortcutBase -AllowLegacyRcUpgrade
    $legacyOutputText = $legacyOutput -join "`n"
    if ($legacyOutputText -notmatch 'RC_ROLLBACK_SNAPSHOT=' -or $legacyOutputText -notmatch 'RC_PROTECTED_STATE_VERIFIED=' -or $legacyOutputText -notmatch 'PROMOTION_COMPLETE') { throw 'Legacy RC upgrade did not report snapshot, protected state, and completion' }
    $legacySnapshotPath = ([regex]::Match($legacyOutputText, 'RC_ROLLBACK_SNAPSHOT=(.+)')).Groups[1].Value.Trim()
    if (-not (Test-Path -LiteralPath (Join-Path $legacySnapshotPath 'rollback-manifest.json') -PathType Leaf)) { throw 'Legacy RC rollback manifest is missing' }
    if (-not (Test-Path -LiteralPath (Join-Path $legacySnapshotPath $legacyJarName) -PathType Leaf)) { throw 'Legacy RC old application JAR was not snapshotted' }
    foreach ($sentinel in $legacySentinels) {
        if (-not (Test-Path -LiteralPath $sentinel -PathType Leaf)) { throw "Legacy RC sentinel disappeared: $sentinel" }
        if ((Get-FileHash -LiteralPath $sentinel -Algorithm SHA256).Hash -cne $legacySentinelHashes[$sentinel]) { throw "Legacy RC sentinel changed: $sentinel" }
    }
    $env:PROMOTION_TEST_RC_ROOT = $rcRuntime

    $wrongBranchConfig = Get-Content -LiteralPath (Join-Path $project 'release-channel.json') -Raw | ConvertFrom-Json
    $wrongBranchConfig.branch = 'beta/not-the-current-branch'
    $wrongBranchConfig | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $project 'release-channel.json') -Encoding UTF8
    Invoke-TestGit -Root $project -Arguments @('add', 'release-channel.json') | Out-Null
    Invoke-TestGit -Root $project -Arguments @('commit', '-m', 'invalid branch metadata fixture') | Out-Null
    Assert-Throws -Action { & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $rcRuntime -PromotionWorktree (Join-Path $tempRoot 'unused-worktree-2') -BuildScript $fakeBuild -VerificationEvidencePath $evidencePath -ShortcutRoot $shortcutBase | Out-Null } -MessageFragment 'Beta source branch metadata mismatch'

    $wrongChannelConfig = Get-Content -LiteralPath (Join-Path $project 'release-channel.json') -Raw | ConvertFrom-Json
    $wrongChannelConfig.branch = 'beta/test-promotion'
    $wrongChannelConfig.channel = 'stable'
    $wrongChannelConfig | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $project 'release-channel.json') -Encoding UTF8
    Invoke-TestGit -Root $project -Arguments @('add', 'release-channel.json') | Out-Null
    Invoke-TestGit -Root $project -Arguments @('commit', '-m', 'invalid channel metadata fixture') | Out-Null
    Assert-Throws -Action { & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $rcRuntime -PromotionWorktree (Join-Path $tempRoot 'unused-worktree-3') -BuildScript $fakeBuild -VerificationEvidencePath $evidencePath -ShortcutRoot $shortcutBase | Out-Null } -MessageFragment 'Beta source channel mismatch'

    Write-Output 'PromoteBetaToReleaseCandidate.Tests: PASS (E2E-pending candidate, exact evidence binding, Stable-readiness hold, shortcut channel contract, Desktop fallback, empty-field diagnostics, recoverable RC duplicate-pin cleanup, Beta/Stable pin preservation, clean promotion, exact source snapshot, RC isolation, idempotent repeat, dirty/missing/mismatched refusal)'
} finally {
    Remove-Item Env:PROMOTION_TEST_RC_ROOT -ErrorAction SilentlyContinue
    Remove-Item Env:PROMOTION_TEST_INVOCATION -ErrorAction SilentlyContinue
    Remove-Item Env:PROMOTION_TEST_SHORTCUT_ROOT -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}
