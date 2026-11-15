$ErrorActionPreference = 'Stop'

# Keep the Debug Run watchdog on the same channel-scoped storage policy as the
# canonical Beta launcher.  This file is deliberately a script rather than a
# module so the packaged runner and launcher can dot-source it without adding
# another PowerShell module-resolution dependency.
$betaTempPolicyPath = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'beta-temp-policy.ps1'
if (-not (Get-Command Get-HsScriptDiagnosticLogDirectory -ErrorAction SilentlyContinue)) {
    if (-not (Test-Path -LiteralPath $betaTempPolicyPath -PathType Leaf)) {
        throw "The Beta diagnostic storage policy was not found: $betaTempPolicyPath"
    }
    . $betaTempPolicyPath
}

function Get-HsScriptRuntimeKey {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$RuntimeDirectory
    )

    $runtimeHasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $runtimeHashBytes = $runtimeHasher.ComputeHash(
            [System.Text.Encoding]::UTF8.GetBytes(
                [System.IO.Path]::GetFullPath($RuntimeDirectory).ToLowerInvariant()
            )
        )
        return ([System.BitConverter]::ToString($runtimeHashBytes)).Replace('-', '').Substring(0, 24)
    } finally {
        $runtimeHasher.Dispose()
    }
}

function Get-HsScriptExternalFixedVolumes {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$FixedVolumes,
        [Parameter(Mandatory = $true)][string]$RuntimeDirectory
    )

    $runtimeDrive = ([System.IO.Path]::GetPathRoot([System.IO.Path]::GetFullPath($RuntimeDirectory))).TrimEnd('\')
    return @($FixedVolumes | Where-Object {
        [string]$_.DeviceID -match '^[A-Za-z]:$' -and
        [string]$_.DeviceID -ne $runtimeDrive -and
        ([int]$_.DriveType -eq 3 -or $null -eq $_.DriveType)
    })
}

function Resolve-HsScriptDebugRunDiagnosticRoots {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Channel,
        [Parameter(Mandatory = $true)][string]$RuntimeDirectory,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$FixedVolumes,
        [long]$MinimumLogFreeBytes = 5GB,
        [long]$MinimumArtifactFreeBytes = 20GB,
        [long]$MinimumArtifactFallbackFreeBytes = 5GB,
        [string]$DiagnosticArchiveRoot = 'D:\HearthstoneScriptArchives'
    )

    $channelToken = $Channel.Trim().ToLowerInvariant()
    if ($channelToken -ne 'beta') {
        throw "Debug Run diagnostic roots require the beta channel; actual=$channelToken"
    }

    $runtimeKey = Get-HsScriptRuntimeKey -RuntimeDirectory $RuntimeDirectory
    $selectedLogRoot = Get-HsScriptDiagnosticLogDirectory `
        -Channel $channelToken `
        -RuntimeKey $runtimeKey `
        -RuntimeDirectory $RuntimeDirectory `
        -FixedVolumes $FixedVolumes `
        -MinimumFreeBytes $MinimumLogFreeBytes
    if ([string]::IsNullOrWhiteSpace([string]$selectedLogRoot)) {
        $externalFixedVolumes = @(Get-HsScriptExternalFixedVolumes `
            -FixedVolumes $FixedVolumes `
            -RuntimeDirectory $RuntimeDirectory)
        $reason = if ($externalFixedVolumes.Count -eq 0) {
            'no-external-log-directory-configured'
        } else {
            'configured-volume-below-reserve'
        }
        throw "BETA_LOG_DIRECTORY_UNAVAILABLE runtime=$RuntimeDirectory minimumFreeBytes=$MinimumLogFreeBytes reason=$reason externalFixedVolumeCount=$($externalFixedVolumes.Count)"
    }

    $diagnosticLogRoot = [System.IO.Path]::GetFullPath($selectedLogRoot)
    $javaTempDirectory = Get-HsScriptBetaJavaTempDirectory `
        -Channel $channelToken `
        -RuntimeKey $runtimeKey `
        -RuntimeDirectory $RuntimeDirectory `
        -FixedVolumes $FixedVolumes
    $artifactRoot = Get-HsScriptDiagnosticArtifactRoot `
        -Channel $channelToken `
        -RuntimeKey $runtimeKey `
        -RuntimeDirectory $RuntimeDirectory `
        -FixedVolumes $FixedVolumes `
        -MinimumFreeBytes $MinimumArtifactFreeBytes `
        -FallbackMinimumFreeBytes $MinimumArtifactFallbackFreeBytes

    return [pscustomobject]@{
        Channel = $channelToken
        RuntimeKey = $runtimeKey
        DiagnosticLogRoot = $diagnosticLogRoot
        AppLogDirectory = [System.IO.Path]::GetFullPath((Join-Path $diagnosticLogRoot 'app'))
        RuntimeLogDirectory = [System.IO.Path]::GetFullPath((Join-Path $diagnosticLogRoot 'runtime-log'))
        GuardianStatePath = [System.IO.Path]::GetFullPath((Join-Path $diagnosticLogRoot 'guardian-state.json'))
        DiagnosticArchiveRoot = [System.IO.Path]::GetFullPath($DiagnosticArchiveRoot)
        JavaTempDirectory = [System.IO.Path]::GetFullPath($javaTempDirectory)
        ArtifactRoot = if ([string]::IsNullOrWhiteSpace([string]$artifactRoot)) { $null } else { [System.IO.Path]::GetFullPath($artifactRoot) }
        ArtifactStorageDisabled = [string]::IsNullOrWhiteSpace([string]$artifactRoot)
    }
}

function Get-HsScriptDebugRunJvmDiagnosticArguments {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][psobject]$DiagnosticRoots
    )

    $arguments = @(
        "-Djava.io.tmpdir=$($DiagnosticRoots.JavaTempDirectory)",
        "-Dhs.script.log.dir=$($DiagnosticRoots.AppLogDirectory)",
        "-Dhs.script.external-runtime-log-root=$($DiagnosticRoots.RuntimeLogDirectory)",
        "-Dhs.script.beta-runtime-log-root=$($DiagnosticRoots.RuntimeLogDirectory)",
        "-Dhs.script.guardian-state.path=$($DiagnosticRoots.GuardianStatePath)",
        "-Dhs.script.diagnostic-archive-root=$($DiagnosticRoots.DiagnosticArchiveRoot)"
    )
    if ($DiagnosticRoots.ArtifactStorageDisabled) {
        $arguments += '-Dhs.script.artifact-storage.disabled=true'
    } else {
        $artifactRoot = [string]$DiagnosticRoots.ArtifactRoot
        $arguments += @(
            "-Dhs.script.artifact-root=$artifactRoot",
            "-Dhs.script.mcts-replay.dir=$(Join-Path $artifactRoot 'mcts-replay')",
            "-Dhs.script.unknown-state.dir=$(Join-Path $artifactRoot 'unknown-states')",
            "-Dhs.script.result-screenshot.dir=$(Join-Path $artifactRoot 'game-results')",
            "-Dhs.script.mulligan-screenshot.dir=$(Join-Path $artifactRoot 'mulligan')"
        )
    }
    return ,$arguments
}
