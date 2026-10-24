[CmdletBinding()]
param(
    [string]$ProjectRoot = '',
    [string]$BetaRuntimeRoot = '',
    [string]$ReleaseCandidateRuntimeRoot = '',
    [string]$ReleaseCandidateBranch = '',
    [string]$PromotionWorktree = '',
    [string]$BuildScript = '',
    [string]$VerificationEvidencePath = '',
    [string]$ShortcutRoot = '',
    [switch]$PreflightOnly,
    [switch]$AllowLegacyRcUpgrade
)

$ErrorActionPreference = 'Stop'
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.Reflection.Assembly]::LoadWithPartialName('System.IO.Compression.FileSystem') | Out-Null
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

function Get-GitStatusLines {
    param([Parameter(Mandatory = $true)][string]$Root)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& git -C $Root status --porcelain --untracked-files=all 2>&1)
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($exitCode -ne 0) { throw "git status failed in $Root (exit $exitCode): $($output -join [Environment]::NewLine)" }
    return @($output | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Assert-GitClean {
    param([Parameter(Mandatory = $true)][string]$Root, [Parameter(Mandatory = $true)][string]$Label)
    $status = Invoke-GitText -Root $Root -Arguments @('status', '--porcelain', '--untracked-files=all')
    if (-not [string]::IsNullOrWhiteSpace($status.Text)) {
        throw "$Label is dirty; refusing promotion: $($status.Text -replace "`r?`n", '; ')"
    }
}

function Assert-BetaSourceCheckout {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)]$BetaDeployment
    )
    $statusLines = @(Get-GitStatusLines -Root $Root)
    if ($statusLines.Count -eq 0) { return }

    $unsupported = [System.Collections.Generic.List[string]]::new()
    foreach ($line in $statusLines) {
        if ($line.Length -lt 3 -or $line.Substring(0, 2) -ne ' M' -or
            [System.IO.Path]::GetFileName($line.Substring(3)) -cne 'pom.xml') {
            [void]$unsupported.Add($line)
        }
    }
    $cached = Invoke-GitText -Root $Root -Arguments @('diff', '--cached', '--name-only')
    if (-not [string]::IsNullOrWhiteSpace($cached.Text)) { [void]$unsupported.Add("staged: $($cached.Text -replace "`r?`n", '; ')") }

    $manifestTimestamp = Get-PropertyText $BetaDeployment.Manifest 'buildTimestampPacific'
    if ([string]::IsNullOrWhiteSpace($manifestTimestamp)) { throw 'Beta deployment manifest lacks buildTimestampPacific for generated POM validation' }
    $expectedVersion = $BetaDeployment.Version
    foreach ($line in @($statusLines | Where-Object {
        $_.Length -ge 3 -and $_.Substring(0, 2) -eq ' M' -and
        [System.IO.Path]::GetFileName($_.Substring(3)) -ceq 'pom.xml'
    })) {
        $relativePath = $line.Substring(3)
        try {
            $headPom = Invoke-GitText -Root $Root -Arguments @('show', "HEAD:$relativePath")
            $expectedPom = Get-ExpectedBetaPomText -RelativePath $relativePath -HeadText $headPom.Text -Version $expectedVersion -Timestamp $manifestTimestamp
            $currentPom = [System.IO.File]::ReadAllText((Join-Path $Root $relativePath))
            $normalize = { param([string]$Text) ($Text -replace "`r`n?", "`n").TrimEnd() }
            if ((& $normalize $currentPom) -cne (& $normalize $expectedPom)) {
                [void]$unsupported.Add("$relativePath (does not match deployed version '$expectedVersion' and timestamp '$manifestTimestamp')")
            }
        } catch {
            [void]$unsupported.Add("$relativePath ($($_.Exception.Message))")
        }
    }
    if ($unsupported.Count -gt 0) {
        throw "Beta source checkout has unsupported changes; only generated POM version/timestamp deltas matching the deployed Beta manifest are allowed: $($unsupported -join '; ')"
    }
}

