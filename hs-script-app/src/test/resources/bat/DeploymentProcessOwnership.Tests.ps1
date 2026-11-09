$ErrorActionPreference = 'Stop'

$modulePath = Join-Path $PSScriptRoot '..\..\..\main\resources\bat\deployment-contract.ps1'
. ([System.IO.Path]::GetFullPath($modulePath))

function Assert-Equal($Expected, $Actual, [string]$Message) {
    if ($Expected -ne $Actual) {
        throw "ASSERT_EQUAL failed: $Message expected=[$Expected] actual=[$Actual]"
    }
}

$currentRoot = 'C:\fixtures\Hearthstone Script Release Candidate'
$foreignRoot = 'C:\fixtures\Hearthstone Script Beta'
$currentStart = '2026-10-25T23:00:00.0000000Z'
$foreignStart = '2026-10-25T23:01:00.0000000Z'
$identities = @{
    1101 = [pscustomobject]@{ ProcessId = 1101; ProcessStartTimeUtc = $currentStart; ExecutablePath = 'C:\Java\bin\javaw.exe' }
    2202 = [pscustomobject]@{ ProcessId = 2202; ProcessStartTimeUtc = $foreignStart; ExecutablePath = 'C:\Java\bin\javaw.exe' }
    3303 = [pscustomobject]@{ ProcessId = 3303; ProcessStartTimeUtc = '2026-10-25T23:03:00.0000000Z'; ExecutablePath = 'C:\Java\bin\javaw.exe' }
}
$identityProvider = { param($processId) $identities[[int]$processId] }

# Windows PowerShell's Process provider can expose Process.Path as empty while
# MainModule.FileName is available. Exercise the concrete path resolver with a
# Process-shaped result that deliberately has no Path property.
$processWithoutPath = [pscustomobject]@{
    MainModule = [pscustomobject]@{ FileName = 'C:\Java\bin\javaw.exe' }
}
$resolvedExecutable = Get-HsScriptExecutablePath $processWithoutPath 9901 { param($className, $filter) throw 'CIM should not be needed when MainModule resolves' }
Assert-Equal 'C:\Java\bin\javaw.exe' $resolvedExecutable 'MainModule must resolve executable when Process.Path is absent'
$observedSelfStart = Get-HsScriptProcessStartTimeUtc $PID
if ([string]::IsNullOrWhiteSpace([string]$observedSelfStart)) { throw 'Process start-time-only resolver must return an independently readable timestamp' }
$null = [DateTimeOffset]::Parse([string]$observedSelfStart)

# If MainModule is inaccessible, CIM ExecutablePath is the fallback; an empty
# result must throw instead of becoming an unverified process identity.
$processWithUnreadableModule = [pscustomobject]@{ MainModule = $null }
$cimFallback = Get-HsScriptExecutablePath $processWithUnreadableModule 9902 { param($className, $filter) [pscustomobject]@{ ExecutablePath = 'C:\Java\bin\java.exe' } }
Assert-Equal 'C:\Java\bin\java.exe' $cimFallback 'CIM ExecutablePath must resolve inaccessible MainModule'
$nativeFallback = Get-HsScriptExecutablePath $processWithUnreadableModule 9903 `
    { param($className, $filter) [pscustomobject]@{ ExecutablePath = '' } } `
    { param($processId) 'C:\Java\bin\javaw.exe' }
Assert-Equal 'C:\Java\bin\javaw.exe' $nativeFallback 'QueryFullProcessImageNameW fallback must resolve a path hidden from MainModule and CIM'
$emptyCimFailedClosed = $false
try {
    $null = Get-HsScriptExecutablePath $processWithUnreadableModule 9904 `
        { param($className, $filter) [pscustomobject]@{ ExecutablePath = '' } } `
        { param($processId) throw 'PROCESS_QUERY_LIMITED_INFORMATION denied' }
} catch { $emptyCimFailedClosed = $true }
Assert-Equal $true $emptyCimFailedClosed 'MainModule/CIM/API path lookup failure must fail closed'

$currentMarker = [pscustomobject]@{
    RuntimeRoot = $currentRoot; ReleaseChannel = 'release-candidate'; ProcessId = 1101
    ProcessStartTimeUtc = $currentStart; ExecutablePath = 'C:\Java\bin\javaw.exe'
}
$foreignMarker = [pscustomobject]@{
    RuntimeRoot = $foreignRoot; ReleaseChannel = 'beta'; ProcessId = 2202
    ProcessStartTimeUtc = $foreignStart; ExecutablePath = 'C:\Java\bin\javaw.exe'
}

