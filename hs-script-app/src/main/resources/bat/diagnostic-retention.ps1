[CmdletBinding()]
param(
    [ValidateSet('stable', 'beta', 'release-candidate')]
    [string]$Channel,
    [string]$RuntimeRoot = (Split-Path -Parent $MyInvocation.MyCommand.Path),
    [string]$SharedGameLogRoot = 'D:\Hearthstone\Logs',
    [string]$TaskNameOverride = '',
    [datetime]$NowUtc = [datetime]::UtcNow,
    [int]$MaxDeletes = 25000,
    [switch]$InstallTask,
    [switch]$VerifyTask,
    [switch]$DescribeTask
)

$ErrorActionPreference = 'Stop'
$RetentionDays = 30
$TaskPrefix = 'HearthstoneScript.DiagnosticRetention.'
$SupportedExtensions = @('.log', '.jsonl', '.png', '.jpg', '.jpeg', '.webp', '.txt', '.state', '.json', '.csv', '.tsv')
$RuntimeDiagnosticDirectories = @(
    'app', 'debug-screenshots', 'decision-trace', 'e2e-evidence', 'game-results',
    'mcts-deck-profile', 'mcts-replay', 'mulligan', 'offline-rank-two-roi',
    'rank-ocr-preprocess', 'unknown-cards', 'unknown-states'
)
$ArtifactCategories = @($RuntimeDiagnosticDirectories | Where-Object { $_ -ne 'app' })

function Get-CanonicalPath([string]$Path) {
    return [System.IO.Path]::GetFullPath($Path).TrimEnd('\')
}

function Get-RuntimeKey([string]$Path) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes((Get-CanonicalPath $Path).ToLowerInvariant()))
        return (([System.BitConverter]::ToString($hash) -replace '-', '').Substring(0, 24))
    } finally { $sha.Dispose() }
}

function Assert-DeploymentIdentity([string]$Root, [string]$ExpectedChannel) {
    $manifestPath = Join-Path $Root 'deployment-manifest.json'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw "Deployment manifest missing: $manifestPath" }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $runtimeNames = @{
        stable = 'Hearthstone Script'
        beta = 'Hearthstone Script Beta'
        'release-candidate' = 'Hearthstone Script Release Candidate'
    }
    if ([string]$manifest.releaseChannel -cne $ExpectedChannel -or
        [string]$manifest.runtimeDirectoryName -cne $runtimeNames[$ExpectedChannel] -or
        (Split-Path -Leaf $Root) -cne $runtimeNames[$ExpectedChannel]) {
        throw "Runtime identity mismatch; refusing diagnostic cleanup: root=$Root channel=$ExpectedChannel"
    }
}

