$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$validator = Join-Path $root 'tools/validate-problem-resolution-closeout.ps1'
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("closeout-gate-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempRoot | Out-Null

function Write-Record([string] $Name, [string] $Body) {
    $path = Join-Path $tempRoot $Name
    [System.IO.File]::WriteAllText($path, $Body)
    return $path
}

function Invoke-Validator([string] $Path, [bool] $ShouldPass) {
    & pwsh -NoProfile -File $validator -Path $Path *> $null
    $passed = ($LASTEXITCODE -eq 0)
    if ($passed -ne $ShouldPass) {
        throw "Validator expectation failed for '$Path': expected pass=$ShouldPass, exit=$LASTEXITCODE"
    }
}

try {
    $valid = @'
Issue: closeout-gate regression
Date: 2026-11-01
Status: CLOSED
Investigation and cause: PASS — The former guidance did not require a uniform closeout artifact.
Fix: PASS — Added a canonical checklist and validator.
Verification: PASS — `pwsh -NoProfile -File tests/ProblemResolutionCloseout.Tests.ps1` passed.
Future prevention: PASS — Require a completed record before moving on.
Prevention enforcement: PASS — test: the validator suite rejects missing and incomplete closeouts.
'@
    Invoke-Validator (Write-Record 'valid.md' $valid) $true

    $validNa = $valid.Replace('Fix: PASS — Added a canonical checklist and validator.', 'Fix: N/A — The investigation found no application behavior to modify in this process-only task.')
    Invoke-Validator (Write-Record 'valid-na.md' $validNa) $true

    $validOpen = $valid.Replace('Status: CLOSED', 'Status: OPEN — live correlation remains pending after the secretary-owned diagnostic deployment.').Replace(
        'Investigation and cause: PASS — The former guidance did not require a uniform closeout artifact.',
        'Investigation and cause: PARTIAL — The old live log omits the failing revalidation predicate.'
    ).Replace(
        'Fix: PASS — Added a canonical checklist and validator.',
        'Fix: DIAGNOSTIC-ONLY — Added ordered final-boundary reasons without changing action authorization.'
    ).Replace(
        'Verification: PASS — `pwsh -NoProfile -File tests/ProblemResolutionCloseout.Tests.ps1` passed.',
        'Verification: PARTIAL — Offline regression tests pass; current-build live correlation is still pending.'
    )
    Invoke-Validator (Write-Record 'valid-open.md' $validOpen) $true

    $invalids = @(
        $valid.Replace('Investigation and cause: PASS — The former guidance did not require a uniform closeout artifact.', ''),
        $valid.Replace('Future prevention: PASS — Require a completed record before moving on.', 'Future prevention: PASS — Require a completed record before moving on.' + "`nFuture prevention: PASS — duplicate field."),
        $valid.Replace('Fix: PASS — Added a canonical checklist and validator.', 'Fix: N/A — reason'),
        $valid.Replace('Verification: PASS — `pwsh -NoProfile -File tests/ProblemResolutionCloseout.Tests.ps1` passed.', 'Verification: PENDING — later.'),
        $valid.Replace('Prevention enforcement: PASS — test: the validator suite rejects missing and incomplete closeouts.', 'Prevention enforcement: PASS — documentation says to be careful.'),
        $valid.Replace('Status: CLOSED', 'Status: OPEN — short'),
        $valid.Replace('Verification: PASS — `pwsh -NoProfile -File tests/ProblemResolutionCloseout.Tests.ps1` passed.', 'Verification: PARTIAL — offline tests pass, but the issue remains open pending deployment.'),
        $valid.Replace('Date: 2026-11-01', 'Date: <YYYY-MM-DD>')
    )
    $index = 0
    foreach ($body in $invalids) {
        $index++
        Invoke-Validator (Write-Record "invalid-$index.md" $body) $false
    }
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force
}

Write-Output 'ProblemResolutionCloseout.Tests: PASS (closed/open records, reasoned N/A, missing/duplicate fields, vague N/A, incomplete closed record, unenforced prevention, placeholders)'