function Get-ExpectedBetaPomText {
    param(
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [Parameter(Mandatory = $true)][string]$HeadText,
        [Parameter(Mandatory = $true)][string]$Version,
        [Parameter(Mandatory = $true)][string]$Timestamp
    )

    $expected = $HeadText
    if ($RelativePath -ieq 'pom.xml') {
        $versionPattern = '<version>[^<]+</version>'
        $versionMatch = [regex]::Match($expected, $versionPattern)
        if (-not $versionMatch.Success) { throw 'root pom.xml lacks its project version' }
        $expected = ([regex]::new($versionPattern)).Replace($expected, "<version>$Version</version>", 1)

        $timestampPattern = '(?m)^([ \t]*<local-build-timestamp-pacific>)[^<]+(</local-build-timestamp-pacific>[ \t]*)(\r?)$'
        $timestampMatch = [regex]::Match($expected, $timestampPattern)
        if (-not $timestampMatch.Success) { throw 'root pom.xml lacks local-build-timestamp-pacific' }
        $timestampReplacement = $timestampMatch.Groups[1].Value + $Timestamp + $timestampMatch.Groups[2].Value + $timestampMatch.Groups[3].Value
        $expected = ([regex]::new($timestampPattern)).Replace($expected, $timestampReplacement, 1)
    } else {
        $parentPattern = '(?s)(<parent\b[^>]*>.*?<version>)[^<]+(</version>)'
        $parentMatch = [regex]::Match($expected, $parentPattern)
        if (-not $parentMatch.Success) { throw 'module pom.xml lacks a parent version' }
        $parentReplacement = $parentMatch.Groups[1].Value + $Version + $parentMatch.Groups[2].Value
        $expected = ([regex]::new($parentPattern)).Replace($expected, $parentReplacement, 1)
    }
    return $expected
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
    # Match the release artifact form and the checked-in local build form. Keep
    # the timestamp and Pacific zone suffix strict so arbitrary filenames do
    # not become promotion versions or branch names.
    $match = [regex]::Match(
        $JarName.Trim(),
        '^hs-script_(v\d+\.\d+\.\d+(?:-local-\d{8}-\d{6}(?:PDT|PST))?)\.jar$'
    )
    if (-not $match.Success) {
        throw "Beta artifact must use a numeric release version before RC promotion: $JarName"
    }
    return $match.Groups[1].Value
}

function Get-ZipEntryText {
    param([Parameter(Mandatory = $true)][string]$ArchivePath, [Parameter(Mandatory = $true)][string]$EntryName)
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        $entry = $archive.GetEntry($EntryName)
        if ($null -eq $entry) { throw "JAR entry is missing: $EntryName in $ArchivePath" }
        $reader = [System.IO.StreamReader]::new($entry.Open())
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $archive.Dispose() }
}

function Get-BuildInfo {
    param([Parameter(Mandatory = $true)][string]$JarPath)
    $values = @{}
    foreach ($line in (Get-ZipEntryText -ArchivePath $JarPath -EntryName 'build.info' -ErrorAction Stop) -split "`r?`n") {
        $separator = $line.IndexOf('=')
        if ($separator -gt 0) {
            $values[$line.Substring(0, $separator)] = $line.Substring($separator + 1).Trim()
        }
    }
    return $values
}