function Test-ReparsePath([string]$Path) {
    $root = [System.IO.Path]::GetPathRoot($Path)
    $current = $root
    $relative = $Path.Substring($root.Length).TrimStart('\')
    foreach ($part in ($relative -split '[\\/]')) {
        if ([string]::IsNullOrWhiteSpace($part)) { continue }
        $current = Join-Path $current $part
        if (-not (Test-Path -LiteralPath $current)) { return $false }
        $attributes = [System.IO.File]::GetAttributes($current)
        if (($attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { return $true }
    }
    return $false
}

function Test-AllowedDiagnostic([string]$Path, [string]$Root, [string]$Scope) {
    $prefix = $Root.TrimEnd('\') + '\'
    if (-not $Path.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) { return $false }
    $relative = $Path.Substring($prefix.Length)
    $parts = $relative -split '[\\/]'
    if ($parts.Count -lt 1) { return $false }
    $extension = [System.IO.Path]::GetExtension($Path).ToLowerInvariant()
    if ($extension -notin $SupportedExtensions) { return $false }
    $name = [System.IO.Path]::GetFileName($Path).ToLowerInvariant()
    if ($name -in @('config.json', 'settings.json', 'user-data.json', 'statistics.db', 'deployment-manifest.json') -or
        $name.EndsWith('.rollback') -or $name.Contains('rollback-backup')) { return $false }
    switch ($Scope) {
        'runtime' { return ($parts.Count -eq 1 -or $parts[0].ToLowerInvariant() -in $RuntimeDiagnosticDirectories) }
        'evidence' { return $true }
        'ocr' { return $extension -eq '.png' }
        'debug-mouse' { return $extension -in @('.csv', '.png') }
        'external-log' {
            if ($parts.Count -eq 1) { return $extension -eq '.log' }
            return ($parts.Count -eq 2 -and $parts[0] -ieq 'app' -and $extension -eq '.log')
        }
        'artifact' { return $true }
        'shared-game-log' { return $extension -eq '.log' }
        default { return $false }
    }
}

function Test-ActiveFile([string]$Path, [string[]]$ActivePaths) {
    $full = (Get-CanonicalPath $Path)
    foreach ($activePath in $ActivePaths) {
        if ($full -ieq (Get-CanonicalPath $activePath)) { return $true }
    }
    return $false
}

function Test-ExclusiveOpen([string]$Path) {
    $stream = $null
    try {
        $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
        return $true
    } catch {
        return $false
    } finally {
        if ($null -ne $stream) { $stream.Dispose() }
    }
}

function Write-DurableSummary([string]$Path, [string]$Line) {
    $directory = Split-Path -Parent $Path
    if (Test-ReparsePath $directory) { throw "Diagnostic summary directory is a reparse point: $directory" }
    if ((Test-Path -LiteralPath $Path) -and (Test-ReparsePath $Path)) { throw "Diagnostic summary file is a reparse point: $Path" }
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $normalized = ($Line -replace '[\r\n]+', ' ').Trim()
    if ($normalized.Length -gt 2048) { $normalized = $normalized.Substring(0, 2048) }
    if ((Test-Path -LiteralPath $Path -PathType Leaf) -and (Get-Item -LiteralPath $Path).Length -ge 262144) {
        $tail = @(Get-Content -LiteralPath $Path -Tail 100 -ErrorAction Stop)
        [System.IO.File]::WriteAllLines($Path, [string[]]$tail, [System.Text.UTF8Encoding]::new($false))
    }
    [System.IO.File]::AppendAllText($Path, $normalized + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
}

function Invoke-DiagnosticRetention([string]$ExpectedChannel, [string]$Root, [datetime]$Now, [int]$Limit, [string]$SharedRoot) {
    $Root = Get-CanonicalPath $Root
    Assert-DeploymentIdentity $Root $ExpectedChannel
    $runtimeLog = Join-Path $Root 'log'
    $summaryDirectory = $runtimeLog
    $roots = @(
        [pscustomobject]@{ Path = $runtimeLog; Scope = 'runtime' },
        [pscustomobject]@{ Path = (Join-Path $Root 'evidence'); Scope = 'evidence' },
        [pscustomobject]@{ Path = (Join-Path $Root 'ocr_res'); Scope = 'ocr' },
        [pscustomobject]@{ Path = (Join-Path (Join-Path $Root 'debug') 'mouse'); Scope = 'debug-mouse' },
        [pscustomobject]@{ Path = (Get-CanonicalPath $SharedRoot); Scope = 'shared-game-log' }
    )
    $active = [System.Collections.Generic.List[string]]::new()
    $active.Add((Join-Path $runtimeLog 'hs_script.log'))

    if ($ExpectedChannel -eq 'beta') {
        $runtimeKey = Get-RuntimeKey $Root
        $betaLogs = Join-Path (Join-Path $env:USERPROFILE 'Documents') "HearthstoneScriptLogs\Beta\$runtimeKey"
        $betaArtifacts = Join-Path (Join-Path $env:USERPROFILE 'Documents') "HearthstoneScriptArtifacts\Beta\$runtimeKey"
        # The active deployment launcher may select a different fixed volume. Resolve it with the same checked-in policy.
        $contract = Join-Path $Root 'beta-temp-policy.ps1'
        if (Test-Path -LiteralPath $contract -PathType Leaf) {
            . $contract
            $volumes = @(Get-CimInstance -ClassName Win32_LogicalDisk -Filter 'DriveType=3' -ErrorAction Stop | Select-Object DeviceID, FreeSpace, DriveType)
            $betaLogs = Get-HsScriptBetaLogDirectory -Channel beta -RuntimeKey $runtimeKey -RuntimeDirectory $Root -FixedVolumes $volumes
            $betaArtifacts = Get-HsScriptBetaArtifactRoot -Channel beta -RuntimeKey $runtimeKey -RuntimeDirectory $Root -FixedVolumes $volumes
        }
        $roots += [pscustomobject]@{ Path = (Join-Path $betaLogs 'runtime-log'); Scope = 'runtime' }
        $summaryDirectory = Join-Path $betaLogs 'runtime-log'
        $roots += [pscustomobject]@{ Path = $betaLogs; Scope = 'external-log' }
        $active.Add((Join-Path $betaLogs 'runtime-log\hs_script.log'))
        $active.Add((Join-Path $betaLogs 'app\hs_script.log'))
        $active.Add((Join-Path $betaLogs 'guardian-state.json'))
        $active.Add((Join-Path $betaLogs 'launcher-selection.log'))
        if (-not [string]::IsNullOrWhiteSpace([string]$betaArtifacts)) {
            foreach ($category in $ArtifactCategories) {
                $roots += [pscustomobject]@{ Path = (Join-Path $betaArtifacts $category); Scope = 'artifact' }
            }
        }
    }

    $summaryName = 'diagnostic-retention-' + $Now.ToLocalTime().ToString('yyyyMMdd-HH') + '.log'
    $summaryPath = Join-Path $summaryDirectory $summaryName
    $active.Add($summaryPath)

    $cutoff = $Now.ToUniversalTime().AddDays(-$RetentionDays)
    $deleted = 0
    $bytes = [long]0
    $errors = 0
    foreach ($candidateRoot in $roots) {
        if ($deleted -ge $Limit -or -not (Test-Path -LiteralPath $candidateRoot.Path -PathType Container)) { continue }
        $rootPath = Get-CanonicalPath $candidateRoot.Path
        if (Test-ReparsePath $rootPath) { continue }
        $stack = [System.Collections.Generic.Stack[string]]::new()
        $stack.Push($rootPath)
        while ($stack.Count -gt 0 -and $deleted -lt $Limit) {
            $directory = $stack.Pop()
            try { $entries = [System.IO.Directory]::EnumerateFileSystemEntries($directory) } catch { $errors++; continue }
            foreach ($entry in $entries) {
                if ($deleted -ge $Limit) { break }
                try {
                    $attributes = [System.IO.File]::GetAttributes($entry)
                    if (($attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { continue }
                    if (($attributes -band [System.IO.FileAttributes]::Directory) -ne 0) {
                        $leaf = [System.IO.Path]::GetFileName($entry)
                        if ($candidateRoot.Scope -eq 'external-log' -and $directory -ieq $rootPath -and $leaf -ine 'app') { continue }
                        if ($candidateRoot.Scope -eq 'runtime' -and $directory -ieq $rootPath -and $leaf.ToLowerInvariant() -notin $RuntimeDiagnosticDirectories) { continue }
                        $stack.Push($entry)
                        continue
                    }
                    if (-not (Test-AllowedDiagnostic $entry $rootPath $candidateRoot.Scope)) { continue }
                    $info = [System.IO.FileInfo]::new($entry)
                    if ($info.LastWriteTimeUtc -ge $cutoff) { continue }
                    if (Test-ActiveFile $entry $active.ToArray()) { continue }
                    if (-not (Test-ExclusiveOpen $entry)) { continue }
                    $length = $info.Length
                    [System.IO.File]::Delete($entry)
                    $deleted++
                    $bytes += $length
                } catch { $errors++ }
            }
        }
    }
    $summary = "DIAGNOSTIC_RETENTION channel=$ExpectedChannel time=$($Now.ToUniversalTime().ToString('o')) cutoff=$($cutoff.ToString('o')) deleted=$deleted bytesDeleted=$bytes errors=$errors"
    Write-DurableSummary $summaryPath $summary
    Write-Output $summary
}

function Get-TaskName([string]$TaskChannel) {
    $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    return "$TaskPrefix$sid.$TaskChannel"
}

function Resolve-TaskAccountSid([string]$Identity) {
    $identity = $Identity.Trim()
    if ($identity -match '^S-\d-') {
        return ([System.Security.Principal.SecurityIdentifier]::new($identity)).Value
    }
    return ([System.Security.Principal.NTAccount]::new($identity)).Translate([System.Security.Principal.SecurityIdentifier]).Value
}

function Get-LogonTriggerUserIds([string]$TaskName) {
    $xmlText = Export-ScheduledTask -TaskName $TaskName -ErrorAction Stop
    [xml]$taskXml = $xmlText
    $namespaces = [System.Xml.XmlNamespaceManager]::new($taskXml.NameTable)
    $namespaces.AddNamespace('task', 'http://schemas.microsoft.com/windows/2004/02/mit/task')
    return @($taskXml.SelectNodes('//task:LogonTrigger/task:UserId', $namespaces) | ForEach-Object { $_.InnerText.Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Get-DailyTriggerHours([object[]]$Triggers) {
    return @($Triggers | Where-Object { $_.CimClass.CimClassName -eq 'MSFT_TaskDailyTrigger' } |
        ForEach-Object { ([datetimeoffset]::Parse([string]$_.StartBoundary)).ToLocalTime().ToString('HH:mm') } |
        Sort-Object -Unique)
}

function Get-TaskDefinition([string]$TaskChannel, [string]$Root, [string]$SharedRoot) {
    $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $script = Join-Path $Root 'diagnostic-retention.ps1'
    $arguments = "-WindowStyle Hidden -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$script`" -Channel $TaskChannel -RuntimeRoot `"$Root`" -SharedGameLogRoot `"$SharedRoot`""
    $action = New-ScheduledTaskAction -Execute $powershell -Argument $arguments -WorkingDirectory $Root
    $triggers = @(
        (New-ScheduledTaskTrigger -Daily -At '00:00'),
        (New-ScheduledTaskTrigger -Daily -At '06:00'),
        (New-ScheduledTaskTrigger -Daily -At '12:00'),
        (New-ScheduledTaskTrigger -Daily -At '18:00'),
        (New-ScheduledTaskTrigger -AtLogOn -User ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name))
    )
    $principal = New-ScheduledTaskPrincipal -UserId ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 1) -MultipleInstances IgnoreNew
    return New-ScheduledTask -Action $action -Trigger $triggers -Principal $principal -Settings $settings
}

if ([string]::IsNullOrWhiteSpace($Channel)) { throw 'Channel is required.' }
$RuntimeRoot = Get-CanonicalPath $RuntimeRoot
Assert-DeploymentIdentity $RuntimeRoot $Channel
if ([string]::IsNullOrWhiteSpace($TaskNameOverride)) {
    $taskName = Get-TaskName $Channel
} else {
    if ($TaskNameOverride -notmatch '^CodexProbe\.DiagnosticRetention\.[0-9a-fA-F]{32}$') {
        throw 'TaskNameOverride is restricted to unique CodexProbe diagnostic-retention test tasks.'
    }
    $taskName = $TaskNameOverride
}

if ($DescribeTask) {
    $definition = Get-TaskDefinition $Channel $RuntimeRoot $SharedGameLogRoot
    [pscustomobject]@{
        Name = $taskName
        Execute = $definition.Actions[0].Execute
        Arguments = $definition.Actions[0].Arguments
        TriggerCount = $definition.Triggers.Count
        DailyTriggerHours = (Get-DailyTriggerHours $definition.Triggers)
        LogonTriggerCount = @($definition.Triggers | Where-Object { $_.CimClass.CimClassName -eq 'MSFT_TaskLogonTrigger' }).Count
        LogonTriggerUserIds = @($definition.Triggers | Where-Object { $_.CimClass.CimClassName -eq 'MSFT_TaskLogonTrigger' } | ForEach-Object { $_.UserId })
        StartWhenAvailable = [bool]$definition.Settings.StartWhenAvailable
        RunLevel = [string]$definition.Principal.RunLevel
        LogonType = [string]$definition.Principal.LogonType
        UserId = [string]$definition.Principal.UserId
    }
    exit 0
}
if ($InstallTask) {
    $definition = Get-TaskDefinition $Channel $RuntimeRoot $SharedGameLogRoot
    Register-ScheduledTask -TaskName $taskName -InputObject $definition -Force | Out-Null
}
if ($VerifyTask -or $InstallTask) {
    $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($null -eq $task) { throw "Diagnostic retention task is missing: $taskName" }
    if ([string]$task.State -eq 'Disabled') { throw "Diagnostic retention task is disabled: $taskName" }
    $expectedScript = Join-Path $RuntimeRoot 'diagnostic-retention.ps1'
    if ($task.Actions.Count -ne 1 -or $task.Actions[0].Execute -notlike '*\powershell.exe' -or
        $task.Actions[0].Arguments -notlike "*`"$expectedScript`"*" -or
        $task.Actions[0].Arguments -notlike "*-Channel $Channel*") {
        throw "Diagnostic retention task action mismatch: $taskName"
    }
    $hours = @(Get-DailyTriggerHours $task.Triggers)
    if (($hours -join ',') -ne '00:00,06:00,12:00,18:00' -or
        @($task.Triggers | Where-Object { $_.CimClass.CimClassName -eq 'MSFT_TaskLogonTrigger' }).Count -lt 1) {
        throw "Diagnostic retention task cadence/logon triggers incomplete: $taskName"
    }
    $currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $currentUserSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    try {
        $principalSid = Resolve-TaskAccountSid ([string]$task.Principal.UserId)
        $logonTriggerUsers = @(Get-LogonTriggerUserIds $taskName)
        $logonTriggerSids = @($logonTriggerUsers | ForEach-Object { Resolve-TaskAccountSid $_ } | Sort-Object -Unique)
    } catch {
        throw "Diagnostic retention task identity could not be resolved: $taskName ($($_.Exception.Message))"
    }
    if ($principalSid -ne $currentUserSid -or
        [string]$task.Principal.LogonType -ne 'Interactive' -or
        [string]$task.Principal.RunLevel -ne 'Limited' -or
        $logonTriggerUsers.Count -ne 1 -or $logonTriggerSids.Count -ne 1 -or $logonTriggerSids[0] -ne $currentUserSid) {
        throw "Diagnostic retention task principal/logon trigger is not scoped to the current least-privilege user: $taskName"
    }
    if (-not [bool]$task.Settings.StartWhenAvailable) { throw "Diagnostic retention task does not catch up missed runs: $taskName" }
    Write-Output "DIAGNOSTIC_RETENTION_TASK_VERIFIED name=$taskName runtime=$RuntimeRoot principalSid=$currentUserSid logonTriggerSid=$($logonTriggerSids[0])"
    exit 0
}

Invoke-DiagnosticRetention $Channel $RuntimeRoot $NowUtc $MaxDeletes $SharedGameLogRoot
