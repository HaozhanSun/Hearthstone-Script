[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BranchName
)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$configPath = Join-Path $projectRoot 'release-channel.json'
$pomPath = Join-Path $projectRoot 'pom.xml'

if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "release-channel.json is missing: $configPath"
}
if (-not (Test-Path -LiteralPath $pomPath -PathType Leaf)) {
    throw "pom.xml is missing: $pomPath"
}

$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
$pom = Get-Content -LiteralPath $pomPath -Raw
$versionMatch = [regex]::Match($pom, '(?s)(<artifactId>hs-script</artifactId>\s*<version>)([^<]+)(</version>)')
if (-not $versionMatch.Success) { throw 'Root hs-script version was not found in pom.xml' }
$version = $versionMatch.Groups[2].Value

if ($BranchName -eq 'main') {
    if ($config.channel -ne 'stable' -or $config.branch -ne 'main') {
        throw 'main must declare channel=stable and branch=main in release-channel.json'
    }
    if ($config.runtimeDirectoryName -ne 'Hearthstone Script' -or
        $config.shortcutName -ne 'Hearthstone Script.lnk') {
        throw 'stable channel must use the canonical Hearthstone Script runtime and shortcut'
    }
    if ($version -match '(?i)(-beta|SNAPSHOT)') {
        throw "main contains a beta/snapshot application version: $version"
    }
    if ($config.iconFileName -ne 'hs-script.exe') { throw 'stable channel must use the application icon' }
    Write-Output "RELEASE_CHANNEL=stable"
}
elseif ($BranchName -like 'beta/*') {
    if ($config.channel -ne 'beta' -or $config.branch -ne $BranchName) {
        throw "beta branch metadata must declare channel=beta and branch=$BranchName"
    }
    if ($config.runtimeDirectoryName -ne 'Hearthstone Script Beta' -or
        $config.shortcutName -ne 'Hearthstone Script Beta.lnk') {
        throw 'beta channel must use the isolated Hearthstone Script Beta runtime and shortcut'
    }
    if ($config.iconFileName -ne 'hs-script-beta.ico') { throw 'beta channel must use hs-script-beta.ico' }
    Write-Output "RELEASE_CHANNEL=beta"
}
else {
    throw "Unsupported release branch: $BranchName. Use main or beta/*"
}

Write-Output "APPLICATION_VERSION=$version"
Write-Output 'RELEASE_CHANNEL_VALID'
