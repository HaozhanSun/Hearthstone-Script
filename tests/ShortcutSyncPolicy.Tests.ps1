$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $projectRoot 'shortcut-sync-policy.ps1')
$syncScriptPath = Join-Path $projectRoot 'sync-shortcuts.ps1'
$parseTokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($syncScriptPath, [ref]$parseTokens, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -ne 0) { throw "sync-shortcuts.ps1 parse failed: $($parseErrors[0].Message)" }
$syncSource = Get-Content -LiteralPath $syncScriptPath -Raw
if (-not $syncSource.Contains("`$taskbarDirectory = `$shortcutDirectories[2]") -or
    -not $syncSource.Contains('Remove-RedundantBetaTaskbarShortcuts') -or
    -not $syncSource.Contains('-Channel $Channel')) {
    throw 'Shortcut sync does not invoke Beta-only duplicate reconciliation on the Taskbar directory'
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("shortcut-policy-" + [guid]::NewGuid().ToString('N'))
$taskbar = Join-Path $tempRoot 'User Pinned/TaskBar'
New-Item -ItemType Directory -Path $taskbar -Force | Out-Null
try {
    $canonical = Join-Path $taskbar 'Hearthstone Script Beta.lnk'
    $duplicate = Join-Path $taskbar 'Hearthstone Script Beta (2).lnk'
    $duplicateThree = Join-Path $taskbar 'Hearthstone Script Beta (3).lnk'
    $stable = Join-Path $taskbar 'Hearthstone Script.lnk'
    $releaseCandidate = Join-Path $taskbar 'Hearthstone Script Release Candidate.lnk'
    foreach ($path in @($canonical, $duplicate, $duplicateThree, $stable, $releaseCandidate)) {
        [System.IO.File]::WriteAllText($path, 'shortcut-fixture')
    }

    $removed = @(Remove-RedundantBetaTaskbarShortcuts `
        -Directory $taskbar `
        -Channel beta `
        -ShortcutName 'Hearthstone Script Beta.lnk')
    if ($removed.Count -ne 2 -or
        (Test-Path -LiteralPath $canonical) -ne $true -or
        (Test-Path -LiteralPath $duplicate) -or
        (Test-Path -LiteralPath $duplicateThree) -or
        (Test-Path -LiteralPath $stable) -ne $true -or
        (Test-Path -LiteralPath $releaseCandidate) -ne $true) {
        throw 'Beta reconciliation must keep the canonical pin, remove numbered Beta duplicates, and preserve Stable/RC pins'
    }

    $secondPass = @(Remove-RedundantBetaTaskbarShortcuts `
        -Directory $taskbar `
        -Channel beta `
        -ShortcutName 'Hearthstone Script Beta.lnk')
    if ($secondPass.Count -ne 0 -or -not (Test-Path -LiteralPath $canonical)) {
        throw 'Beta taskbar reconciliation is not idempotent'
    }

    $nonBetaDuplicate = Join-Path $taskbar 'Hearthstone Script Beta (4).lnk'
    [System.IO.File]::WriteAllText($nonBetaDuplicate, 'shortcut-fixture')
    $stablePass = @(Remove-RedundantBetaTaskbarShortcuts `
        -Directory $taskbar `
        -Channel stable `
        -ShortcutName 'Hearthstone Script.lnk')
    $rcPass = @(Remove-RedundantBetaTaskbarShortcuts `
        -Directory $taskbar `
        -Channel release-candidate `
        -ShortcutName 'Hearthstone Script Release Candidate.lnk')
    if ($stablePass.Count -ne 0 -or $rcPass.Count -ne 0 -or -not (Test-Path -LiteralPath $nonBetaDuplicate)) {
        throw 'Stable/RC sync must not reconcile Beta taskbar pins'
    }

    $missingCanonicalDirectory = Join-Path $tempRoot 'missing-canonical'
    New-Item -ItemType Directory -Path $missingCanonicalDirectory -Force | Out-Null
    $orphan = Join-Path $missingCanonicalDirectory 'Hearthstone Script Beta (2).lnk'
    [System.IO.File]::WriteAllText($orphan, 'shortcut-fixture')
    try {
        Remove-RedundantBetaTaskbarShortcuts `
            -Directory $missingCanonicalDirectory `
            -Channel beta `
            -ShortcutName 'Hearthstone Script Beta.lnk' | Out-Null
        throw 'Reconciliation must not delete a Beta pin when the canonical pin is absent'
    } catch {
        if ($_.Exception.Message -eq 'Reconciliation must not delete a Beta pin when the canonical pin is absent') { throw }
    }
    if (-not (Test-Path -LiteralPath $orphan)) {
        throw 'A numbered Beta pin was removed without its canonical replacement'
    }
} finally {
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}

Write-Output 'ShortcutSyncPolicy.Tests: PASS (canonical Beta pin wins, numbered duplicates removed, Stable/RC preserved, idempotent)'