function Get-ManifestRuntimePath {
    param(
        [Parameter(Mandatory = $true)]$Manifest,
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$PropertyName,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $relative = Get-PropertyText $Manifest $PropertyName
    if ([string]::IsNullOrWhiteSpace($relative) -or [System.IO.Path]::IsPathRooted($relative) -or
        $relative.Contains('/') -and $relative.StartsWith('/')) {
        throw "$Label must be a runtime-relative path: $relative"
    }
    $resolved = [System.IO.Path]::GetFullPath((Join-Path $Root $relative))
    if (-not $resolved.StartsWith($Root.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label escapes its runtime: $relative"
    }
    return $resolved
}

function Assert-ManifestArtifacts {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)]$Manifest,
        [Parameter(Mandatory = $true)][string]$Label
    )
    foreach ($entry in @(
        @{ Property = 'appJar'; Hash = 'appJarSha256'; Label = "$Label application JAR" },
        @{ Property = 'strategyPluginLib'; Hash = 'strategyPluginSha256'; Label = "$Label strategy plugin lib" },
        @{ Property = 'strategyPlugin'; Hash = 'strategyPluginSha256'; Label = "$Label strategy plugin" },
        @{ Property = 'cardSdk'; Hash = 'cardSdkSha256'; Label = "$Label card SDK" }
    )) {
        $path = Get-ManifestRuntimePath -Manifest $Manifest -Root $Root -PropertyName $entry.Property -Label $entry.Label
        Assert-FileHash -Path $path -Expected (Get-PropertyText $Manifest $entry.Hash) -Label $entry.Label
    }
    if ($null -ne $Manifest.PSObject.Properties['runtimeLibs'] -and $null -ne $Manifest.runtimeLibs) {
        foreach ($property in $Manifest.runtimeLibs.PSObject.Properties) {
            $path = [System.IO.Path]::GetFullPath((Join-Path $Root ([string]$property.Name)))
            if (-not $path.StartsWith($Root.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
                throw "$Label runtime library escapes its runtime: $($property.Name)"
            }
            Assert-FileHash -Path $path -Expected ([string]$property.Value) -Label "$Label runtime library $($property.Name)"
        }
    }
}

