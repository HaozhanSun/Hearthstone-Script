[CmdletBinding()]
param(
    [string]$ProjectRoot = '',
    [string]$BetaRuntimeRoot = '',
    [string]$ReleaseCandidateRuntimeRoot = '',
    [string]$ReleaseCandidateBranch = '',
    [string]$PromotionWorktree = '',
    [string]$BuildScript = ''
)

$ErrorActionPreference = 'Stop'
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = $PSScriptRoot }
$projectRoot = [System.IO.Path]::GetFullPath($ProjectRoot).TrimEnd('\')

function Invoke-GitText {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$AllowFailure
    )

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& git -C $Root @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    $text = ($output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "git $($Arguments -join ' ') failed in $Root (exit $exitCode): $text"
    }
    [pscustomobject]@{ ExitCode = $exitCode; Text = $text.Trim() }
}

function Assert-GitClean {
    param([Parameter(Mandatory = $true)][string]$Root, [Parameter(Mandatory = $true)][string]$Label)
    $status = Invoke-GitText -Root $Root -Arguments @('status', '--porcelain', '--untracked-files=all')
    if (-not [string]::IsNullOrWhiteSpace($status.Text)) {
        throw "$Label is dirty; refusing promotion: $($status.Text -replace "`r?`n", '; ')"
    }
}

function Read-JsonFile {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][string]$Label)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Label is missing: $Path" }
    try { return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json }
    catch { throw "$Label is not valid JSON: $Path. $($_.Exception.Message)" }
}

function Get-PropertyText {
    param([Parameter(Mandatory = $true)]$Object, [Parameter(Mandatory = $true)][string]$Name)
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return '' }
    return ([string]$property.Value).Trim()
}

function Assert-Equals {
    param([Parameter(Mandatory = $true)][string]$Expected, [Parameter(Mandatory = $true)][string]$Actual, [Parameter(Mandatory = $true)][string]$Label)
    if ($Expected -cne $Actual) { throw "$Label mismatch: expected '$Expected', actual '$Actual'" }
}

function Assert-FileHash {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][string]$Expected, [Parameter(Mandatory = $true)][string]$Label)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Label is missing: $Path" }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Equals -Expected $Expected.ToLowerInvariant() -Actual $actual -Label "$Label SHA-256"
}

function Get-VersionFromJarName {
    param([Parameter(Mandatory = $true)][string]$JarName)
    $match = [regex]::Match($JarName, '^hs-script_(v\d+\.\d+\.\d+)\.jar$')
    if (-not $match.Success) {
        throw "Beta artifact must use a numeric release version before RC promotion: $JarName"
    }
    return $match.Groups[1].Value
}

function Get-CanonicalOutputParent {
    $stableRoot = 'C:\Users\yzjsh\Documents\Codex\2026-08-15\for-all-these-delay-short-are-2\outputs\Hearthstone Script'
    return Split-Path -Parent $stableRoot
}

