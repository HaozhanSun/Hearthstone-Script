param(
    [Parameter(Mandatory = $true)]
    [string] $LogRoot,
    [Parameter(Mandatory = $true)]
    [string] $OutputFile
)

$files = Get-ChildItem -LiteralPath $LogRoot -Filter 'hs_script*.log' -File -Recurse
$rawEvents = 0
$parsed = @{}
$reasonTotals = @{}
$unparsed = 0

function Get-FieldValue([string] $Line, [string] $FieldName) {
    $match = [regex]::Match($Line, "(?:^|\s)$([regex]::Escape($FieldName))=([^\s]+)")
    if ($match.Success) {
        return $match.Groups[1].Value
    }
    return $null
}

foreach ($file in $files) {
    foreach ($line in Get-Content -LiteralPath $file.FullName) {
        if ($line -notmatch 'CARD_ACTION_UNRECOGNIZED') {
            continue
        }
        $rawEvents++
        $cardMatch = [regex]::Match($line, 'cardName=(.*?)\s+cardId=(\S+)\s+reason=(\S+)')
        if (-not $cardMatch.Success) {
            $unparsed++
            continue
        }
        $cardName = $cardMatch.Groups[1].Value
        $cardId = $cardMatch.Groups[2].Value
        $reason = $cardMatch.Groups[3].Value
        $rawAction = Get-FieldValue $line 'action'
        $action = if ($rawAction) { $rawAction } else { 'FAIL_CLOSED' }
        $identitySource = Get-FieldValue $line 'identitySource'
        $sourceZone = Get-FieldValue $line 'sourceZone'
        if (-not $sourceZone) {
            $sourceZone = 'HISTORICAL_BACKFILL'
        }
        $route = Get-FieldValue $line 'route'
        if (-not $route) {
            $route = 'FAIL_CLOSED_PARSER_UNAVAILABLE'
        }
        $safeAction = Get-FieldValue $line 'safeAction'
        if (-not $safeAction -or $safeAction -eq 'FAIL_CLOSED') {
            $safeAction = 'SKIP_UNRECOGNIZED'
        }
        if (-not $reasonTotals.ContainsKey($reason)) {
            $reasonTotals[$reason] = 0
        }
        $reasonTotals[$reason]++
        $key = "$cardId|$cardName|$reason|$action|$identitySource|$sourceZone|$route|$safeAction"
        if (-not $parsed.ContainsKey($key)) {
            $parsed[$key] = [ordered]@{
                cardId = $cardId
                cardName = $cardName
                reason = $reason
                action = $action
                identitySource = $identitySource
                sourceZone = $sourceZone
                route = $route
                safeAction = $safeAction
                phase = 'historical-log-backfill'
                events = 0
                firstSeen = $line.Substring(0, [Math]::Min(23, $line.Length))
                lastSeen = $line.Substring(0, [Math]::Min(23, $line.Length))
            }
        }
        $parsed[$key].events++
        $parsed[$key].lastSeen = $line.Substring(0, [Math]::Min(23, $line.Length))
    }
}

$parent = Split-Path -Parent $OutputFile
if ($parent) {
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
}
$jsonLines = @($parsed.Values | Sort-Object @{ Expression = { -[int]$_.events } }, cardId, cardName |
    ForEach-Object { $_ | ConvertTo-Json -Compress -Depth 4 }
)
[IO.File]::WriteAllText($OutputFile, ($jsonLines -join [Environment]::NewLine), [Text.UTF8Encoding]::new($false))

function Sum-Events($records) {
    $sum = 0
    foreach ($record in $records) {
        $sum += [int]$record.events
    }
    return $sum
}

$parsedEvents = Sum-Events $parsed.Values
Write-Output "FILES=$($files.Count) RAW_EVENTS=$rawEvents PARSED_EVENTS=$parsedEvents UNIQUE_KEYS=$($parsed.Count) UNPARSED=$unparsed"
Write-Output 'REASONS'
$reasonTotals.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object {
    "$($_.Key)`t$($_.Value)"
}
Write-Output 'TOP 50'
$parsed.Values | Sort-Object @{ Expression = { -[int]$_.events } } | Select-Object -First 50 |
    ForEach-Object { "$($_.cardId)`t$($_.cardName)`t$($_.events)`t$($_.reason)`t$($_.sourceZone)`t$($_.route)`t$($_.safeAction)" }
