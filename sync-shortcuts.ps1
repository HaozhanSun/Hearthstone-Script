[CmdletBinding()]
param(
    [string]$RuntimeRoot = "C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script",
    [ValidateSet('stable', 'beta', 'release-candidate')]
    [string]$Channel = 'stable',
    [string]$ShortcutName = "Hearthstone Script.lnk",
    [string]$Description = "",
    [string]$IconPath = "",
    [string]$LegacyShortcutName = "Hearthstone.lnk",
    [string]$LegacyRuntimeRoot = "C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script"
)

$ErrorActionPreference = "Stop"
$runtimeRoot = [System.IO.Path]::GetFullPath($RuntimeRoot).TrimEnd('\')
$launcher = Join-Path $runtimeRoot "launch-as-admin.vbs"
$icon = if ([string]::IsNullOrWhiteSpace($IconPath)) { Join-Path $runtimeRoot "hs-script.exe" } else { [System.IO.Path]::GetFullPath($IconPath) }
if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) { throw "Stable launcher missing: $launcher" }
if (-not (Test-Path -LiteralPath $icon -PathType Leaf)) { throw "Application icon missing: $icon" }

$shortcutDirectories = @(
    [Environment]::GetFolderPath("Desktop"),
    (Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"),
    (Join-Path $env:APPDATA "Microsoft\Internet Explorer\Quick Launch\User Pinned\TaskBar")
)
$shell = New-Object -ComObject WScript.Shell
$target = Join-Path $env:SystemRoot "System32\wscript.exe"
$arguments = '"' + $launcher + '"'
$updated = [System.Collections.Generic.List[string]]::new()
. (Join-Path $PSScriptRoot 'shortcut-sync-policy.ps1')

foreach ($directory in $shortcutDirectories) {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $shortcutPath = Join-Path $directory $shortcutName
    $shortcut = $shell.CreateShortcut($shortcutPath)
    $shortcut.TargetPath = $target
    $shortcut.Arguments = $arguments
    $shortcut.WorkingDirectory = $runtimeRoot
    $shortcut.IconLocation = "$icon,0"
    $shortcut.Description = if ([string]::IsNullOrWhiteSpace($Description)) {
        "$($shortcutName -replace '\.lnk$','')（管理员启动，自动使用最新构建）"
    } else { $Description }
    $shortcut.WindowStyle = 1
    $shortcut.Save()
    $updated.Add($shortcutPath)
}

# Beta taskbar pins are the deployment source of truth. Windows may create a
# numbered duplicate beside the canonical pin; retain the canonical link and
# remove only numbered duplicates for this exact Beta shortcut identity.
$taskbarDirectory = $shortcutDirectories[2]
$removedBetaDuplicates = Remove-RedundantBetaTaskbarShortcuts `
    -Directory $taskbarDirectory `
    -Channel $Channel `
    -ShortcutName $ShortcutName
foreach ($path in $removedBetaDuplicates) { Write-Output "REMOVED_DUPLICATE_SHORTCUT=$path" }

# Repair the pre-channel generic Desktop shortcut when it still exists. Older
# installers left this shortcut pointing at the removed `outputs\Hearthstone`
# directory, which makes Windows Script Host try to execute a path with no
# extension. Keep the channel shortcuts isolated; this legacy entry always
# resolves to the stable launcher and is only repaired, never created.
$legacyShortcutPath = Join-Path ([Environment]::GetFolderPath("Desktop")) $LegacyShortcutName
$legacyRoot = [System.IO.Path]::GetFullPath($LegacyRuntimeRoot).TrimEnd('\')
$legacyLauncher = Join-Path $legacyRoot "launch-as-admin.vbs"
$legacyIcon = Join-Path $legacyRoot "hs-script.exe"
if ($Channel -eq 'stable' -and
    (Test-Path -LiteralPath $legacyShortcutPath -PathType Leaf) -and
    (Test-Path -LiteralPath $legacyLauncher -PathType Leaf) -and
    (Test-Path -LiteralPath $legacyIcon -PathType Leaf)) {
    $legacyShortcut = $shell.CreateShortcut($legacyShortcutPath)
    $legacyShortcut.TargetPath = $target
    $legacyShortcut.Arguments = '"' + $legacyLauncher + '"'
    $legacyShortcut.WorkingDirectory = $legacyRoot
    $legacyShortcut.IconLocation = "$legacyIcon,0"
    $legacyShortcut.Description = "Hearthstone Script（管理员启动，自动使用最新稳定构建）"
    $legacyShortcut.WindowStyle = 1
    $legacyShortcut.Save()
    Write-Output "REPAIRED_LEGACY_SHORTCUT=$legacyShortcutPath"
}

$iconCacheRefresh = Join-Path $env:SystemRoot "System32\ie4uinit.exe"
if (Test-Path -LiteralPath $iconCacheRefresh) {
    Start-Process -FilePath $iconCacheRefresh -ArgumentList "-show" -WindowStyle Hidden -Wait
}

foreach ($path in $updated) { Write-Output "UPDATED_SHORTCUT=$path" }
Write-Output "SHORTCUT_SYNC_COMPLETE launcher=$launcher"