function Assert-ArtifactIdentity {
    param(
        [Parameter(Mandatory = $true)][string]$JarPath,
        [Parameter(Mandatory = $true)][string]$JarName,
        [Parameter(Mandatory = $true)]$Manifest,
        [Parameter(Mandatory = $true)][string]$ExpectedChannel,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $expectedVersion = Get-VersionFromJarName $JarName
    $buildInfo = Get-BuildInfo -JarPath $JarPath
    Assert-Equals -Expected $expectedVersion -Actual ([string]$buildInfo['version']) -Label "$Label embedded version"
    Assert-Equals -Expected $ExpectedChannel -Actual ([string]$buildInfo['channel']) -Label "$Label embedded channel"
    $manifestTimestamp = Get-PropertyText $Manifest 'buildTimestampPacific'
    if ([string]::IsNullOrWhiteSpace($manifestTimestamp)) { throw "$Label manifest is missing buildTimestampPacific" }
    Assert-Equals -Expected $manifestTimestamp -Actual ([string]$buildInfo['buildTimestampPacific']) -Label "$Label embedded build timestamp"
}

function Get-ShortcutDirectories {
    param([string]$Root)
    if ([string]::IsNullOrWhiteSpace($Root)) {
        return @(
            [Environment]::GetFolderPath('Desktop'),
            (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs'),
            (Join-Path $env:APPDATA 'Microsoft\Internet Explorer\Quick Launch\User Pinned\TaskBar')
        )
    }
    $base = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
    return @((Join-Path $base 'Desktop'), (Join-Path $base 'Start Menu'), (Join-Path $base 'Taskbar'))
}

function Read-ShortcutContract {
    param([Parameter(Mandatory = $true)][string]$Path)
    $shell = New-Object -ComObject WScript.Shell
    try {
        $shortcut = $shell.CreateShortcut($Path)
        return [pscustomobject]@{
            TargetPath = [string]$shortcut.TargetPath
            Arguments = [string]$shortcut.Arguments
            WorkingDirectory = [string]$shortcut.WorkingDirectory
            IconLocation = [string]$shortcut.IconLocation
        }
    } finally {
        if ($null -ne $shell) { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($shell) }
    }
}

function Assert-LauncherContract {
    param([Parameter(Mandatory = $true)][string]$Root, [Parameter(Mandatory = $true)][string]$Label)
    $vbs = Join-Path $Root 'launch-as-admin.vbs'
    $ps = Join-Path $Root 'launch-newest-as-admin.ps1'
    $contract = Join-Path $Root 'deployment-contract.ps1'
    foreach ($path in @($vbs, $ps, $contract)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "$Label launcher file is missing: $path" }
    }
    $vbsText = Get-Content -LiteralPath $vbs -Raw
    $psText = Get-Content -LiteralPath $ps -Raw
    $contractText = Get-Content -LiteralPath $contract -Raw
    if ($vbsText -notmatch 'launch-newest-as-admin\.ps1') { throw "$Label VBS launcher does not delegate to the channel PowerShell launcher" }
    if ($psText -notmatch 'Resolve-Deployment\s+\$scriptDirectory' -or
        $psText -notmatch 'hs\.script\.deployment\.id' -or $psText -notmatch '\-jar') {
        throw "$Label PowerShell launcher does not resolve the manifest-backed JAR"
    }
    if ($contractText -notmatch 'function Resolve-Deployment') { throw "$Label deployment contract is missing Resolve-Deployment" }
}

function Assert-ShortcutContract {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$ShortcutName,
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$IconPath,
        [string]$ShortcutBase = ''
    )
    $directories = @(Get-ShortcutDirectories -Root $ShortcutBase)
    $expectedTarget = Join-Path $env:SystemRoot 'System32\wscript.exe'
    $expectedLauncher = Join-Path $Root 'launch-as-admin.vbs'
    $expectedArguments = '"' + $expectedLauncher + '"'
    $expectedPaths = @()
    foreach ($directory in $directories) {
        $path = Join-Path $directory $ShortcutName
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "$Label shortcut is missing: $path" }
        $expectedPaths += [System.IO.Path]::GetFullPath($path)
        $shortcut = Read-ShortcutContract -Path $path
        if (-not $expectedTarget.Equals([System.IO.Path]::GetFullPath($shortcut.TargetPath), [StringComparison]::OrdinalIgnoreCase)) {
            throw "$Label shortcut target $path mismatch: expected '$expectedTarget', actual '$($shortcut.TargetPath)'"
        }
        if (-not $expectedArguments.Equals($shortcut.Arguments, [StringComparison]::OrdinalIgnoreCase)) {
            throw "$Label shortcut arguments $path mismatch: expected '$expectedArguments', actual '$($shortcut.Arguments)'"
        }
        $workingDirectory = [System.IO.Path]::GetFullPath($shortcut.WorkingDirectory).TrimEnd('\')
        if (-not $Root.TrimEnd('\').Equals($workingDirectory, [StringComparison]::OrdinalIgnoreCase)) {
            throw "$Label shortcut working directory $path mismatch: expected '$Root', actual '$workingDirectory'"
        }
        if (-not $shortcut.IconLocation.StartsWith($IconPath, [StringComparison]::OrdinalIgnoreCase)) {
            throw "$Label shortcut icon $path mismatch: expected '$IconPath', actual '$($shortcut.IconLocation)'"
        }
    }

    $runtimeMarker = $Root.TrimEnd('\')
    $stale = @()
    foreach ($directory in $directories) {
        foreach ($candidate in @(Get-ChildItem -LiteralPath $directory -Filter '*.lnk' -File -ErrorAction SilentlyContinue)) {
            $candidateFullPath = [System.IO.Path]::GetFullPath($candidate.FullName)
            $shortcut = Read-ShortcutContract -Path $candidate.FullName
            $sameRuntime = ([System.IO.Path]::GetFullPath($shortcut.WorkingDirectory).TrimEnd('\') -ieq $runtimeMarker) -or
                $shortcut.Arguments.IndexOf($expectedLauncher, [StringComparison]::OrdinalIgnoreCase) -ge 0
            if ($sameRuntime -and $expectedPaths -notcontains $candidateFullPath) { $stale += $candidate.FullName }
        }
    }
    if ($stale.Count -gt 0) { throw "$Label has duplicate or stale shortcuts for the RC runtime: $($stale -join '; ')" }
}

function Assert-VerificationEvidence {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Path,
        [Parameter(Mandatory = $true)][string]$SourceBranch,
        [Parameter(Mandatory = $true)][string]$SourceCommit,
        [Parameter(Mandatory = $true)]$BetaDeployment
    )
    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw 'Online E2E verification evidence is required; pass -VerificationEvidencePath with a current gate JSON file'
    }
    $evidence = Read-JsonFile -Path ([System.IO.Path]::GetFullPath($Path)) -Label 'Online E2E verification evidence'
    Assert-Equals -Expected 'PASS' -Actual (Get-PropertyText $evidence 'status') -Label 'Online E2E evidence status'
    Assert-Equals -Expected 'beta' -Actual (Get-PropertyText $evidence 'releaseChannel') -Label 'Online E2E evidence channel'
    Assert-Equals -Expected $SourceBranch -Actual (Get-PropertyText $evidence 'sourceBranch') -Label 'Online E2E evidence source branch'
    Assert-Equals -Expected $SourceCommit -Actual (Get-PropertyText $evidence 'sourceCommit') -Label 'Online E2E evidence source commit'
    Assert-Equals -Expected $BetaDeployment.JarName -Actual (Get-PropertyText $evidence 'artifactName') -Label 'Online E2E evidence artifact'
    Assert-Equals -Expected (Get-PropertyText $BetaDeployment.Manifest 'appJarSha256') -Actual (Get-PropertyText $evidence 'artifactSha256') -Label 'Online E2E evidence artifact hash'
    if ([int]$evidence.gamesCompleted -lt 3 -or [int]$evidence.terminalMarkers -lt 3) { throw 'Online E2E evidence must prove at least three games and three terminal Power.log markers' }
    if ($evidence.scriptProcessStable -ne $true) { throw 'Online E2E evidence does not prove stable script process execution' }
    $screenshot = Get-PropertyText $evidence 'finalScreenshotPath'
    if ([string]::IsNullOrWhiteSpace($screenshot)) { throw 'Online E2E evidence is missing finalScreenshotPath' }
    if (-not [System.IO.Path]::IsPathRooted($screenshot)) { $screenshot = Join-Path (Split-Path -Parent ([System.IO.Path]::GetFullPath($Path))) $screenshot }
    if (-not (Test-Path -LiteralPath $screenshot -PathType Leaf)) { throw "Online E2E final screenshot is missing: $screenshot" }
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
    if ([System.IO.Path]::GetFileName($runtime) -ne 'Hearthstone Script Beta') {
        throw "Beta runtime must use the isolated Beta directory: $runtime"
    }
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
    Assert-ManifestArtifacts -Root $runtime -Manifest $manifest -Label 'Beta'
    Assert-ArtifactIdentity -JarPath $jarPath -JarName $jarName -Manifest $manifest -ExpectedChannel 'beta' -Label 'Beta artifact'

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

function Assert-RcDeployment {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [string]$SourceBranch = '',
        [string]$SourceCommit = '',
        [string]$ShortcutBase = '',
        [switch]$RequirePromotionSource
    )
    $manifestPath = Join-Path $Root 'deployment-manifest.json'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw "Release Candidate deployment manifest is missing: $manifestPath" }
    $manifest = Read-JsonFile -Path $manifestPath -Label 'Release Candidate deployment manifest'
    Assert-Equals -Expected 'release-candidate' -Actual (Get-PropertyText $manifest 'releaseChannel') -Label 'Release Candidate deployment channel'
    Assert-Equals -Expected 'Hearthstone Script Release Candidate' -Actual (Get-PropertyText $manifest 'runtimeDirectoryName') -Label 'Release Candidate deployment runtime identity'
    if ($RequirePromotionSource) {
        Assert-Equals -Expected $SourceBranch -Actual (Get-PropertyText $manifest 'promotionSourceBranch') -Label 'Release Candidate deployed source branch'
        Assert-Equals -Expected $SourceCommit -Actual (Get-PropertyText $manifest 'promotionSourceCommit') -Label 'Release Candidate deployed source commit'
    }
    $iconPath = Get-ManifestRuntimePath -Manifest $manifest -Root $Root -PropertyName 'iconFile' -Label 'Release Candidate icon'
    if (-not (Test-Path -LiteralPath $iconPath -PathType Leaf)) { throw "Release Candidate icon is missing: $iconPath" }
    $jarName = Get-PropertyText $manifest 'appJar'
    if ([string]::IsNullOrWhiteSpace($jarName) -or $jarName.Contains('\') -or $jarName.Contains('/')) { throw "Release Candidate manifest appJar must be runtime-local: $jarName" }
    $jarPath = Join-Path $Root $jarName
    Assert-ManifestArtifacts -Root $Root -Manifest $manifest -Label 'Release Candidate'
    Assert-ArtifactIdentity -JarPath $jarPath -JarName $jarName -Manifest $manifest -ExpectedChannel 'release-candidate' -Label 'Release Candidate artifact'
    Assert-LauncherContract -Root $Root -Label 'Release Candidate'
    Assert-ShortcutContract -Root $Root -ShortcutName 'Hearthstone Script Release Candidate.lnk' -Label 'Release Candidate' -IconPath $iconPath -ShortcutBase $ShortcutBase
}

function Test-RcDeployment {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$SourceBranch,
        [Parameter(Mandatory = $true)][string]$SourceCommit,
        [string]$ShortcutBase = ''
    )
    try {
        Assert-RcDeployment -Root $Root -SourceBranch $SourceBranch -SourceCommit $SourceCommit -ShortcutBase $ShortcutBase -RequirePromotionSource
        return $true
    } catch { Write-Verbose "Release Candidate validation failed: $($_.Exception.Message)"; return $false }
}

function Assert-RcProtectedDirectories {
    param([Parameter(Mandatory = $true)][string]$Root)
    foreach ($name in @('config', 'data', 'log', 'plugin')) {
        $path = Join-Path $Root $name
        if (-not (Test-Path -LiteralPath $path -PathType Container)) {
            throw "Release Candidate protected directory is missing; refusing legacy upgrade: $path"
        }
    }
}

function Get-RcProtectedState {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)]$Manifest
    )
    Assert-RcProtectedDirectories -Root $Root
    $managedPluginPaths = @(
        (Get-PropertyText $Manifest 'strategyPlugin').Replace('/', '\'),
        (Get-PropertyText $Manifest 'strategyPluginLib').Replace('/', '\')
    )
    $state = [System.Collections.Generic.List[object]]::new()
    foreach ($directory in @('config', 'data', 'log', 'plugin')) {
        $directoryRoot = Join-Path $Root $directory
        foreach ($file in @(Get-ChildItem -LiteralPath $directoryRoot -File -Recurse -ErrorAction Stop)) {
            $relative = [System.IO.Path]::GetRelativePath($Root, $file.FullName).Replace('/', '\')
            if ($managedPluginPaths -contains $relative) { continue }
            $hash = if ($relative -ieq 'config\script.ini') { '' } else { (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
            $state.Add([pscustomobject]@{ path = $relative; sha256 = $hash })
        }
    }
    foreach ($file in @(Get-ChildItem -LiteralPath $Root -File -Filter 'hs_cards.db.backup-*' -ErrorAction SilentlyContinue)) {
        $state.Add([pscustomobject]@{
            path = $file.Name
            sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        })
    }
    return @($state)
}

function Assert-RcProtectedState {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][object[]]$State
    )
    foreach ($entry in $State) {
        $path = Join-Path $Root ([string]$entry.path)
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Release Candidate protected file disappeared during upgrade: $path"
        }
        if (-not [string]::IsNullOrWhiteSpace([string]$entry.sha256)) {
            Assert-FileHash -Path $path -Expected ([string]$entry.sha256) -Label "Release Candidate protected file $($entry.path)"
        }
    }
}

function New-RcRollbackSnapshot {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)]$Manifest,
        [string]$ShortcutBase = ''
    )
    Assert-RcProtectedDirectories -Root $Root
    $deploymentId = Get-PropertyText $Manifest 'deploymentId'
    if ([string]::IsNullOrWhiteSpace($deploymentId)) { throw 'Legacy Release Candidate manifest lacks deploymentId; refusing upgrade snapshot' }
    $token = ($deploymentId -replace '[^A-Za-z0-9._-]', '_')
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmssfff'
    $snapshot = Join-Path (Split-Path -Parent $Root) "Hearthstone Script Release Candidate.rollback-$stamp-$token"
    if (Test-Path -LiteralPath $snapshot) { throw "Rollback snapshot path already exists; refusing to overwrite it: $snapshot" }
    New-Item -ItemType Directory -Path $snapshot -Force | Out-Null

    $copied = [System.Collections.Generic.List[string]]::new()
    foreach ($file in @(Get-ChildItem -LiteralPath $Root -File -ErrorAction Stop | Where-Object { $_.Name -notlike 'hs_cards.db' -and $_.Name -notlike 'hs_cards.db.backup-*' })) {
        Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $snapshot $file.Name) -Force
        $copied.Add($file.Name)
    }
    foreach ($directory in @('lib', 'plugin', 'resources', 'aot')) {
        $source = Join-Path $Root $directory
        if (Test-Path -LiteralPath $source -PathType Container) {
            Copy-Item -LiteralPath $source -Destination (Join-Path $snapshot $directory) -Recurse -Force
            $copied.Add("$directory\*")
        }
    }

    $shortcutCopies = [System.Collections.Generic.List[string]]::new()
    $shortcutDirectories = @(Get-ShortcutDirectories -Root $ShortcutBase)
    $shortcutDirectoryNames = @('Desktop', 'Start Menu', 'Taskbar')
    for ($index = 0; $index -lt $shortcutDirectories.Count; $index++) {
        $source = Join-Path $shortcutDirectories[$index] 'Hearthstone Script Release Candidate.lnk'
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Legacy Release Candidate shortcut disappeared before snapshot: $source" }
        $destinationDirectory = Join-Path (Join-Path $snapshot 'shortcuts') $shortcutDirectoryNames[$index]
        New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination (Join-Path $destinationDirectory (Split-Path -Leaf $source)) -Force
        $shortcutCopies.Add((Join-Path 'shortcuts' "$($shortcutDirectoryNames[$index])\Hearthstone Script Release Candidate.lnk"))
    }

    $protectedState = @(Get-RcProtectedState -Root $Root -Manifest $Manifest)
    $metadata = [ordered]@{
        schema = 1
        kind = 'release-candidate-rollback-snapshot'
        createdAt = (Get-Date).ToString('o')
        runtimeRoot = $Root
        deploymentId = $deploymentId
        managedFiles = @($copied)
        shortcutFiles = @($shortcutCopies)
        protectedState = @($protectedState)
        note = 'config, data, log, custom plugin files, and backup files remain in the live runtime and are verified after upgrade'
    }
    $metadata | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $snapshot 'rollback-manifest.json') -Encoding UTF8
    return [pscustomobject]@{ Root = $snapshot; ProtectedState = $protectedState }
}

