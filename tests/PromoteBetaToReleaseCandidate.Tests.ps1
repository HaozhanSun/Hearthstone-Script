$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$promotionScript = Join-Path $root 'promote-beta-to-release-candidate.ps1'

function Invoke-TestGit {
    param([Parameter(Mandatory = $true)][string]$Root, [Parameter(Mandatory = $true)][string[]]$Arguments)
    $output = @(& git -C $Root @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "git $($Arguments -join ' ') failed: $($output -join [Environment]::NewLine)" }
    return (($output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine).Trim()
}

function Assert-Throws {
    param([Parameter(Mandatory = $true)][scriptblock]$Action, [Parameter(Mandatory = $true)][string]$MessageFragment)
    try { & $Action; throw 'Expected action to fail' }
    catch {
        if ($_.Exception.Message -eq 'Expected action to fail' -or
            $_.Exception.Message -notlike "*$MessageFragment*") {
            throw "Expected failure containing '$MessageFragment' but got: $($_.Exception.Message)"
        }
    }
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('hs-beta-rc-promotion-' + [guid]::NewGuid().ToString('N'))
$project = Join-Path $tempRoot 'beta-source'
$betaRuntime = Join-Path $tempRoot 'Hearthstone Script Beta'
$rcRuntime = Join-Path $tempRoot 'Hearthstone Script Release Candidate'
$rcWorktree = Join-Path $tempRoot 'rc-worktree'
$stableRuntime = Join-Path $tempRoot 'Hearthstone Script'
$fakeBuild = Join-Path $tempRoot 'fake-build.ps1'
$invocationLog = Join-Path $tempRoot 'build-invocations.log'
New-Item -ItemType Directory -Path $project, $betaRuntime, $stableRuntime -Force | Out-Null
Set-Content -LiteralPath (Join-Path $stableRuntime 'stable-marker.txt') -Value 'stable-preserved' -NoNewline

try {
    Invoke-TestGit -Root $project -Arguments @('init', '-b', 'beta/test-promotion') | Out-Null
    Invoke-TestGit -Root $project -Arguments @('config', 'user.email', 'promotion-test@example.invalid') | Out-Null
    Invoke-TestGit -Root $project -Arguments @('config', 'user.name', 'Promotion Test') | Out-Null
    $config = @'
{
  "schema": 1,
  "channel": "beta",
  "branch": "beta/test-promotion",
  "displayName": "Hearthstone Script Beta",
  "runtimeDirectoryName": "Hearthstone Script Beta",
  "shortcutName": "Hearthstone Script Beta.lnk",
  "iconFileName": "hs-script-beta.ico",
  "artifactLabel": "beta"
}
'@
    [System.IO.File]::WriteAllText((Join-Path $project 'release-channel.json'), $config)
    [System.IO.File]::WriteAllText((Join-Path $project 'build-and-deploy.ps1'), "param([string]`$Channel)`n")
    Invoke-TestGit -Root $project -Arguments @('add', '--all') | Out-Null
    Invoke-TestGit -Root $project -Arguments @('commit', '-m', 'beta promotion fixture') | Out-Null
    $betaHead = Invoke-TestGit -Root $project -Arguments @('rev-parse', 'HEAD')

    $jarPath = Join-Path $betaRuntime 'hs-script_v4.16.670-local-20261022-225531PDT.jar'
    [System.IO.File]::WriteAllText($jarPath, 'beta-artifact')
    $jarHash = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $betaManifest = [ordered]@{
        schema = 1
        releaseChannel = 'beta'
        runtimeDirectoryName = 'Hearthstone Script Beta'
        sourceBranch = 'beta/test-promotion'
        sourceCommit = $betaHead
        appJar = 'hs-script_v4.16.670-local-20261022-225531PDT.jar'
        appJarSha256 = $jarHash
    }
    $betaManifestPath = Join-Path $betaRuntime 'deployment-manifest.json'
    $betaManifest | ConvertTo-Json | Set-Content -LiteralPath $betaManifestPath -Encoding UTF8
    $betaManifestBefore = (Get-FileHash -LiteralPath $betaManifestPath -Algorithm SHA256).Hash
    $stableMarkerBefore = Get-Content -LiteralPath (Join-Path $stableRuntime 'stable-marker.txt') -Raw

    $fakeBuildText = @'
param([string]$Channel)
if ($Channel -cne 'release-candidate') { throw "wrong channel: $Channel" }
$root = $env:PROMOTION_TEST_RC_ROOT
$log = $env:PROMOTION_TEST_INVOCATION
Add-Content -LiteralPath $log -Value (Get-Date).ToString('o')
New-Item -ItemType Directory -Path $root -Force | Out-Null
$jar = Join-Path $root 'hs-script_v4.16.670-local-20261022-225531PDT.jar'
[System.IO.File]::WriteAllText($jar, 'rc-artifact')
$hash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
$config = Get-Content -LiteralPath (Join-Path (Get-Location) 'release-channel.json') -Raw | ConvertFrom-Json
$sourceCommit = ((& git rev-parse HEAD) | Out-String).Trim()
$sourceBranch = ((& git branch --show-current) | Out-String).Trim()
[ordered]@{
  schema = 1
  releaseChannel = 'release-candidate'
  runtimeDirectoryName = 'Hearthstone Script Release Candidate'
  sourceBranch = $sourceBranch
  sourceCommit = $sourceCommit
  promotionSourceBranch = [string]$config.promotionSourceBranch
  promotionSourceCommit = [string]$config.promotionSourceCommit
  appJar = 'hs-script_v4.16.670-local-20261022-225531PDT.jar'
  appJarSha256 = $hash
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'deployment-manifest.json') -Encoding UTF8
'@
    [System.IO.File]::WriteAllText($fakeBuild, $fakeBuildText)
    $env:PROMOTION_TEST_RC_ROOT = $rcRuntime
    $env:PROMOTION_TEST_INVOCATION = $invocationLog

    & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $rcRuntime -PromotionWorktree $rcWorktree -BuildScript $fakeBuild | Out-Null
    if (-not (Test-Path -LiteralPath (Join-Path $rcWorktree 'release-channel.json') -PathType Leaf)) { throw 'RC worktree metadata was not created' }
    $rcConfig = Get-Content -LiteralPath (Join-Path $rcWorktree 'release-channel.json') -Raw | ConvertFrom-Json
    if ($rcConfig.channel -ne 'release-candidate' -or $rcConfig.branch -ne 'beta/release-candidate-v4.16.670-local-20261022-225531PDT') { throw 'RC metadata was not isolated or branch-bound' }
    if ($rcConfig.promotionSourceCommit -ne $betaHead -or $rcConfig.promotionSourceBranch -ne 'beta/test-promotion') { throw 'RC promotion source metadata is incomplete' }
    if ((Get-FileHash -LiteralPath $betaManifestPath -Algorithm SHA256).Hash -cne $betaManifestBefore) { throw 'Promotion modified the Beta manifest' }
    if ((Get-Content -LiteralPath (Join-Path $stableRuntime 'stable-marker.txt') -Raw) -cne $stableMarkerBefore) { throw 'Promotion modified the Stable runtime' }
    if ((Get-Content -LiteralPath $invocationLog).Count -ne 1) { throw 'Initial promotion did not invoke the build path exactly once' }

    $repeatOutput = & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $rcRuntime -PromotionWorktree $rcWorktree -BuildScript $fakeBuild
    if (($repeatOutput -join "`n") -notmatch 'PROMOTION_ALREADY_COMPLETE') { throw 'Repeat promotion was not idempotent' }
    if ((Get-Content -LiteralPath $invocationLog).Count -ne 1) { throw 'Repeat promotion rebuilt an already-complete RC' }

    $invalidRuntime = Join-Path $tempRoot 'invalid-beta-runtime'
    New-Item -ItemType Directory -Path $invalidRuntime -Force | Out-Null
    $invalidJarName = 'hs-script_v4.16.670-local-20261022-225531UTC.jar'
    $invalidJarPath = Join-Path $invalidRuntime $invalidJarName
    [System.IO.File]::WriteAllText($invalidJarPath, 'invalid-beta-artifact')
    $invalidJarHash = (Get-FileHash -LiteralPath $invalidJarPath -Algorithm SHA256).Hash.ToLowerInvariant()
    [ordered]@{
        schema = 1
        releaseChannel = 'beta'
        runtimeDirectoryName = 'Hearthstone Script Beta'
        sourceBranch = 'beta/test-promotion'
        sourceCommit = $betaHead
        appJar = $invalidJarName
        appJarSha256 = $invalidJarHash
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $invalidRuntime 'deployment-manifest.json') -Encoding UTF8
    Assert-Throws -Action {
        & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $invalidRuntime -ReleaseCandidateRuntimeRoot (Join-Path $tempRoot 'invalid-rc-runtime') -PromotionWorktree (Join-Path $tempRoot 'invalid-rc-worktree') -BuildScript $fakeBuild | Out-Null
    } -MessageFragment 'Beta artifact must use a numeric release version'

    $missingRuntime = Join-Path $tempRoot 'missing-beta-runtime'
    Assert-Throws -Action { & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $missingRuntime -ReleaseCandidateRuntimeRoot $rcRuntime -PromotionWorktree (Join-Path $tempRoot 'unused-worktree') -BuildScript $fakeBuild | Out-Null } -MessageFragment 'Beta deployment manifest is missing'

    Set-Content -LiteralPath (Join-Path $project 'dirty.txt') -Value 'dirty'
    Assert-Throws -Action { & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $rcRuntime -PromotionWorktree $rcWorktree -BuildScript $fakeBuild | Out-Null } -MessageFragment 'Beta source checkout is dirty'
    Remove-Item -LiteralPath (Join-Path $project 'dirty.txt') -Force

    $wrongBranchConfig = Get-Content -LiteralPath (Join-Path $project 'release-channel.json') -Raw | ConvertFrom-Json
    $wrongBranchConfig.branch = 'beta/not-the-current-branch'
    $wrongBranchConfig | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $project 'release-channel.json') -Encoding UTF8
    Invoke-TestGit -Root $project -Arguments @('add', 'release-channel.json') | Out-Null
    Invoke-TestGit -Root $project -Arguments @('commit', '-m', 'invalid branch metadata fixture') | Out-Null
    Assert-Throws -Action { & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $rcRuntime -PromotionWorktree (Join-Path $tempRoot 'unused-worktree-2') -BuildScript $fakeBuild | Out-Null } -MessageFragment 'Beta source branch metadata mismatch'

    $wrongChannelConfig = Get-Content -LiteralPath (Join-Path $project 'release-channel.json') -Raw | ConvertFrom-Json
    $wrongChannelConfig.branch = 'beta/test-promotion'
    $wrongChannelConfig.channel = 'stable'
    $wrongChannelConfig | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $project 'release-channel.json') -Encoding UTF8
    Invoke-TestGit -Root $project -Arguments @('add', 'release-channel.json') | Out-Null
    Invoke-TestGit -Root $project -Arguments @('commit', '-m', 'invalid channel metadata fixture') | Out-Null
    Assert-Throws -Action { & $promotionScript -ProjectRoot $project -BetaRuntimeRoot $betaRuntime -ReleaseCandidateRuntimeRoot $rcRuntime -PromotionWorktree (Join-Path $tempRoot 'unused-worktree-3') -BuildScript $fakeBuild | Out-Null } -MessageFragment 'Beta source channel mismatch'

    $buildText = Get-Content -LiteralPath (Join-Path $root 'build-and-deploy.ps1') -Raw
    foreach ($field in @('sourceBranch', 'sourceCommit', 'promotionSourceBranch', 'promotionSourceCommit')) {
        if ($buildText -notmatch [regex]::Escape("$field =")) { throw "Build manifest source field is missing: $field" }
    }
    Write-Output 'PromoteBetaToReleaseCandidate.Tests: PASS (clean promotion, exact source snapshot, RC isolation, idempotent repeat, dirty/missing/mismatched refusal, Beta/Stable preservation)'
} finally {
    Remove-Item Env:PROMOTION_TEST_RC_ROOT -ErrorAction SilentlyContinue
    Remove-Item Env:PROMOTION_TEST_INVOCATION -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}
