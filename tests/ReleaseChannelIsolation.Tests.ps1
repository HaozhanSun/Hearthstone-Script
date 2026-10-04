$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$branchName = 'beta/release-candidate-v4.16.551'
$config = Get-Content -LiteralPath (Join-Path $projectRoot 'release-channel.json') -Raw | ConvertFrom-Json
if ($config.channel -ne 'release-candidate' -or $config.branch -ne $branchName) {
    throw 'Release Candidate metadata does not identify this isolated RC branch'
}
if ($config.runtimeDirectoryName -ne 'Hearthstone Script Release Candidate' -or
    $config.shortcutName -ne 'Hearthstone Script Release Candidate.lnk' -or
    $config.iconFileName -ne 'hs-script-release-candidate.ico') {
    throw 'Release Candidate runtime, shortcut, or icon identity is incorrect'
}

& (Join-Path $projectRoot '.github/scripts/verify-release-channel.ps1') -BranchName $branchName | Out-Null

$deployScript = Get-Content -LiteralPath (Join-Path $projectRoot 'build-and-deploy.ps1') -Raw
$shortcutScript = Get-Content -LiteralPath (Join-Path $projectRoot 'sync-shortcuts.ps1') -Raw
$iconScript = Get-Content -LiteralPath (Join-Path $projectRoot 'tools/create-channel-icon.ps1') -Raw
if ($deployScript -notmatch "'release-candidate'") { throw 'Build/deploy does not allow the RC channel' }
if ($deployScript -notmatch "'release-candidate'\) \{ 'RC' \} else \{ 'B' \}") {
    throw 'Build/deploy does not request the distinct RC icon badge'
}
if (-not $deployScript.Contains('Copy-Item -LiteralPath $channelConfigPath -Destination') -or
    -not $deployScript.Contains("'release-channel.json') -Force")) {
    throw 'Build/deploy does not install channel identity metadata beside the launcher'
}
if (-not $deployScript.Contains('sync-shortcuts.ps1') -or
    -not $deployScript.Contains('-RuntimeRoot $runtimeRoot') -or
    -not $deployScript.Contains('-Channel $Channel')) {
    throw 'Build/deploy does not bind shortcut sync to the selected channel runtime'
}
foreach ($folder in @('GetFolderPath("Desktop")', 'Microsoft\Windows\Start Menu\Programs', 'User Pinned\TaskBar')) {
    if ($shortcutScript -notmatch [regex]::Escape($folder)) { throw "Shortcut sync is missing required location: $folder" }
}
if ($shortcutScript -notmatch 'System32\\wscript\.exe' -or
    -not $shortcutScript.Contains('$arguments = ''"'' + $launcher')) {
    throw 'Shortcuts must target wscript.exe with the canonical runtime-local VBS launcher'
}
if ($iconScript -notmatch "'RC'" -or $iconScript -notmatch 'DarkOrange') {
    throw 'RC icon badge is not distinct from Beta'
}

# Exercise the exact launcher manifest resolver with synthetic files under a
# temporary directory. No user runtime, shortcut, app, or game is touched.
. (Join-Path $projectRoot 'hs-script-app/src/main/resources/bat/deployment-contract.ps1')
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hs-rc-isolation-" + [guid]::NewGuid().ToString('N'))
$rcRoot = Join-Path $tempRoot 'Hearthstone Script Release Candidate'
$betaRoot = Join-Path $tempRoot 'Hearthstone Script Beta'
New-Item -ItemType Directory -Path $rcRoot, $betaRoot -Force | Out-Null
try {
    [System.IO.File]::WriteAllText((Join-Path $rcRoot 'rc.jar'), 'rc-artifact')
    [System.IO.File]::WriteAllText((Join-Path $rcRoot 'strategy-lib.jar'), 'same-strategy-artifact')
    [System.IO.File]::WriteAllText((Join-Path $rcRoot 'strategy-plugin.jar'), 'same-strategy-artifact')
    [System.IO.File]::WriteAllText((Join-Path $rcRoot 'card-sdk.jar'), 'card-sdk')
    $rcManifest = [ordered]@{
        schema = 1
        releaseChannel = 'release-candidate'
        runtimeDirectoryName = 'Hearthstone Script Release Candidate'
        appJar = 'rc.jar'
        appJarSha256 = (Get-FileHash -LiteralPath (Join-Path $rcRoot 'rc.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
        strategyPluginLib = 'strategy-lib.jar'
        strategyPlugin = 'strategy-plugin.jar'
        strategyPluginSha256 = (Get-FileHash -LiteralPath (Join-Path $rcRoot 'strategy-lib.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
        cardSdk = 'card-sdk.jar'
        cardSdkSha256 = (Get-FileHash -LiteralPath (Join-Path $rcRoot 'card-sdk.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
        deploymentId = 'rc.jar|test'
    }
    $rcManifestPath = Join-Path $rcRoot 'deployment-manifest.json'
    $rcManifest | ConvertTo-Json | Set-Content -LiteralPath $rcManifestPath -Encoding UTF8
    $resolved = Resolve-Deployment $rcRoot
    if ($resolved.Manifest.releaseChannel -ne 'release-candidate' -or $resolved.Root -ne $rcRoot) {
        throw 'RC launcher did not resolve its own RC manifest'
    }

    Copy-Item -LiteralPath $rcManifestPath -Destination (Join-Path $betaRoot 'deployment-manifest.json')
    try {
        Resolve-Deployment $betaRoot | Out-Null
        throw 'Beta launcher accepted an RC manifest'
    } catch {
        if ($_.Exception.Message -eq 'Beta launcher accepted an RC manifest') { throw }
    }

    $wrongChannelManifest = $rcManifest | ConvertTo-Json | ConvertFrom-Json
    $wrongChannelManifest.releaseChannel = 'beta'
    $wrongChannelManifest.runtimeDirectoryName = 'Hearthstone Script Beta'
    $wrongChannelManifest | ConvertTo-Json | Set-Content -LiteralPath $rcManifestPath -Encoding UTF8
    try {
        Resolve-Deployment $rcRoot | Out-Null
        throw 'RC launcher accepted a Beta manifest'
    } catch {
        if ($_.Exception.Message -eq 'RC launcher accepted a Beta manifest') { throw }
    }
} finally {
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}

Write-Output 'ReleaseChannelIsolation.Tests: PASS (RC metadata/verifier, isolated manifest resolution, RC-only identity/icon, three canonical launcher shortcuts)'
