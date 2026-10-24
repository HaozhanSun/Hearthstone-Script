Describe 'Beta to Release Candidate promotion contract' {
    It 'passes the complete isolated promotion integration harness' {
        $integrationScript = Join-Path $PSScriptRoot 'PromoteBetaToReleaseCandidate.Tests.ps1'
        $output = @(& pwsh -NoProfile -ExecutionPolicy Bypass -File $integrationScript 2>&1)
        $exitCode = $LASTEXITCODE
        $text = ($output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine

        $exitCode | Should Be 0
        $text | Should Match 'PromoteBetaToReleaseCandidate\.Tests: PASS'
    }
}
