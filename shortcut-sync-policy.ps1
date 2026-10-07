function Remove-RedundantBetaTaskbarShortcuts {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Directory,
        [Parameter(Mandatory = $true)]
        [ValidateSet('stable', 'beta', 'release-candidate')]
        [string]$Channel,
        [Parameter(Mandatory = $true)]
        [string]$ShortcutName
    )

    if ($Channel -ne 'beta') { return @() }
    $directoryPath = [System.IO.Path]::GetFullPath($Directory)
    $canonicalPath = Join-Path $directoryPath $ShortcutName
    if (-not (Test-Path -LiteralPath $canonicalPath -PathType Leaf)) {
        throw "Canonical Beta taskbar shortcut must exist before duplicate cleanup: $canonicalPath"
    }

    $baseName = [System.IO.Path]::GetFileNameWithoutExtension($ShortcutName)
    $duplicatePattern = '^' + [regex]::Escape($baseName) + ' \(\d+\)\.lnk$'
    $removed = [System.Collections.Generic.List[string]]::new()
    foreach ($candidate in Get-ChildItem -LiteralPath $directoryPath -File -Filter '*.lnk') {
        if ($candidate.Name -match $duplicatePattern -and
            -not [string]::Equals($candidate.FullName, $canonicalPath, [StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $candidate.FullName -Force
            $removed.Add($candidate.FullName)
        }
    }
    return $removed.ToArray()
}
