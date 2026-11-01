param(
    [Parameter(Mandatory = $true)]
    [string] $Path
)

$ErrorActionPreference = 'Stop'

$requiredFields = @(
    'Investigation and cause',
    'Fix',
    'Verification',
    'Future prevention',
    'Prevention enforcement'
)

if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    throw "Closeout record does not exist: $Path"
}

$content = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $Path))
foreach ($token in @('<short name', '<YYYY-MM-DD>')) {
    if ($content -match [regex]::Escape($token)) {
        throw "Closeout record contains unfinished placeholder/status '$token'"
    }
}
if ($content -match '(?im)^(Issue|Date):\s*(TODO|TBD|PENDING)\b') {
    throw 'Closeout record has an unfinished issue/date value'
}

$values = @{}
foreach ($field in $requiredFields) {
    $matches = [regex]::Matches($content, "(?m)^$([regex]::Escape($field)):\s*(.+?)\s*$")
    if ($matches.Count -ne 1) {
        throw "Expected exactly one non-empty '${field}:' field; found $($matches.Count)"
    }
    $values[$field] = $matches[0].Groups[1].Value.Trim()
}

foreach ($field in $requiredFields) {
    $value = $values[$field]
    if ($value -match '^N/A\s*[—-]\s*(.+)$') {
        $reason = $Matches[1].Trim()
        if ($reason.Length -lt 12 -or $reason -match '^(reason|n/?a|tbd)$') {
            throw "'$field' needs a specific N/A reason"
        }
        continue
    }
    if ($value -notmatch '^PASS\s*[—-]\s*\S') {
        throw "'$field' must begin with 'PASS —' or 'N/A — <specific reason>'"
    }
}

$enforcement = $values['Prevention enforcement']
if ($enforcement -notmatch '^N/A\s*[—-]' -and $enforcement -notmatch '^PASS\s*[—-]\s*(test|guard):\s*\S') {
    throw 'Prevention enforcement must identify an automated "test:" or "guard:"'
}

Write-Output "Problem-resolution closeout validation: PASS ($((Resolve-Path -LiteralPath $Path).Path))"