# PowerShell 7 coerces an ISO-8601 JSON timestamp into DateTime.  The contract
# must preserve the raw JSON token rather than stringify that coerced object as
# local time, otherwise a live foreign RC/Beta marker is misclassified stale.
$markerFixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hs-marker-timezone-" + [guid]::NewGuid().ToString('N'))
$fixtureBetaRoot = Join-Path $markerFixtureRoot 'Hearthstone Script Beta'
$fixtureRcRoot = Join-Path $markerFixtureRoot 'Hearthstone Script Release Candidate'
New-Item -ItemType Directory -Path $fixtureRcRoot, $fixtureBetaRoot -Force | Out-Null
try {
    $fixtureTimestamp = '2026-11-08T07:09:37.5057863Z'
    [System.IO.File]::WriteAllText((Join-Path $fixtureBetaRoot 'release-channel.json'), '{"channel":"beta","runtimeDirectoryName":"Hearthstone Script Beta"}')
    [System.IO.File]::WriteAllText((Join-Path $fixtureRcRoot 'release-channel.json'), '{"channel":"release-candidate","runtimeDirectoryName":"Hearthstone Script Release Candidate"}')
    $foreignMarkerJson = (@{
        schema = 1
        runtimeRoot = $fixtureRcRoot
        runtimeDirectoryName = 'Hearthstone Script Release Candidate'
        releaseChannel = 'release-candidate'
        processId = 2202
        processStartTimeUtc = $fixtureTimestamp
        executablePath = 'C:\Java\bin\javaw.exe'
    } | ConvertTo-Json -Compress)
    [System.IO.File]::WriteAllText((Join-Path $fixtureRcRoot 'hs-script.pid.json'), $foreignMarkerJson)

    $jsonCoercedMarker = $foreignMarkerJson | ConvertFrom-Json
    if (-not ($jsonCoercedMarker.processStartTimeUtc -is [DateTime])) {
        throw 'Regression fixture requires ConvertFrom-Json to exercise ISO timestamp DateTime coercion'
    }
    $fixtureMarkers = @(Get-HsScriptRuntimeMarkers $fixtureBetaRoot)
    Assert-Equal ([DateTimeOffset]::Parse($fixtureTimestamp).UtcDateTime.Ticks) `
        ([DateTimeOffset]::Parse($fixtureMarkers[0].ProcessStartTimeUtc).UtcDateTime.Ticks) `
        'raw marker UTC instant must survive ConvertFrom-Json coercion'
    $fixtureIdentityProvider = {
        param($processId)
        if ($processId -eq 2202) {
            return [pscustomobject]@{ ProcessId = 2202; ProcessStartTimeUtc = $fixtureTimestamp; ExecutablePath = 'C:\Java\bin\javaw.exe' }
        }
        return $null
    }.GetNewClosure()
    $foreignFixture = Resolve-HsScriptProcessAssessment $fixtureBetaRoot @(
        [pscustomobject]@{ Name = 'javaw.exe'; ProcessId = 2202; CommandLine = '' }
    ) $fixtureMarkers $fixtureIdentityProvider
    Assert-Equal 'IDLE' $foreignFixture.Status 'exact live foreign RC marker with blank command line must not block Beta launch'

    # A genuinely stale marker for the same numeric PID remains ambiguous and
    # fail-closed even though the raw timestamp parsing is now corrected.
    $staleMarkerJson = $foreignMarkerJson.Replace($fixtureTimestamp, '2026-11-08T07:09:36.0000000Z')
    [System.IO.File]::WriteAllText((Join-Path $fixtureRcRoot 'hs-script.pid.json'), $staleMarkerJson)
    $staleFixtureMarkers = @(Get-HsScriptRuntimeMarkers $fixtureBetaRoot)
    $reusedFixture = Resolve-HsScriptProcessAssessment $fixtureBetaRoot @(
        [pscustomobject]@{ Name = 'javaw.exe'; ProcessId = 2202; CommandLine = '' }
    ) $staleFixtureMarkers $fixtureIdentityProvider
    Assert-Equal 'AMBIGUOUS' $reusedFixture.Status 'truly stale marker/PID reuse must remain fail-closed'
} finally {
    if (Test-Path -LiteralPath $markerFixtureRoot) {
        Remove-Item -LiteralPath $markerFixtureRoot -Recurse -Force
    }
}

