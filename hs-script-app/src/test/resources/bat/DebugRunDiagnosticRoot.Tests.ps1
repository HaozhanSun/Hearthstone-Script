$ErrorActionPreference = 'Stop'

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "ASSERT_TRUE failed: $Message" }
}

function Assert-Contains([string]$Text, [string]$Expected, [string]$Message) {
    Assert-True $Text.Contains($Expected) "$Message expected=[$Expected]"
}

function Assert-Throws([scriptblock]$Script, [string]$ExpectedMessage, [string]$Message) {
    try {
        & $Script
        throw "ASSERT_THROWS failed: $Message did not throw"
    } catch {
        if (-not $_.Exception.Message.Contains($ExpectedMessage)) {
            throw "ASSERT_THROWS failed: $Message expected message containing=[$ExpectedMessage] actual=[$($_.Exception.Message)]"
        }
    }
}

$helperPath = Join-Path $PSScriptRoot '..\..\..\main\resources\bat\debug-run-diagnostics.ps1'
$runnerPath = Join-Path $PSScriptRoot '..\..\..\main\resources\bat\run-debug.ps1'
if (-not (Test-Path -LiteralPath $helperPath -PathType Leaf)) { throw "Diagnostic helper missing: $helperPath" }
if (-not (Test-Path -LiteralPath $runnerPath -PathType Leaf)) { throw "Debug runner missing: $runnerPath" }
. $helperPath

$runtimeDirectory = 'C:\Users\tester\Hearthstone Script Beta'
$volumes = @(
    [pscustomobject]@{ DeviceID = 'C:'; FreeSpace = 24GB; DriveType = 3 },
    [pscustomobject]@{ DeviceID = 'D:'; FreeSpace = 50GB; DriveType = 3 }
)
$roots = Resolve-HsScriptDebugRunDiagnosticRoots `
    -Channel beta `
    -RuntimeDirectory $runtimeDirectory `
    -FixedVolumes $volumes

Assert-Contains $roots.DiagnosticLogRoot 'D:\HearthstoneScriptLogs\Beta\' 'Beta log root must be external and scoped'
Assert-Contains $roots.AppLogDirectory '\app' 'application log must use the active app subdirectory'
Assert-Contains $roots.RuntimeLogDirectory '\runtime-log' 'runtime diagnostics must use the runtime-log subdirectory'
Assert-Contains $roots.JavaTempDirectory 'D:\HearthstoneScriptTemp\Beta\' 'Beta temp must use the external policy'
Assert-Contains $roots.ArtifactRoot 'D:\HearthstoneScriptArtifacts\Beta\' 'diagnostic artifacts must use the external policy'
Assert-True ($roots.DiagnosticArchiveRoot -eq 'D:\HearthstoneScriptArchives') 'diagnostic archive root must be explicit'

$jvmArguments = @(Get-HsScriptDebugRunJvmDiagnosticArguments -DiagnosticRoots $roots)
foreach ($requiredArgument in @(
    '-Djava.io.tmpdir=D:\HearthstoneScriptTemp\Beta\',
    '-Dhs.script.log.dir=D:\HearthstoneScriptLogs\Beta\',
    '-Dhs.script.external-runtime-log-root=D:\HearthstoneScriptLogs\Beta\',
    '-Dhs.script.beta-runtime-log-root=D:\HearthstoneScriptLogs\Beta\',
    '-Dhs.script.guardian-state.path=D:\HearthstoneScriptLogs\Beta\',
    '-Dhs.script.diagnostic-archive-root=D:\HearthstoneScriptArchives',
    '-Dhs.script.artifact-root=D:\HearthstoneScriptArtifacts\Beta\',
    '-Dhs.script.mcts-replay.dir=',
    '-Dhs.script.unknown-state.dir=',
    '-Dhs.script.result-screenshot.dir=',
    '-Dhs.script.mulligan-screenshot.dir='
)) {
    Assert-True (@($jvmArguments | Where-Object { $_.StartsWith($requiredArgument) }).Count -eq 1) `
        "JVM diagnostic arguments must contain [$requiredArgument]"
}
Assert-True (@($jvmArguments | Where-Object { $_ -match 'hs\.script\.(log\.dir|external-runtime-log-root)=' -and $_.Contains('C:\') }).Count -eq 0) `
    'JVM diagnostic arguments must not route application logs to the runtime C: drive'

$belowReserveVolumes = @([pscustomobject]@{ DeviceID = 'C:'; FreeSpace = 24GB; DriveType = 3 },
    [pscustomobject]@{ DeviceID = 'D:'; FreeSpace = 1GB; DriveType = 3 })
Assert-Throws {
    Resolve-HsScriptDebugRunDiagnosticRoots -Channel beta -RuntimeDirectory $runtimeDirectory -FixedVolumes $belowReserveVolumes
} 'BETA_LOG_DIRECTORY_UNAVAILABLE' 'below-reserve external storage must refuse before Java starts'
Assert-Throws {
    Resolve-HsScriptDebugRunDiagnosticRoots -Channel beta -RuntimeDirectory $runtimeDirectory -FixedVolumes $belowReserveVolumes
} 'reason=configured-volume-below-reserve' 'below-reserve refusal must identify the configured volume condition'

$noExternalVolumes = @([pscustomobject]@{ DeviceID = 'C:'; FreeSpace = 24GB; DriveType = 3 })
Assert-Throws {
    Resolve-HsScriptDebugRunDiagnosticRoots -Channel beta -RuntimeDirectory $runtimeDirectory -FixedVolumes $noExternalVolumes
} 'reason=no-external-log-directory-configured' 'absence of an external volume must be distinct from low free space'

$runnerText = Get-Content -LiteralPath $runnerPath -Raw
$resolverIndex = $runnerText.IndexOf('Resolve-HsScriptDebugRunDiagnosticRoots')
$attemptInitializationIndex = $runnerText.IndexOf('$attempt = 0')
$retryLoopIndex = $runnerText.IndexOf('while ($true)')
Assert-True $resolverIndex -ge 0 'runner must resolve diagnostic roots'
Assert-True $resolverIndex -lt $attemptInitializationIndex -and $attemptInitializationIndex -lt $retryLoopIndex `
    'storage refusal must happen before the retry loop is initialized'
Assert-Contains $runnerText '$scriptLog = Join-Path $appLogDirectory' 'watchdog must monitor the active external application log'
Assert-Contains $runnerText 'E2E_JAVA_STARTED runId=' 'startup must record the run-to-log correlation'
Assert-Contains $runnerText 'Get-HsScriptDebugRunJvmDiagnosticArguments' 'runner must pass the shared diagnostic JVM properties'
Assert-Contains $runnerText 'E2E diagnostic roots channel=beta' 'selected roots must be visible before Java starts'
Assert-True (-not $runnerText.Contains('$logDirectory = Join-Path $scriptDirectory "log"')) `
    'runner must not restore the old C: relative log root'

Write-Output 'DebugRunDiagnosticRoot.Tests.ps1 PASSED'
