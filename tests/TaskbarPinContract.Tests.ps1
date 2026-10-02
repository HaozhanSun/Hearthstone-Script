$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $root 'taskbar-pin-contract.ps1')

function Assert-True([bool]$Condition, [string]$Label) {
    if (-not $Condition) { throw "ASSERT TRUE failed: $Label" }
}
function Assert-False([bool]$Condition, [string]$Label) {
    if ($Condition) { throw "ASSERT FALSE failed: $Label" }
}
function Assert-Equal([string]$Expected, [string]$Actual, [string]$Label) {
    if ($Expected -cne $Actual) { throw "ASSERT EQUAL failed: $Label expected '$Expected' got '$Actual'" }
}

$canonical = 'C:\Users\test\AppData\Roaming\Microsoft\Internet Explorer\Quick Launch\User Pinned\TaskBar\Hearthstone Script Beta.lnk'
$archived = 'C:\Users\test\AppData\Local\Packages\OpenAI.Codex\LocalCache\Roaming\Codex\shortcut-archive\Hearthstone Script Beta (2)-archived-20261001-103236.lnk'
$resolvedBlob = [System.Text.Encoding]::Unicode.GetBytes("TaskBar`0$canonical`0$archived`0")
$resolvedPaths = ConvertFrom-TaskbandResolvedLinkPaths -Bytes $resolvedBlob
Assert-True (Test-TaskbandPathRegistered -ExpectedPath $canonical -ResolvedPaths $resolvedPaths) 'canonical Taskband path accepted'
Assert-False (Test-TaskbandPathRegistered -ExpectedPath ($canonical + '.other') -ResolvedPaths $resolvedPaths) 'unrelated Taskband path rejected'
$archivedOnlyBlob = [System.Text.Encoding]::ASCII.GetBytes("TaskBar`0$archived`0")
$archivedOnlyPaths = ConvertFrom-TaskbandResolvedLinkPaths -Bytes $archivedOnlyBlob
Assert-False (Test-TaskbandPathRegistered -ExpectedPath $canonical -ResolvedPaths $archivedOnlyPaths) 'archived Taskband pin does not register canonical shortcut'
Assert-True (Test-TaskbandPathRegistered -ExpectedPath $archived -ResolvedPaths $archivedOnlyPaths) 'archived path remains separately identifiable as a stale Taskband item'

$missingPin = Get-TaskbarShortcutSyncPlan -PinPath $canonical -PinRegistered $false -ShortcutFileExists $true
Assert-False $missingPin.Allowed 'existing file but no Taskband registration must fail'
Assert-Equal 'FAIL_MANUAL_PIN_REQUIRED' $missingPin.Action 'missing pin action'
Assert-True ($missingPin.Reason -match 'Pin this channel''s launcher through Windows taskbar UI') 'missing pin has actionable instruction'

$missingFile = Get-TaskbarShortcutSyncPlan -PinPath $canonical -PinRegistered $true -ShortcutFileExists $false
Assert-False $missingFile.Allowed 'registered path with missing file must fail'
Assert-Equal 'FAIL_PIN_FILE_MISSING' $missingFile.Action 'missing file action'

$updatePlan = Get-TaskbarShortcutSyncPlan -PinPath $canonical -PinRegistered $true -ShortcutFileExists $true
Assert-True $updatePlan.Allowed 'registered existing pin may be updated'
Assert-Equal 'UPDATE_REGISTERED_SHORTCUT_IN_PLACE' $updatePlan.Action 'pin sync updates in place only'

$evidence = [pscustomobject]@{
    TaskbandRegistered = $true
    ShortcutExists = $true
    TargetPath = 'C:\Windows\System32\wscript.exe'
    ExpectedWscriptPath = 'C:\Windows\System32\wscript.exe'
    Arguments = '"C:\Beta\launch-as-admin.vbs"'
    ExpectedLauncherPath = 'C:\Beta\launch-as-admin.vbs'
    WorkingDirectory = 'C:\Beta'
    ExpectedRuntimeRoot = 'C:\Beta'
    IconLocation = 'C:\Beta\hs-script-beta.ico,0'
    ExpectedIconPath = 'C:\Beta\hs-script-beta.ico'
    LauncherExists = $true
    IconExists = $true
    ManifestJarExists = $true
    ManifestDeploymentId = 'hs-script_v4.16.517.jar|abc123'
    ManifestJarSha256 = 'aabbcc'
    ActualJarSha256 = 'aabbcc'
}
Assert-True (Test-TaskbarShortcutContract $evidence).IsValid 'valid launcher/manifest contract passes'

