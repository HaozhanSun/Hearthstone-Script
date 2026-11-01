function Remove-BetaTaskbarDuplicateShortcuts {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)] [string]$TaskbarDirectory,
        [Parameter(Mandatory = $true)] [ValidateSet('stable', 'beta', 'release-candidate')] [string]$Channel,
        [Parameter(Mandatory = $true)] [string]$ShortcutName,
        [Parameter(Mandatory = $true)] [string]$LauncherPath
    )

    $canonicalName = 'Hearthstone Script Beta.lnk'
    if ($Channel -ne 'beta' -or
        -not [string]::Equals($ShortcutName, $canonicalName, [System.StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $TaskbarDirectory -PathType Container)) {
        return @()
    }

    $launcherFullPath = [System.IO.Path]::GetFullPath($LauncherPath)
    $duplicatePattern = '^' + [regex]::Escape([System.IO.Path]::GetFileNameWithoutExtension($canonicalName)) +
        ' \(\d+\)\.lnk$'
    $shell = New-Object -ComObject WScript.Shell
    $removed = [System.Collections.Generic.List[string]]::new()
    foreach ($candidate in Get-ChildItem -LiteralPath $TaskbarDirectory -File -Filter '*.lnk') {
        if ($candidate.Name -notmatch $duplicatePattern) { continue }

        try {
            $shortcut = $shell.CreateShortcut($candidate.FullName)
            $candidateTarget = [System.IO.Path]::GetFullPath($shortcut.Arguments.Trim('"'))
            if (-not [string]::Equals(
                    $candidateTarget,
                    $launcherFullPath,
                    [System.StringComparison]::OrdinalIgnoreCase)) {
                continue
            }
            Remove-Item -LiteralPath $candidate.FullName -Force
            $removed.Add($candidate.FullName)
        } catch {
            # Unreadable or malformed shortcuts are preserved; deletion is
            # allowed only when the duplicate name and Beta launcher target
            # are both verified.
            continue
        }
    }
    return @($removed)
}