function Assert-RcRuntimeIsSafe {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$SourceBranch,
        [Parameter(Mandatory = $true)][string]$SourceCommit,
        [string]$ShortcutBase = '',
        [switch]$AllowLegacyUpgrade
    )
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) { return $false }
    $entries = @(Get-ChildItem -LiteralPath $Root -Force -ErrorAction SilentlyContinue)
    if ($entries.Count -eq 0) { return $false }
    if (-not (Test-Path -LiteralPath (Join-Path $Root 'deployment-manifest.json') -PathType Leaf)) {
        throw "Existing Release Candidate runtime has no manifest; refusing to overwrite stale files: $Root"
    }
    try {
        Assert-RcDeployment -Root $Root -SourceBranch $SourceBranch -SourceCommit $SourceCommit -ShortcutBase $ShortcutBase -RequirePromotionSource
        return $false
    } catch {
        if (-not $AllowLegacyUpgrade) { throw }
        Assert-RcDeployment -Root $Root -SourceBranch '' -SourceCommit '' -ShortcutBase $ShortcutBase
        Assert-RcProtectedDirectories -Root $Root
        Write-Verbose "LEGACY_RC_VALIDATED_FOR_UPGRADE runtime=$Root"
        return $true
    }
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
$betaHead = (Invoke-GitText -Root $projectRoot -Arguments @('rev-parse', 'HEAD')).Text
$betaDeployment = Get-BetaDeployment -Root $projectRoot -Branch $betaBranch -Head $betaHead
Assert-BetaSourceCheckout -Root $projectRoot -BetaDeployment $betaDeployment
Assert-VerificationEvidence -Path $VerificationEvidencePath -SourceBranch $betaBranch -SourceCommit $betaHead -BetaDeployment $betaDeployment

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
 $legacyRcUpgrade = Assert-RcRuntimeIsSafe -Root $rcRoot -SourceBranch $betaBranch -SourceCommit $betaHead -ShortcutBase $ShortcutRoot -AllowLegacyUpgrade:$AllowLegacyRcUpgrade
