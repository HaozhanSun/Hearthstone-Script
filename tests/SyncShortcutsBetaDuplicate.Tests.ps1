$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $projectRoot 'tools/shortcut-sync-policy.ps1')
$syncScript = Get-Content -LiteralPath (Join-Path $projectRoot 'sync-shortcuts.ps1') -Raw
if (-not $syncScript.Contains("'tools/shortcut-sync-policy.ps1'") -or
    -not $syncScript.Contains('Remove-BetaTaskbarDuplicateShortcuts') -or
    -not $syncScript.Contains('-TaskbarDirectory $taskbarDirectory') -or
    -not $syncScript.Contains('-Channel $Channel')) {
    throw 'Shortcut sync must call the channel-scoped duplicate policy for the Taskbar directory'
}

function Assert-True([bool]$Condition, [string]$Label) {
    if (-not $Condition) { throw "Assertion failed: $Label" }
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hs-beta-pin-test-" + [guid]::NewGuid().ToString('N'))
$taskbar = Join-Path $tempRoot 'Taskbar'
New-Item -ItemType Directory -Path $taskbar -Force | Out-Null
$betaLauncher = Join-Path $tempRoot 'Beta/launch-as-admin.vbs'
$stableLauncher = Join-Path $tempRoot 'Stable/launch-as-admin.vbs'
$otherLauncher = Join-Path $tempRoot 'Other/launch-as-admin.vbs'
foreach ($launcher in @($betaLauncher, $stableLauncher, $otherLauncher)) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $launcher) -Force | Out-Null
    Set-Content -LiteralPath $launcher -Value ''
}

function New-TestShortcut([string]$Name, [string]$Target) {
    $shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $taskbar $Name))
    $shortcut.TargetPath = Join-Path $env:SystemRoot 'System32/wscript.exe'
    $shortcut.Arguments = '"' + $Target + '"'
    $shortcut.Save()
}

try {
    New-TestShortcut 'Hearthstone Script Beta.lnk' $betaLauncher
    New-TestShortcut 'Hearthstone Script Beta (2).lnk' $betaLauncher
    New-TestShortcut 'Hearthstone Script Beta (3).lnk' $stableLauncher
    New-TestShortcut 'Hearthstone Script Beta Helper (2).lnk' $otherLauncher
    New-TestShortcut 'Hearthstone Script Stable.lnk' $stableLauncher
    New-TestShortcut 'Unrelated Tool.lnk' $otherLauncher

    $removed = Remove-BetaTaskbarDuplicateShortcuts `
        -TaskbarDirectory $taskbar `
        -Channel beta `
        -ShortcutName 'Hearthstone Script Beta.lnk' `
        -LauncherPath $betaLauncher

    Assert-True (Test-Path -LiteralPath (Join-Path $taskbar 'Hearthstone Script Beta.lnk')) 'canonical Beta pin remains'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $taskbar 'Hearthstone Script Beta (2).lnk'))) 'duplicate Beta pin removed'
    Assert-True (Test-Path -LiteralPath (Join-Path $taskbar 'Hearthstone Script Beta (3).lnk')) 'numbered alias targeting Stable is preserved'
    Assert-True (Test-Path -LiteralPath (Join-Path $taskbar 'Hearthstone Script Beta Helper (2).lnk')) 'similarly named unrelated pin is preserved'
    Assert-True (Test-Path -LiteralPath (Join-Path $taskbar 'Hearthstone Script Stable.lnk')) 'Stable pin is preserved'
    Assert-True (Test-Path -LiteralPath (Join-Path $taskbar 'Unrelated Tool.lnk')) 'unrelated pin is preserved'
    Assert-True (@($removed).Count -eq 1) 'only the verified duplicate Beta launcher was removed'

    New-TestShortcut 'Hearthstone Script Beta (4).lnk' $betaLauncher
    $stableRun = Remove-BetaTaskbarDuplicateShortcuts `
        -TaskbarDirectory $taskbar `
        -Channel stable `
        -ShortcutName 'Hearthstone Script Beta.lnk' `
        -LauncherPath $betaLauncher
    Assert-True (@($stableRun).Count -eq 0) 'Stable sync is not authorized to remove Beta pins'
    Assert-True (Test-Path -LiteralPath (Join-Path $taskbar 'Hearthstone Script Beta (4).lnk')) 'Beta pin remains after Stable policy call'
} finally {
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}

Write-Output 'SyncShortcutsBetaDuplicate.Tests: PASS (Beta duplicate removed; canonical, Stable, wrong-target and unrelated pins preserved)'
