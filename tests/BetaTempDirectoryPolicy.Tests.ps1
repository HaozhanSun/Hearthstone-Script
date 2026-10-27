$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\hs-script-app\src\main\resources\bat\beta-temp-policy.ps1')

function Assert-Equal($Expected, $Actual, [string]$Label) {
    if ($Expected -cne $Actual) { throw "$Label`: expected=[$Expected] actual=[$Actual]" }
}

function Assert-Throws([scriptblock]$Action, [string]$Label) {
    try { & $Action } catch { return }
    throw "$Label`: expected an exception"
}

$volumes = @(
    [pscustomobject]@{ DeviceID = 'C:'; FreeSpace = 0L; DriveType = 3 },
    [pscustomobject]@{ DeviceID = 'D:'; FreeSpace = 716000000000L; DriveType = 3 },
    [pscustomobject]@{ DeviceID = 'E:'; FreeSpace = 900000000000L; DriveType = 2 }
)

$betaPath = Get-HsScriptBetaJavaTempDirectory -Channel 'beta' -RuntimeKey '1234ABCD' -RuntimeDirectory 'C:\BetaRuntime' -FixedVolumes $volumes
Assert-Equal 'D:\HearthstoneScriptTemp\Beta\1234ABCD' $betaPath 'Beta selects a fixed volume with free space and scopes by runtime key'
Assert-Equal '-Djava.io.tmpdir="D:\HearthstoneScriptTemp\Beta\1234ABCD"' `
    (Get-HsScriptJavaTempJvmArgument -Channel 'beta' -Directory $betaPath) 'Beta gets the JVM temp override'

$rcPath = Get-HsScriptBetaJavaTempDirectory -Channel 'release-candidate' -RuntimeKey '1234ABCD' -RuntimeDirectory 'C:\BetaRuntime' -FixedVolumes $volumes
Assert-Equal $null $rcPath 'RC keeps the JVM default temp directory'
Assert-Equal '' (Get-HsScriptJavaTempJvmArgument -Channel 'release-candidate' -Directory '') 'RC gets no temp override'

$tempOnNonRuntimeVolume = Get-HsScriptBetaJavaTempDirectory -Channel 'beta' -RuntimeKey '1234ABCD' -RuntimeDirectory 'C:\BetaRuntime' -FixedVolumes @(
    [pscustomobject]@{ DeviceID = 'C:'; FreeSpace = 100GB; DriveType = 3 },
    [pscustomobject]@{ DeviceID = 'D:'; FreeSpace = 2GB; DriveType = 3 }
)
Assert-Equal 'D:\HearthstoneScriptTemp\Beta\1234ABCD' $tempOnNonRuntimeVolume 'Beta JVM temp never reuses the runtime volume when C has more free space'

Assert-Throws {
    Get-HsScriptBetaJavaTempDirectory -Channel 'beta' -RuntimeKey '1234ABCD' `
        -RuntimeDirectory 'C:\BetaRuntime' `
        -FixedVolumes @([pscustomobject]@{ DeviceID = 'C:'; FreeSpace = 0L; DriveType = 3 })
} 'Beta fails closed when all fixed volumes lack reserved headroom'

$artifact = Get-HsScriptBetaArtifactRoot -Channel 'beta' -RuntimeKey 'abc123' -RuntimeDirectory 'C:\BetaRuntime' -FixedVolumes @(
    [pscustomobject]@{ DeviceID = 'C:'; FreeSpace = 1GB; DriveType = 3 },
    [pscustomobject]@{ DeviceID = 'D:'; FreeSpace = 700GB; DriveType = 3 }
)
Assert-Equal 'D:\HearthstoneScriptArtifacts\Beta\abc123' $artifact 'Beta artifacts select a sufficiently free fixed volume'

$fallback = Get-HsScriptBetaArtifactRoot -Channel 'beta' -RuntimeKey 'abc123' -RuntimeDirectory 'C:\BetaRuntime' -FixedVolumes @(
    [pscustomobject]@{ DeviceID = 'C:'; FreeSpace = 6GB; DriveType = 3 },
    [pscustomobject]@{ DeviceID = 'D:'; FreeSpace = 8GB; DriveType = 3 }
)
Assert-Equal 'D:\HearthstoneScriptArtifacts\Beta\abc123' $fallback 'Beta artifacts use the non-runtime volume when only fallback headroom is available'

$none = Get-HsScriptBetaArtifactRoot -Channel 'beta' -RuntimeKey 'abc123' -RuntimeDirectory 'C:\BetaRuntime' -FixedVolumes @(
    [pscustomobject]@{ DeviceID = 'C:'; FreeSpace = 1GB; DriveType = 3 },
    [pscustomobject]@{ DeviceID = 'D:'; FreeSpace = 4GB; DriveType = 3 }
)
Assert-Equal $null $none 'Beta artifacts disable new evidence when no volume clears thresholds'
Assert-Equal $null (Get-HsScriptBetaArtifactRoot -Channel 'release-candidate' -RuntimeKey 'abc123' -RuntimeDirectory 'C:\BetaRuntime' -FixedVolumes @()) 'RC receives no Beta artifact root'

$logDirectory = Get-HsScriptBetaLogDirectory -Channel 'beta' -RuntimeKey 'abc123' -RuntimeDirectory 'C:\BetaRuntime' -FixedVolumes @(
    [pscustomobject]@{ DeviceID = 'C:'; FreeSpace = 700GB; DriveType = 3 },
    [pscustomobject]@{ DeviceID = 'D:'; FreeSpace = 20GB; DriveType = 3 }
)
Assert-Equal 'D:\HearthstoneScriptLogs\Beta\abc123' $logDirectory 'Beta logs stay on a non-runtime volume even when C has more free space'

Assert-Throws {
    Get-HsScriptBetaLogDirectory -Channel 'beta' -RuntimeKey 'abc123' -RuntimeDirectory 'C:\BetaRuntime' -FixedVolumes @(
        [pscustomobject]@{ DeviceID = 'C:'; FreeSpace = 700GB; DriveType = 3 },
        [pscustomobject]@{ DeviceID = 'D:'; FreeSpace = 4GB; DriveType = 3 }
    )
} 'Beta launcher fails closed when no non-runtime volume has log reserve'
Assert-Equal $null (Get-HsScriptBetaLogDirectory -Channel 'release-candidate' -RuntimeKey 'abc123' -RuntimeDirectory 'C:\BetaRuntime' -FixedVolumes @()) 'RC gets no Beta log override'

$launcher = Get-Content (Join-Path $PSScriptRoot '..\hs-script-app\src\main\resources\bat\launch-newest-as-admin.ps1') -Raw
if (-not $launcher.Contains('Get-HsScriptBetaLogDirectory') -or
    -not $launcher.Contains('-Dhs.script.log.dir=') -or
    -not $launcher.Contains('-Dhs.script.guardian-state.path=')) {
    throw 'Beta launcher does not wire the selected log, guardian, and selection root into the JVM'
}
Assert-Equal $true ((Get-Content (Join-Path $PSScriptRoot '..\hs-script-app\src\main\resources\logback.xml') -Raw).Contains('${hs.script.log.dir:-log}')) 'Logback emits to the selected Beta app log directory'

Write-Output 'BetaTempDirectoryPolicy.Tests.ps1: 12 cases passed'
