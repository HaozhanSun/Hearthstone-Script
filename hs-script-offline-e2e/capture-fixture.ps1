param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string] $PowerLog,

    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string] $ScriptLog,

    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string] $DeckSelectionScreenshot,

    [Parameter(Mandatory = $true)]
    [string] $Destination,

    [string] $RankScreenshot,
    [string] $RunId = (Get-Date -Format 'yyyyMMdd-HHmmss')
)

$ErrorActionPreference = 'Stop'

foreach ($sourcePath in @($PowerLog, $ScriptLog, $DeckSelectionScreenshot, $RankScreenshot)) {
    if ([string]::IsNullOrWhiteSpace($sourcePath)) { continue }
    $resolved = (Resolve-Path -LiteralPath $sourcePath).Path
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        throw "Fixture source is not a file: $resolved"
    }
}

$destinationRoot = [System.IO.Path]::GetFullPath($Destination)
$driveRoot = [System.IO.Path]::GetPathRoot($destinationRoot)
if ($destinationRoot.TrimEnd('\') -eq $driveRoot.TrimEnd('\')) {
    throw 'Destination must be a named fixture directory, not a drive root.'
}
$fixtureDirectory = Join-Path $destinationRoot $RunId
if (Test-Path -LiteralPath $fixtureDirectory) {
    throw "Refusing to overwrite existing fixture directory: $fixtureDirectory"
}
New-Item -ItemType Directory -Path $fixtureDirectory | Out-Null

$powerPatterns = @(
    'CREATE_GAME',
    'MULLIGAN_STATE value=INPUT',
    'PLAYSTATE value=CONCEDED',
    'PLAYSTATE value=LOST',
    'PLAYSTATE value=WON',
    'STEP value=FINAL_GAMEOVER'
)
$powerLines = Get-Content -LiteralPath $PowerLog | Where-Object {
    $line = $_
    $powerPatterns.Where({ $line.Contains($_) }).Count -gt 0
} | ForEach-Object {
    $_ -replace 'Entity=[^ ]+#\d+', 'Entity=PLAYER'
}
if (-not $powerLines) {
    throw 'No supported authoritative game lifecycle markers found in Power.log.'
}
$powerLines | Set-Content -LiteralPath (Join-Path $fixtureDirectory 'powerlog-lifecycle.txt') -Encoding utf8

$scriptPatterns = @(
    'RANK_OCR ',
    'RANK_OCR_EVIDENCE ',
    'RANK_POLICY_TRIGGERED ',
    'SURRENDER_ACTION_REQUESTED ',
    'SURRENDER_EXECUTOR_REQUESTED '
)
$scriptLines = Get-Content -LiteralPath $ScriptLog | Where-Object {
    $line = $_
    $scriptPatterns.Where({ $line.Contains($_) }).Count -gt 0
} | Select-Object -Last 120 | ForEach-Object {
    $_ -replace 'C:\\Users\\[^\\ ]+', 'C:\Users\<redacted>'
}
if (-not $scriptLines) {
    throw 'No rank/surrender decision markers found in script log.'
}
$scriptLines | Set-Content -LiteralPath (Join-Path $fixtureDirectory 'script-rank-surrender.txt') -Encoding utf8

Copy-Item -LiteralPath $DeckSelectionScreenshot -Destination (Join-Path $fixtureDirectory 'deck-selection.png')
if (-not [string]::IsNullOrWhiteSpace($RankScreenshot)) {
    if (-not (Test-Path -LiteralPath $RankScreenshot -PathType Leaf)) {
        throw "Rank screenshot is not a file: $RankScreenshot"
    }
    Copy-Item -LiteralPath $RankScreenshot -Destination (Join-Path $fixtureDirectory 'rank-badge.png')
}

$metadata = [ordered]@{
    runId = $RunId
    capturedAt = (Get-Date).ToString('o')
    sourcePowerLog = [System.IO.Path]::GetFileName($PowerLog)
    sourceScriptLog = [System.IO.Path]::GetFileName($ScriptLog)
    hasRankScreenshot = -not [string]::IsNullOrWhiteSpace($RankScreenshot)
    reviewBeforeCheckingIn = $true
    notes = 'Screenshots may contain account names. Redact/crop sensitive regions before adding this folder to source control.'
}
$metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $fixtureDirectory 'fixture.json') -Encoding utf8

Write-Output "OFFLINE_RANK_FIXTURE_CREATED path=$fixtureDirectory"
Write-Output 'Review the copied screenshots and sanitize any identifiers before checking the fixture into source control.'
