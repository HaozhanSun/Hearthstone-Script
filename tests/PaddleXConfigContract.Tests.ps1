$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $root 'paddlex-config-utils.ps1')
. (Join-Path $root 'hs-script-app/src/main/resources/bat/deployment-contract.ps1')

function Assert-Equal([string]$Expected, [string]$Actual, [string]$Label) {
    if ($Expected -cne $Actual) { throw "$Label expected '$Expected' but got '$Actual'" }
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("beta-paddlex-contract-" + [guid]::NewGuid().ToString('N'))
$betaRoot = Join-Path $tempRoot 'Hearthstone Script Beta'
$fallbackRoot = Join-Path $tempRoot 'validated-fallback'
$modulePath = Join-Path $betaRoot 'resources\paddlex-vision\src'
$configPath = Join-Path $betaRoot 'config\script.ini'
$fallbackConfigPath = Join-Path $fallbackRoot 'config\script.ini'
$gamePath = Join-Path $tempRoot 'verified-game'
$platformPath = Join-Path $tempRoot 'verified-platform\Battle.net.exe'
try {
    New-Item -ItemType Directory -Path $modulePath, (Split-Path -Parent $configPath), $gamePath, `
        (Split-Path -Parent $platformPath), (Split-Path -Parent $fallbackConfigPath) -Force | Out-Null
    New-Item -ItemType File -Path (Join-Path $gamePath 'Hearthstone.exe'), $platformPath -Force | Out-Null
    $moduleIniPath = ConvertTo-IniPath $modulePath
    $fallbackGameIni = ConvertTo-IniPath $gamePath
    $fallbackPlatformIni = ConvertTo-IniPath $platformPath
    $betaOriginalGame = 'D:/Beta-specific/custom-game-path'
    $betaOriginalPlatform = 'D:/Beta-specific/custom-platform.exe'
    $config = @"
[dev]
GAME_PATH = $betaOriginalGame
PLATFORM_PATH = $betaOriginalPlatform
PADDLEX_OCR_PYTHON = 
PADDLEX_OCR_MODULE_PATH = C:/removed/old-paddlex-module
PADDLEX_OCR_MODEL_CACHE = 
"@
    [System.IO.File]::WriteAllText($configPath, $config)
    $fallback = @"
[dev]
GAME_PATH = $fallbackGameIni
PLATFORM_PATH = $fallbackPlatformIni
"@
    [System.IO.File]::WriteAllText($fallbackConfigPath, $fallback)

    Sync-PaddleXOcrConfig -Root $betaRoot -FallbackRoot $fallbackRoot -PreserveGamePlatformPaths | Out-Null
    $synced = [System.IO.File]::ReadAllText($configPath)
    Assert-Equal $betaOriginalGame (Get-IniValue $synced 'GAME_PATH') 'Beta game path preservation'
    Assert-Equal $betaOriginalPlatform (Get-IniValue $synced 'PLATFORM_PATH') 'Beta platform path preservation'
    Assert-Equal $moduleIniPath (Get-IniValue $synced 'PADDLEX_OCR_MODULE_PATH') 'stale PaddleX module repair'
    Assert-Equal $modulePath (Assert-PaddleXOcrModulePath $betaRoot) 'PaddleX module preflight'

    $customModulePath = Join-Path $tempRoot 'custom-paddlex-module'
    New-Item -ItemType Directory -Path $customModulePath -Force | Out-Null
    $customModuleIni = ConvertTo-IniPath $customModulePath
    [System.IO.File]::WriteAllText($configPath, $synced.Replace($moduleIniPath, $customModuleIni))
    Sync-PaddleXOcrConfig -Root $betaRoot -FallbackRoot $fallbackRoot -PreserveGamePlatformPaths | Out-Null
    $customModuleConfig = [System.IO.File]::ReadAllText($configPath)
    Assert-Equal $customModuleIni (Get-IniValue $customModuleConfig 'PADDLEX_OCR_MODULE_PATH') 'valid custom module preservation'
    Assert-Equal $customModulePath (Assert-PaddleXOcrModulePath $betaRoot) 'valid custom module preflight'

    $broken = $customModuleConfig.Replace($customModuleIni, 'C:/missing/packaged/module')
    [System.IO.File]::WriteAllText($configPath, $broken)
    Sync-PaddleXOcrConfig -Root $betaRoot -FallbackRoot $fallbackRoot -PreserveGamePlatformPaths | Out-Null
    $repaired = [System.IO.File]::ReadAllText($configPath)
    Assert-Equal $moduleIniPath (Get-IniValue $repaired 'PADDLEX_OCR_MODULE_PATH') 'missing module repair'
    Assert-Equal $betaOriginalGame (Get-IniValue $repaired 'GAME_PATH') 'Beta game path after module repair'
    Assert-Equal $betaOriginalPlatform (Get-IniValue $repaired 'PLATFORM_PATH') 'Beta platform path after module repair'

    $invalidPaths = $repaired.Replace($betaOriginalGame, 'D:/invalid/game').Replace($betaOriginalPlatform, 'D:/invalid/platform.exe')
    [System.IO.File]::WriteAllText($configPath, $invalidPaths)
    Sync-PaddleXOcrConfig -Root $betaRoot -FallbackRoot $fallbackRoot | Out-Null
    $repairedPaths = [System.IO.File]::ReadAllText($configPath)
    Assert-Equal $fallbackGameIni (Get-IniValue $repairedPaths 'GAME_PATH') 'validated game fallback'
    Assert-Equal $fallbackPlatformIni (Get-IniValue $repairedPaths 'PLATFORM_PATH') 'validated platform fallback'

    $gateRoot = Join-Path $tempRoot 'Hearthstone Script Beta Gate Test'
    $gate = Enter-HsScriptRuntimeGate $gateRoot
    try {
        $contractPath = (Join-Path $root 'hs-script-app/src/main/resources/bat/deployment-contract.ps1').Replace("'", "''")
        $gatePath = $gateRoot.Replace("'", "''")
        $childScript = @"
`$ErrorActionPreference = 'Stop'
. '$contractPath'
try {
    `$childGate = Enter-HsScriptRuntimeGate '$gatePath'
    Exit-HsScriptRuntimeGate `$childGate
    exit 2
} catch {
    if (`$_.Exception.Message -match 'being deployed or launched') { exit 0 }
    exit 3
}
"@
        $null = & (Get-Command pwsh).Source -NoLogo -NoProfile -Command $childScript 2>&1
        if ($LASTEXITCODE -ne 0) { throw "Runtime mutex was not exclusive across processes (child exit=$LASTEXITCODE)" }
    } finally { Exit-HsScriptRuntimeGate $gate }

    Remove-Item -LiteralPath $modulePath -Recurse -Force
    $missingModuleRejected = $false
    try { Assert-PaddleXOcrModulePath $betaRoot | Out-Null } catch { $missingModuleRejected = $_.Exception.Message -match 'missing or unresolved' }
    Assert-Equal 'True' ([string]$missingModuleRejected) 'missing packaged module refusal'
} finally {
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}

$deployScript = [System.IO.File]::ReadAllText((Join-Path $root 'build-and-deploy.ps1'))
foreach ($required in @(
    '. $deploymentContractSource',
    '. $paddleXConfigUtilsSource',
    '$runtimeGate = Enter-HsScriptRuntimeGate $runtimeRoot',
    'Exit-HsScriptRuntimeGate $runtimeGate',
    "Copy-Item -LiteralPath `$launcherGuardianPolicySource -Destination (Join-Path `$runtimeRoot 'launcher-guardian-policy.ps1') -Force",
    '$verifiedPaddleXModulePath = Assert-PaddleXOcrModulePath $runtimeRoot',
    'Sync-PaddleXOcrConfig @paddleXSyncArgs'
)) {
    if (-not $deployScript.Contains($required)) { throw "Build/deploy safety hook is missing: $required" }
}
if (-not $deployScript.Contains('foreach ($requiredSource in @($deploymentContractSource, $launcherGuardianPolicySource, $paddleXConfigUtilsSource))')) {
    throw 'Build/deploy must fail before mutation when a launcher or PaddleX helper source is missing'
}
$gatePosition = $deployScript.IndexOf('$runtimeGate = Enter-HsScriptRuntimeGate $runtimeRoot', [System.StringComparison]::Ordinal)
$runtimeCreatePosition = $deployScript.IndexOf('New-Item -ItemType Directory -Path $runtimeRoot', [System.StringComparison]::Ordinal)
if ($gatePosition -lt 0 -or $runtimeCreatePosition -lt 0 -or $gatePosition -gt $runtimeCreatePosition) {
    throw 'Build/deploy must acquire the runtime gate before creating or mutating the channel runtime'
}
$launcherScript = [System.IO.File]::ReadAllText((Join-Path $root 'hs-script-app/src/main/resources/bat/launch-newest-as-admin.ps1'))
if (-not $launcherScript.Contains(". (Join-Path `$scriptDirectory 'launcher-guardian-policy.ps1')")) {
    throw 'Canonical launcher must consume the guardian policy that deployment refreshes'
}
if ($deployScript -notmatch 'PreserveGamePlatformPaths\s*=\s*\$true') {
    throw 'Beta deploy must preserve its channel-specific game/platform path behavior'
}
$channel = Get-Content -LiteralPath (Join-Path $root 'release-channel.json') -Raw | ConvertFrom-Json
Assert-Equal 'beta' ([string]$channel.channel) 'Beta channel metadata'
Assert-Equal 'Hearthstone Script Beta' ([string]$channel.runtimeDirectoryName) 'Beta runtime identity'
Assert-Equal 'Hearthstone Script Beta.lnk' ([string]$channel.shortcutName) 'Beta shortcut identity'

Write-Output 'PaddleXConfigContract.Tests: PASS (Beta config preservation, path repair/preflight, runtime mutex exclusion, guardian packaging, Beta channel identity)'