$staleTarget = $evidence.PSObject.Copy()
$staleTarget.TargetPath = 'C:\Old\wscript.exe'
Assert-True ((Test-TaskbarShortcutContract $staleTarget).Failures -contains 'shortcut-target-stale') 'stale shortcut target is detected'

$staleIcon = $evidence.PSObject.Copy()
$staleIcon.IconLocation = 'C:\Old\hs-script-beta.ico,0'
Assert-True ((Test-TaskbarShortcutContract $staleIcon).Failures -contains 'icon-path-stale') 'stale icon path is detected'

$missingIcon = $evidence.PSObject.Copy()
$missingIcon.IconExists = $false
Assert-True ((Test-TaskbarShortcutContract $missingIcon).Failures -contains 'icon-file-missing') 'missing icon is detected'

$wrongManifest = $evidence.PSObject.Copy()
$wrongManifest.ActualJarSha256 = 'ddeeff'
Assert-True ((Test-TaskbarShortcutContract $wrongManifest).Failures -contains 'manifest-jar-hash-mismatch') 'launcher manifest identity/hash mismatch is detected'

$unregistered = $evidence.PSObject.Copy()
$unregistered.TaskbandRegistered = $false
Assert-True ((Test-TaskbarShortcutContract $unregistered).Failures -contains 'taskband-not-registered') 'Taskband archive/canonical mismatch is detected'
Assert-Equal 'shortcut-to-stable-launcher-and-manifest-verified; actual taskbar click remains an operator UI check' `
    (Test-TaskbarShortcutContract $evidence).LaunchabilityScope 'no false claim of actual UI launch'

$syncScript = [System.IO.File]::ReadAllText((Join-Path $root 'sync-shortcuts.ps1'))
if ($syncScript -match '(?im)^\s*(Remove-Item|Move-Item|Rename-Item)\b') {
    throw 'Shortcut synchronization must not remove, move, or rename a registered taskbar pin'
}
if ($syncScript -notmatch 'Get-TaskbarShortcutSyncPlan' -or
    $syncScript -notmatch '\$taskbarShortcut\.Save\(\)') {
    throw 'Taskbar pin must be updated in place only after its Taskband registration is checked'
}
$requirementBlock = [regex]::Match($syncScript, '(?s)if \(\$RequireTaskbarRegistration\)\s*\{(?<body>.*?)\}\s*else\s*\{')
if (-not $requirementBlock.Success -or $requirementBlock.Groups['body'].Value -match 'New-Item') {
    throw 'Beta taskbar synchronization must not create a shortcut and mistake it for a user pin'
}

$deployScript = [System.IO.File]::ReadAllText((Join-Path $root 'build-and-deploy.ps1'))
$pinPreflight = $deployScript.IndexOf('$taskbarPinPlan = Get-TaskbarShortcutSyncPlan', [System.StringComparison]::Ordinal)
$runtimeCreate = $deployScript.IndexOf('New-Item -ItemType Directory -Path $runtimeRoot', [System.StringComparison]::Ordinal)
$processStop = $deployScript.IndexOf('Stop-ManagedHsScriptProcesses', [System.StringComparison]::Ordinal)
if ($pinPreflight -lt 0 -or $runtimeCreate -lt 0 -or $processStop -lt 0 -or
    $pinPreflight -gt $runtimeCreate -or $pinPreflight -gt $processStop) {
    throw 'Taskband pin preflight must fail before runtime creation or process changes'
}
if ($deployScript -notmatch "(?s)if \(\`$Channel -eq 'beta'\) \{\s*Write-Output 'BUILD_AND_DEPLOY_STRUCTURAL_CHECKS_COMPLETE'\s*Write-Output 'TASKBAR_PIN_LAUNCH_VERIFICATION_PENDING") {
    throw 'Beta deployment output must leave the actual taskbar-pin launch verification explicitly pending'
}
if ($deployScript -notmatch "-RequireTaskbarRegistration") {
    throw 'The Beta release path must require Taskband registration during shortcut synchronization'
}

Write-Output 'TaskbarPinContract.Tests: PASS (Taskband registration, missing pin/file, stale target, icon, launcher/manifest identity, in-place-only sync plan, preflight ordering, no false UI-complete claim)'
