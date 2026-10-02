[CmdletBinding()]
param(
    [string]$RuntimeRoot = "C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script",
    [string]$ShortcutName = "Hearthstone Script.lnk",
    [string]$Description = "",
    [string]$IconPath = "",
    [string]$LegacyShortcutName = "Hearthstone.lnk",
    [string]$LegacyRuntimeRoot = "C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script",
    [switch]$RequireTaskbarRegistration
)

$ErrorActionPreference = "Stop"
$runtimeRoot = [System.IO.Path]::GetFullPath($RuntimeRoot).TrimEnd('\')
$launcher = Join-Path $runtimeRoot "launch-as-admin.vbs"
$icon = if ([string]::IsNullOrWhiteSpace($IconPath)) { Join-Path $runtimeRoot "hs-script.exe" } else { [System.IO.Path]::GetFullPath($IconPath) }
if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) { throw "Stable launcher missing: $launcher" }
if (-not (Test-Path -LiteralPath $icon -PathType Leaf)) { throw "Application icon missing: $icon" }

. (Join-Path $PSScriptRoot 'taskbar-pin-contract.ps1')
$taskbarDirectory = Join-Path $env:APPDATA "Microsoft\Internet Explorer\Quick Launch\User Pinned\TaskBar"
$taskbarShortcutPath = Join-Path $taskbarDirectory $ShortcutName
$taskbarPlan = $null
if ($RequireTaskbarRegistration) {
    $registeredTaskbarPaths = Get-TaskbandResolvedPathsFromCurrentUser
    $taskbarRegistered = Test-TaskbandPathRegistered -ExpectedPath $taskbarShortcutPath -ResolvedPaths $registeredTaskbarPaths
    $taskbarPlan = Get-TaskbarShortcutSyncPlan `
        -PinPath $taskbarShortcutPath `
        -PinRegistered $taskbarRegistered `
        -ShortcutFileExists (Test-Path -LiteralPath $taskbarShortcutPath -PathType Leaf)
    if (-not $taskbarPlan.Allowed) {
        $registered = if ($registeredTaskbarPaths.Count -eq 0) { '<none>' } else { $registeredTaskbarPaths -join '; ' }
        throw "$($taskbarPlan.Reason) Taskband resolved paths: $registered"
    }
} else {
    New-Item -ItemType Directory -Path $taskbarDirectory -Force | Out-Null
}

$shortcutDirectories = @(
    [Environment]::GetFolderPath("Desktop"),
    (Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs")
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

# The registered Taskbar .lnk is updated in place. Never delete, recreate, or
# attempt to pin it via registry/shell mutation; Windows owns Taskband state.
$taskbarShortcut = $shell.CreateShortcut($taskbarShortcutPath)
$taskbarShortcut.TargetPath = $target
$taskbarShortcut.Arguments = $arguments
$taskbarShortcut.WorkingDirectory = $runtimeRoot
$taskbarShortcut.IconLocation = "$icon,0"
$taskbarShortcut.Description = if ([string]::IsNullOrWhiteSpace($Description)) {
    "$($ShortcutName -replace '\.lnk$','')（管理员启动，自动使用最新构建）"
} else { $Description }
$taskbarShortcut.WindowStyle = 1
$taskbarShortcut.Save()
$updated.Add($taskbarShortcutPath)

# Repair the pre-channel generic Desktop shortcut when it still exists. Older
# installers left this shortcut pointing at the removed `outputs\Hearthstone`
# directory, which makes Windows Script Host try to execute a path with no
# extension. Keep the channel shortcuts isolated; this legacy entry always
# resolves to the stable launcher and is only repaired, never created.
$legacyShortcutPath = Join-Path ([Environment]::GetFolderPath("Desktop")) $LegacyShortcutName
$legacyRoot = [System.IO.Path]::GetFullPath($LegacyRuntimeRoot).TrimEnd('\')
$legacyLauncher = Join-Path $legacyRoot "launch-as-admin.vbs"
$legacyIcon = Join-Path $legacyRoot "hs-script.exe"
if ((Test-Path -LiteralPath $legacyShortcutPath -PathType Leaf) -and
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

$manifestPath = Join-Path $runtimeRoot 'deployment-manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw "Deployment manifest missing: $manifestPath" }
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$jarPath = Join-Path $runtimeRoot ([string]$manifest.appJar)
$jarExists = Test-Path -LiteralPath $jarPath -PathType Leaf
$actualJarHash = if ($jarExists) { (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant() } else { '' }
if ($RequireTaskbarRegistration) {
    $taskbarReadback = $shell.CreateShortcut($taskbarShortcutPath)
    $contract = Test-TaskbarShortcutContract ([pscustomobject]@{
        TaskbandRegistered = (Test-TaskbandPathRegistered -ExpectedPath $taskbarShortcutPath -ResolvedPaths (Get-TaskbandResolvedPathsFromCurrentUser))
        ShortcutExists = (Test-Path -LiteralPath $taskbarShortcutPath -PathType Leaf)
        TargetPath = [string]$taskbarReadback.TargetPath
        ExpectedWscriptPath = $target
        Arguments = [string]$taskbarReadback.Arguments
        ExpectedLauncherPath = $launcher
        WorkingDirectory = [string]$taskbarReadback.WorkingDirectory
        ExpectedRuntimeRoot = $runtimeRoot
        IconLocation = [string]$taskbarReadback.IconLocation
        ExpectedIconPath = $icon
        LauncherExists = (Test-Path -LiteralPath $launcher -PathType Leaf)
        IconExists = (Test-Path -LiteralPath $icon -PathType Leaf)
        ManifestJarExists = $jarExists
        ManifestDeploymentId = [string]$manifest.deploymentId
        ManifestJarSha256 = [string]$manifest.appJarSha256
        ActualJarSha256 = $actualJarHash
    })
    if (-not $contract.IsValid) {
        throw "Taskbar shortcut contract failed: $($contract.Failures -join ', ')"
    }
}

$iconCacheRefresh = Join-Path $env:SystemRoot "System32\ie4uinit.exe"
if (Test-Path -LiteralPath $iconCacheRefresh) {
    Start-Process -FilePath $iconCacheRefresh -ArgumentList "-show" -WindowStyle Hidden -Wait
}

foreach ($path in $updated) { Write-Output "UPDATED_SHORTCUT=$path" }
if ($RequireTaskbarRegistration) {
    Write-Output "TASKBAR_TASKBAND_REGISTRATION=verified path=$taskbarShortcutPath"
    Write-Output "TASKBAR_PIN_LAUNCH_VERIFICATION=operator-required deploymentId=$($manifest.deploymentId) instruction='Click the visible Beta taskbar icon and verify the app footer and fresh log identify this deployment.'"
    Write-Output "SHORTCUT_SYNC_COMPLETE launcher=$launcher taskbarSync=$($taskbarPlan.Action)"
} else {
    Write-Output "SHORTCUT_SYNC_COMPLETE launcher=$launcher"
}