if ($PreflightOnly) {
    Write-Output "PREFLIGHT_OK betaVersion=$($betaDeployment.Version) sourceCommit=$betaHead rcRuntime=$rcRoot legacyRcUpgrade=$legacyRcUpgrade"
    exit 0
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
if ($branchRef.ExitCode -eq 0 -and (Test-RcDeployment -Root $rcRoot -SourceBranch $betaBranch -SourceCommit $betaHead -ShortcutBase $ShortcutRoot)) {
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
$legacySnapshot = $null
if ($legacyRcUpgrade) {
    $legacyManifest = Read-JsonFile -Path (Join-Path $rcRoot 'deployment-manifest.json') -Label 'Legacy Release Candidate deployment manifest'
    $legacySnapshot = New-RcRollbackSnapshot -Root $rcRoot -Manifest $legacyManifest -ShortcutBase $ShortcutRoot
    Write-Output "RC_ROLLBACK_SNAPSHOT=$($legacySnapshot.Root)"
}
Write-Output "BUILD_AND_DEPLOY_INVOCATION=release-candidate worktree=$PromotionWorktree"
Invoke-BuildAndDeploy -Root $PromotionWorktree -Script $BuildScript
if (-not (Test-RcDeployment -Root $rcRoot -SourceBranch $betaBranch -SourceCommit $betaHead -ShortcutBase $ShortcutRoot)) {
    throw "Release Candidate deployment manifest did not validate against the promoted Beta source: $rcRoot"
}
if ($null -ne $legacySnapshot) {
    Assert-RcProtectedState -Root $rcRoot -State @($legacySnapshot.ProtectedState)
    Write-Output "RC_PROTECTED_STATE_VERIFIED=$rcRoot"
}
Write-Output "PROMOTION_COMPLETE branch=$ReleaseCandidateBranch sourceCommit=$betaHead runtime=$rcRoot"