function Get-WorktreeBranchPath {
    param([Parameter(Mandatory = $true)][string]$Root, [Parameter(Mandatory = $true)][string]$Branch)
    $listing = Invoke-GitText -Root $Root -Arguments @('worktree', 'list', '--porcelain')
    $path = ''
    foreach ($line in ($listing.Text -split "`r?`n")) {
        if ($line.StartsWith('worktree ', [StringComparison]::Ordinal)) {
            $path = $line.Substring('worktree '.Length).Trim()
        } elseif ($line -eq "branch refs/heads/$Branch") {
            return [System.IO.Path]::GetFullPath($path).TrimEnd('\')
        }
    }
    return ''
}

function Get-BetaDeployment {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Branch,
        [Parameter(Mandatory = $true)][string]$Head
    )
    $configPath = Join-Path $Root 'release-channel.json'
    $config = Read-JsonFile -Path $configPath -Label 'Beta source channel metadata'
    Assert-Equals -Expected 'beta' -Actual (Get-PropertyText $config 'channel') -Label 'Beta source channel'
    Assert-Equals -Expected $Branch -Actual (Get-PropertyText $config 'branch') -Label 'Beta source branch metadata'
    Assert-Equals -Expected 'Hearthstone Script Beta' -Actual (Get-PropertyText $config 'runtimeDirectoryName') -Label 'Beta runtime identity'

    $runtime = if ([string]::IsNullOrWhiteSpace($BetaRuntimeRoot)) {
        Join-Path (Get-CanonicalOutputParent) 'Hearthstone Script Beta'
    } else { [System.IO.Path]::GetFullPath($BetaRuntimeRoot).TrimEnd('\') }
    $manifestPath = Join-Path $runtime 'deployment-manifest.json'
    $manifest = Read-JsonFile -Path $manifestPath -Label 'Beta deployment manifest'
    Assert-Equals -Expected 'beta' -Actual (Get-PropertyText $manifest 'releaseChannel') -Label 'Beta deployment channel'
    Assert-Equals -Expected 'Hearthstone Script Beta' -Actual (Get-PropertyText $manifest 'runtimeDirectoryName') -Label 'Beta deployment runtime identity'
    $sourceCommit = Get-PropertyText $manifest 'sourceCommit'
    $sourceBranch = Get-PropertyText $manifest 'sourceBranch'
    if ($sourceCommit -notmatch '^[0-9a-fA-F]{40}$') {
        throw "Beta deployment manifest lacks an exact sourceCommit; rebuild Beta with the checked-in release path before promotion: $manifestPath"
    }
    Assert-Equals -Expected $Head -Actual $sourceCommit -Label 'Beta deployed source commit'
    Assert-Equals -Expected $Branch -Actual $sourceBranch -Label 'Beta deployed source branch'

    $jarName = Get-PropertyText $manifest 'appJar'
    if ([string]::IsNullOrWhiteSpace($jarName) -or $jarName.Contains('\') -or $jarName.Contains('/')) { throw "Beta manifest appJar must be a runtime-local file name: $jarName" }
    $jarPath = Join-Path $runtime $jarName
    $jarResolved = [System.IO.Path]::GetFullPath($jarPath)
    if (-not $jarResolved.StartsWith($runtime + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Beta manifest appJar escapes the Beta runtime' }
    Assert-FileHash -Path $jarPath -Expected (Get-PropertyText $manifest 'appJarSha256') -Label 'Beta application JAR'

    [pscustomobject]@{
        Config = $config
        Manifest = $manifest
        RuntimeRoot = $runtime
        ManifestPath = $manifestPath
        JarName = $jarName
        JarPath = $jarPath
        Version = Get-VersionFromJarName $jarName
    }
}

function Set-RcMetadata {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Branch,
        [Parameter(Mandatory = $true)][string]$SourceBranch,
        [Parameter(Mandatory = $true)][string]$SourceCommit
    )
    $config = Read-JsonFile -Path $Path -Label 'Release Candidate channel metadata'
    $config.channel = 'release-candidate'
    $config.branch = $Branch
    $config.displayName = 'Hearthstone Script Release Candidate'
    $config.runtimeDirectoryName = 'Hearthstone Script Release Candidate'
    $config.shortcutName = 'Hearthstone Script Release Candidate.lnk'
    $config.iconFileName = 'hs-script-release-candidate.ico'
    $config.artifactLabel = 'release-candidate'
    if ($null -eq $config.PSObject.Properties['promotionSourceBranch']) { $config | Add-Member -NotePropertyName promotionSourceBranch -NotePropertyValue $SourceBranch }
    else { $config.promotionSourceBranch = $SourceBranch }
    if ($null -eq $config.PSObject.Properties['promotionSourceCommit']) { $config | Add-Member -NotePropertyName promotionSourceCommit -NotePropertyValue $SourceCommit }
    else { $config.promotionSourceCommit = $SourceCommit }
    $json = $config | ConvertTo-Json -Depth 10
    [System.IO.File]::WriteAllText($Path, $json + [Environment]::NewLine, $utf8NoBom)
}

function Assert-RcMetadata {
    param([Parameter(Mandatory = $true)][string]$Root, [Parameter(Mandatory = $true)][string]$Branch, [Parameter(Mandatory = $true)][string]$SourceBranch, [Parameter(Mandatory = $true)][string]$SourceCommit)
    $config = Read-JsonFile -Path (Join-Path $Root 'release-channel.json') -Label 'Release Candidate channel metadata'
    Assert-Equals -Expected 'release-candidate' -Actual (Get-PropertyText $config 'channel') -Label 'Release Candidate channel'
    Assert-Equals -Expected $Branch -Actual (Get-PropertyText $config 'branch') -Label 'Release Candidate branch metadata'
    Assert-Equals -Expected 'Hearthstone Script Release Candidate' -Actual (Get-PropertyText $config 'runtimeDirectoryName') -Label 'Release Candidate runtime identity'
    Assert-Equals -Expected 'Hearthstone Script Release Candidate.lnk' -Actual (Get-PropertyText $config 'shortcutName') -Label 'Release Candidate shortcut identity'
    Assert-Equals -Expected 'hs-script-release-candidate.ico' -Actual (Get-PropertyText $config 'iconFileName') -Label 'Release Candidate icon identity'
    Assert-Equals -Expected $SourceBranch -Actual (Get-PropertyText $config 'promotionSourceBranch') -Label 'Release Candidate source branch'
    Assert-Equals -Expected $SourceCommit -Actual (Get-PropertyText $config 'promotionSourceCommit') -Label 'Release Candidate source commit'
}

function Test-RcDeployment {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$SourceBranch,
        [Parameter(Mandatory = $true)][string]$SourceCommit
    )
    $manifestPath = Join-Path $Root 'deployment-manifest.json'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { return $false }
    try {
        $manifest = Read-JsonFile -Path $manifestPath -Label 'Release Candidate deployment manifest'
        Assert-Equals -Expected 'release-candidate' -Actual (Get-PropertyText $manifest 'releaseChannel') -Label 'Release Candidate deployment channel'
        Assert-Equals -Expected 'Hearthstone Script Release Candidate' -Actual (Get-PropertyText $manifest 'runtimeDirectoryName') -Label 'Release Candidate deployment runtime identity'
        Assert-Equals -Expected $SourceBranch -Actual (Get-PropertyText $manifest 'promotionSourceBranch') -Label 'Release Candidate deployed source branch'
        Assert-Equals -Expected $SourceCommit -Actual (Get-PropertyText $manifest 'promotionSourceCommit') -Label 'Release Candidate deployed source commit'
        $jarName = Get-PropertyText $manifest 'appJar'
        if ([string]::IsNullOrWhiteSpace($jarName) -or $jarName.Contains('\') -or $jarName.Contains('/')) { return $false }
        Assert-FileHash -Path (Join-Path $Root $jarName) -Expected (Get-PropertyText $manifest 'appJarSha256') -Label 'Release Candidate application JAR'
        return $true
    } catch { return $false }
}

function Invoke-BuildAndDeploy {
    param([Parameter(Mandatory = $true)][string]$Root, [Parameter(Mandatory = $true)][string]$Script)
    if (-not (Test-Path -LiteralPath $Script -PathType Leaf)) { throw "Checked-in build-and-deploy.ps1 is missing from the RC snapshot: $Script" }
    $resolvedScript = [System.IO.Path]::GetFullPath($Script)
    Push-Location $Root
    try {
        & $resolvedScript -Channel 'release-candidate'
        if (-not $?) { throw 'Release Candidate build-and-deploy.ps1 failed' }
    } finally { Pop-Location }
}

if (-not (Test-Path -LiteralPath (Join-Path $projectRoot '.git'))) { throw "Project root is not a Git checkout: $projectRoot" }
$branchResult = Invoke-GitText -Root $projectRoot -Arguments @('branch', '--show-current')
$betaBranch = $branchResult.Text
if ([string]::IsNullOrWhiteSpace($betaBranch) -or $betaBranch -notlike 'beta/*' -or $betaBranch -like 'beta/release-candidate-*') {
    throw "Promotion must start from a named non-RC beta branch; current branch is '$betaBranch'"
}
Assert-GitClean -Root $projectRoot -Label 'Beta source checkout'
$betaHead = (Invoke-GitText -Root $projectRoot -Arguments @('rev-parse', 'HEAD')).Text
$betaDeployment = Get-BetaDeployment -Root $projectRoot -Branch $betaBranch -Head $betaHead

if ([string]::IsNullOrWhiteSpace($ReleaseCandidateBranch)) { $ReleaseCandidateBranch = "beta/release-candidate-$($betaDeployment.Version)" }
if ($ReleaseCandidateBranch -notlike 'beta/release-candidate-*') { throw "Release Candidate branch must begin beta/release-candidate-: $ReleaseCandidateBranch" }
$rcRoot = if ([string]::IsNullOrWhiteSpace($ReleaseCandidateRuntimeRoot)) {
    Join-Path (Get-CanonicalOutputParent) 'Hearthstone Script Release Candidate'
} else { [System.IO.Path]::GetFullPath($ReleaseCandidateRuntimeRoot).TrimEnd('\') }
if ([System.IO.Path]::GetFullPath($rcRoot).TrimEnd('\') -eq [System.IO.Path]::GetFullPath($betaDeployment.RuntimeRoot).TrimEnd('\') -or
    [System.IO.Path]::GetFileName($rcRoot) -ne 'Hearthstone Script Release Candidate' -or
    [System.IO.Path]::GetFileName($rcRoot) -eq 'Hearthstone Script') {
    throw "Release Candidate runtime must be isolated from Beta and Stable: $rcRoot"
}
if ([string]::IsNullOrWhiteSpace($PromotionWorktree)) {
    $worktreeLeaf = 'Hearthstone Copilot-' + ($ReleaseCandidateBranch -replace '[^A-Za-z0-9._-]', '-')
    $PromotionWorktree = Join-Path (Split-Path -Parent $projectRoot) $worktreeLeaf
}
$PromotionWorktree = [System.IO.Path]::GetFullPath($PromotionWorktree).TrimEnd('\')
if ([string]::IsNullOrWhiteSpace($BuildScript)) { $BuildScript = Join-Path $PromotionWorktree 'build-and-deploy.ps1' }

$branchRef = Invoke-GitText -Root $projectRoot -Arguments @('rev-parse', '--verify', "refs/heads/$ReleaseCandidateBranch") -AllowFailure
$existingRcWorktree = Get-WorktreeBranchPath -Root $projectRoot -Branch $ReleaseCandidateBranch
if ($branchRef.ExitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($existingRcWorktree) -and
    $existingRcWorktree -cne $PromotionWorktree) {
    throw "Release Candidate branch is already checked out elsewhere: $existingRcWorktree"
}

# A valid RC manifest is the idempotency fence. A rerun must not rebuild or
# touch a channel that already contains the exact promoted source snapshot.
if ($branchRef.ExitCode -eq 0 -and (Test-RcDeployment -Root $rcRoot -SourceBranch $betaBranch -SourceCommit $betaHead)) {
    Write-Output "PROMOTION_ALREADY_COMPLETE branch=$ReleaseCandidateBranch sourceCommit=$betaHead runtime=$rcRoot"
    exit 0
}

if ($branchRef.ExitCode -ne 0) {
    Invoke-GitText -Root $projectRoot -Arguments @('branch', $ReleaseCandidateBranch, $betaHead) | Out-Null
    Write-Output "CREATED_RC_BRANCH=$ReleaseCandidateBranch sourceCommit=$betaHead"
} else {
    $rcHead = (Invoke-GitText -Root $projectRoot -Arguments @('rev-parse', "refs/heads/$ReleaseCandidateBranch")).Text
    if ($rcHead -ne $betaHead) {
        $parent = (Invoke-GitText -Root $projectRoot -Arguments @('rev-parse', "$rcHead^") -AllowFailure).Text
        if ($parent -ne $betaHead) { throw "Existing Release Candidate branch does not derive directly from current Beta source: branch=$rcHead parent=$parent beta=$betaHead" }
    }
}

if ([string]::IsNullOrWhiteSpace($existingRcWorktree)) {
    if (Test-Path -LiteralPath $PromotionWorktree) {
        throw "Promotion worktree path exists but is not registered for the RC branch; refusing to overwrite it: $PromotionWorktree"
    }
    Invoke-GitText -Root $projectRoot -Arguments @('worktree', 'add', $PromotionWorktree, $ReleaseCandidateBranch) | Out-Null
    Write-Output "CREATED_RC_WORKTREE=$PromotionWorktree"
} else { $PromotionWorktree = $existingRcWorktree }

Assert-GitClean -Root $PromotionWorktree -Label 'Release Candidate source checkout before metadata update'
$rcConfigPath = Join-Path $PromotionWorktree 'release-channel.json'
Set-RcMetadata -Path $rcConfigPath -Branch $ReleaseCandidateBranch -SourceBranch $betaBranch -SourceCommit $betaHead
Assert-RcMetadata -Root $PromotionWorktree -Branch $ReleaseCandidateBranch -SourceBranch $betaBranch -SourceCommit $betaHead
$metadataStatus = Invoke-GitText -Root $PromotionWorktree -Arguments @('status', '--porcelain', '--untracked-files=all')
if (-not [string]::IsNullOrWhiteSpace($metadataStatus.Text)) {
    Invoke-GitText -Root $PromotionWorktree -Arguments @('add', '--', 'release-channel.json') | Out-Null
    Invoke-GitText -Root $PromotionWorktree -Arguments @('commit', '-m', "Promote Beta $betaDeployment.Version to Release Candidate") | Out-Null
}
Assert-GitClean -Root $PromotionWorktree -Label 'Release Candidate source checkout after metadata commit'
Assert-RcMetadata -Root $PromotionWorktree -Branch $ReleaseCandidateBranch -SourceBranch $betaBranch -SourceCommit $betaHead
Write-Output "RC_METADATA_COMMITTED branch=$ReleaseCandidateBranch sourceCommit=$betaHead"

if (-not (Test-Path -LiteralPath $BuildScript -PathType Leaf)) { throw "Checked-in build-and-deploy.ps1 is missing: $BuildScript" }
Write-Output "BUILD_AND_DEPLOY_INVOCATION=release-candidate worktree=$PromotionWorktree"
Invoke-BuildAndDeploy -Root $PromotionWorktree -Script $BuildScript
if (-not (Test-RcDeployment -Root $rcRoot -SourceBranch $betaBranch -SourceCommit $betaHead)) {
    throw "Release Candidate deployment manifest did not validate against the promoted Beta source: $rcRoot"
}
Write-Output "PROMOTION_COMPLETE branch=$ReleaseCandidateBranch sourceCommit=$betaHead runtime=$rcRoot"
