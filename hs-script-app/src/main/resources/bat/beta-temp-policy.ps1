$ErrorActionPreference = 'Stop'

function Get-HsScriptBetaJavaTempDirectory {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Channel,
        [Parameter(Mandatory = $true)][string]$RuntimeKey,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$FixedVolumes,
        [long]$MinimumFreeBytes = 1GB
    )

    if ($Channel.Trim().ToLowerInvariant() -ne 'beta') { return $null }

    $safeKey = $RuntimeKey -replace '[^A-Za-z0-9_-]', ''
    if ([string]::IsNullOrWhiteSpace($safeKey)) { throw 'A stable runtime key is required for Beta temp isolation' }

    $selected = $FixedVolumes |
        Where-Object {
            [string]$_.DeviceID -match '^[A-Za-z]:$' -and
            [long]$_.FreeSpace -ge $MinimumFreeBytes -and
            ([int]$_.DriveType -eq 3 -or $null -eq $_.DriveType)
        } |
        Sort-Object -Property @{ Expression = { [long]$_.FreeSpace }; Descending = $true } |
        Select-Object -First 1

    if ($null -eq $selected) {
        throw "Beta requires a fixed volume with at least $MinimumFreeBytes free bytes for JVM temporary files"
    }

    $driveRoot = ([string]$selected.DeviceID).TrimEnd('\') + '\'
    return Join-Path $driveRoot (Join-Path 'HearthstoneScriptTemp' (Join-Path 'Beta' $safeKey))
}

function Get-HsScriptJavaTempJvmArgument {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Channel,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Directory
    )

    if ($Channel.Trim().ToLowerInvariant() -ne 'beta') { return '' }
    if ([string]::IsNullOrWhiteSpace($Directory)) { throw 'Beta JVM temp directory is required' }
    return '-Djava.io.tmpdir="' + $Directory + '"'
}

function Get-HsScriptBetaArtifactRoot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Channel,
        [Parameter(Mandatory = $true)][string]$RuntimeKey,
        [Parameter(Mandatory = $true)][string]$RuntimeDirectory,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$FixedVolumes,
        [long]$MinimumFreeBytes = 20GB,
        [long]$FallbackMinimumFreeBytes = 5GB
    )

    if ($Channel.Trim().ToLowerInvariant() -ne 'beta') { return $null }
    $safeKey = $RuntimeKey -replace '[^A-Za-z0-9_-]', ''
    if ([string]::IsNullOrWhiteSpace($safeKey)) { throw 'A stable runtime key is required for Beta artifact isolation' }

    $selected = $FixedVolumes |
        Where-Object {
            [string]$_.DeviceID -match '^[A-Za-z]:$' -and
            [long]$_.FreeSpace -ge $MinimumFreeBytes -and
            ([int]$_.DriveType -eq 3 -or $null -eq $_.DriveType)
        } |
        Sort-Object -Property @{ Expression = { [long]$_.FreeSpace }; Descending = $true } |
        Select-Object -First 1
    if ($null -ne $selected) {
        $driveRoot = ([string]$selected.DeviceID).TrimEnd('\') + '\'
        return Join-Path $driveRoot (Join-Path 'HearthstoneScriptArtifacts' (Join-Path 'Beta' $safeKey))
    }

    $runtimeDrive = ([System.IO.Path]::GetPathRoot([System.IO.Path]::GetFullPath($RuntimeDirectory))).TrimEnd('\')
    $fallback = $FixedVolumes | Where-Object {
        [string]$_.DeviceID -eq $runtimeDrive -and
        [long]$_.FreeSpace -ge $FallbackMinimumFreeBytes
    } | Select-Object -First 1
    if ($null -ne $fallback) {
        return Join-Path $RuntimeDirectory (Join-Path 'log' 'beta-artifacts')
    }
    return $null
}