# Blank or unavailable CIM CommandLine is identified by exact launcher PID,
# process start time, and executable path instead of being dropped as empty.
$blankLine = Resolve-HsScriptProcessAssessment $currentRoot @(
    [pscustomobject]@{ Name = 'javaw.exe'; ProcessId = 1101; CommandLine = $null }
) @($currentMarker) $identityProvider
Assert-Equal 'RUNTIME_ACTIVE' $blankLine.Status 'current channel marker must identify JVM when CommandLine is blank'
Assert-Equal 1101 $blankLine.ProcessIds[0] 'current channel PID should be reported'

# A verified foreign channel PID is not attributed to this runtime.
$foreignOnly = Resolve-HsScriptProcessAssessment $currentRoot @(
    [pscustomobject]@{ Name = 'javaw.exe'; ProcessId = 2202; CommandLine = '' }
) @($foreignMarker) $identityProvider
Assert-Equal 'IDLE' $foreignOnly.Status 'verified Beta process must not block RC based on channel ownership'

# The native image-path fallback is only evidence for the existing exact marker
# match; it allows a foreign channel through only when both start time and path
# agree, and never converts stale or current-channel evidence into IDLE.
$foreignNativeIdentityProvider = {
    param($processId)
    if ($processId -eq 2202) {
        return [pscustomobject]@{ ProcessId = 2202; ProcessStartTimeUtc = $foreignStart; ExecutablePath = 'C:\Java\bin\javaw.exe' }
    }
    return $null
}.GetNewClosure()
$foreignNativeOnly = Resolve-HsScriptProcessAssessment $currentRoot @(
    [pscustomobject]@{ Name = 'javaw.exe'; ProcessId = 2202; CommandLine = '' }
) @($foreignMarker) $foreignNativeIdentityProvider
Assert-Equal 'IDLE' $foreignNativeOnly.Status 'exact foreign marker remains allowed when identity uses the native image-path fallback'

$foreignPathMismatchProvider = {
    param($processId)
    [pscustomobject]@{ ProcessId = $processId; ProcessStartTimeUtc = $foreignStart; ExecutablePath = 'C:\OtherJava\bin\javaw.exe' }
}.GetNewClosure()
$foreignPathMismatch = Resolve-HsScriptProcessAssessment $currentRoot @(
    [pscustomobject]@{ Name = 'javaw.exe'; ProcessId = 2202; CommandLine = '' }
) @($foreignMarker) $foreignPathMismatchProvider
Assert-Equal 'AMBIGUOUS' $foreignPathMismatch.Status 'native path mismatch must leave a blank-command JVM fail-closed'

# Unknown JVMs with unavailable command lines fail closed.
$unavailableLine = Resolve-HsScriptProcessAssessment $currentRoot @(
    [pscustomobject]@{ Name = 'java.exe'; ProcessId = 3303 }
) @() $identityProvider
Assert-Equal 'AMBIGUOUS' $unavailableLine.Status 'unclassified JVM must not be treated as absent'

# A reused numeric PID with a different verified start time is stale marker
# evidence, not ownership of the new process.
$reusedMarker = [pscustomobject]@{
    RuntimeRoot = $currentRoot; ReleaseChannel = 'release-candidate'; ProcessId = 3303
    ProcessStartTimeUtc = '2026-10-25T22:00:00.0000000Z'; ExecutablePath = 'C:\Java\bin\javaw.exe'
}
$reusedPid = Resolve-HsScriptProcessAssessment $currentRoot @(
    [pscustomobject]@{ Name = 'javaw.exe'; ProcessId = 3303; CommandLine = '' }
) @($reusedMarker) $identityProvider
Assert-Equal 'AMBIGUOUS' $reusedPid.Status 'verified PID reuse must not inherit old ownership or pass as an unidentified idle JVM'

# Never restart the app while Hearthstone may still be in a queue/game.
$activeGame = Resolve-HsScriptProcessAssessment $currentRoot @(
    [pscustomobject]@{ Name = 'Hearthstone.exe'; ProcessId = 4404; CommandLine = $null }
) @() $identityProvider
Assert-Equal 'GAME_PROCESS_ACTIVE' $activeGame.Status 'a live game blocks deployment and launcher restart'

# A visible, nonblank command line permits precise cross-channel classification.
$foreignCommandLine = Resolve-HsScriptProcessAssessment $currentRoot @(
    [pscustomobject]@{ Name = 'javaw.exe'; ProcessId = 5505; CommandLine = 'javaw.exe -jar "C:\fixtures\Hearthstone Script Beta\hs-script_4.jar"' }
) @() $identityProvider
Assert-Equal 'IDLE' $foreignCommandLine.Status 'foreign channel path must not be mistaken for current runtime'

Write-Output 'DeploymentProcessOwnership.Tests.ps1 PASSED'
