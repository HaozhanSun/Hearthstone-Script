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

$betaPath = Get-HsScriptBetaJavaTempDirectory -Channel 'beta' -RuntimeKey '1234ABCD' -FixedVolumes $volumes
Assert-Equal 'D:\HearthstoneScriptTemp\Beta\1234ABCD' $betaPath 'Beta selects a fixed volume with free space and scopes by runtime key'
Assert-Equal '-Djava.io.tmpdir="D:\HearthstoneScriptTemp\Beta\1234ABCD"' `
    (Get-HsScriptJavaTempJvmArgument -Channel 'beta' -Directory $betaPath) 'Beta gets the JVM temp override'

$rcPath = Get-HsScriptBetaJavaTempDirectory -Channel 'release-candidate' -RuntimeKey '1234ABCD' -FixedVolumes $volumes
Assert-Equal $null $rcPath 'RC keeps the JVM default temp directory'
Assert-Equal '' (Get-HsScriptJavaTempJvmArgument -Channel 'release-candidate' -Directory '') 'RC gets no temp override'

Assert-Throws {
    Get-HsScriptBetaJavaTempDirectory -Channel 'beta' -RuntimeKey '1234ABCD' `
        -FixedVolumes @([pscustomobject]@{ DeviceID = 'C:'; FreeSpace = 0L; DriveType = 3 })
} 'Beta fails closed when all fixed volumes lack reserved headroom'

Write-Output 'BetaTempDirectoryPolicy.Tests.ps1: 3 cases passed'
