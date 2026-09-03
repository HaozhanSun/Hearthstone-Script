[CmdletBinding()]
param(
    [string]$RuntimeRoot = "C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script",
    [string]$ShortcutName = "Hearthstone Script.lnk",
    [string]$Description = "",
    [string]$IconPath = ""
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

$iconCacheRefresh = Join-Path $env:SystemRoot "System32\ie4uinit.exe"
if (Test-Path -LiteralPath $iconCacheRefresh) {
    Start-Process -FilePath $iconCacheRefresh -ArgumentList "-show" -WindowStyle Hidden -Wait
}

foreach ($path in $updated) { Write-Output "UPDATED_SHORTCUT=$path" }
Write-Output "SHORTCUT_SYNC_COMPLETE launcher=$launcher"
