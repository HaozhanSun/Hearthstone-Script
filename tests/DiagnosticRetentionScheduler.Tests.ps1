$ErrorActionPreference = 'Stop'
function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "ASSERTION_FAILED: $Message" }
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$source = Join-Path $projectRoot 'hs-script-app\src\main\resources\bat\diagnostic-retention.ps1'
$deploySource = Join-Path $projectRoot 'build-and-deploy.ps1'
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hs-retention-test-" + [guid]::NewGuid().ToString('N'))
$runtimeRoot = Join-Path $tempRoot 'Hearthstone Script'
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null

try {
    $manifest = [ordered]@{ releaseChannel = 'stable'; runtimeDirectoryName = 'Hearthstone Script' }
    $manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtimeRoot 'deployment-manifest.json') -Encoding UTF8
    Copy-Item -LiteralPath $source -Destination (Join-Path $runtimeRoot 'diagnostic-retention.ps1')

    $taskPlan = & $source -Channel stable -RuntimeRoot $runtimeRoot -DescribeTask
    $userSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    Assert-True ($taskPlan.Name -eq "HearthstoneScript.DiagnosticRetention.$userSid.stable") 'task name is isolated by user and channel'
    Assert-True ($taskPlan.Execute -like '*\powershell.exe') 'task uses Windows PowerShell without launching the app'
    Assert-True ($taskPlan.Arguments -like '*-NonInteractive*diagnostic-retention.ps1*-Channel stable*') 'task runs the checked-in cleaner noninteractively for its own channel'
    Assert-True (($taskPlan.DailyTriggerHours -join ',') -eq '00:00,06:00,12:00,18:00') 'task is scheduled every six hours'
    Assert-True ($taskPlan.LogonTriggerCount -eq 1) 'task catches up at user logon'
    Assert-True ($taskPlan.LogonType -eq 'S4U') 'task runs as current user without storing a password'
    Assert-True ($taskPlan.RunLevel -eq 'Limited') 'task runs without elevation'

    foreach ($channelInfo in @(
        [pscustomobject]@{ Channel = 'beta'; RuntimeName = 'Hearthstone Script Beta' },
        [pscustomobject]@{ Channel = 'release-candidate'; RuntimeName = 'Hearthstone Script Release Candidate' }
    )) {
        $otherRuntime = Join-Path $tempRoot $channelInfo.RuntimeName
        New-Item -ItemType Directory -Path $otherRuntime -Force | Out-Null
        $otherManifest = [ordered]@{ releaseChannel = $channelInfo.Channel; runtimeDirectoryName = $channelInfo.RuntimeName }
        $otherManifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $otherRuntime 'deployment-manifest.json') -Encoding UTF8
        $otherTaskPlan = & $source -Channel $channelInfo.Channel -RuntimeRoot $otherRuntime -DescribeTask
        Assert-True ($otherTaskPlan.Name -eq "HearthstoneScript.DiagnosticRetention.$userSid.$($channelInfo.Channel)") "task name isolates user and $($channelInfo.Channel)"
        Assert-True ($otherTaskPlan.Arguments -like "*-Channel $($channelInfo.Channel)*") "task arguments isolate $($channelInfo.Channel)"
        Assert-True (($otherTaskPlan.DailyTriggerHours -join ',') -eq '00:00,06:00,12:00,18:00') "task cadence applies to $($channelInfo.Channel)"
    }
    $deployText = Get-Content -LiteralPath $deploySource -Raw
    Assert-True ($deployText.Contains("'diagnostic-retention.ps1'")) 'release path copies the cleaner entrypoint'
    Assert-True ($deployText.Contains('-InstallTask -VerifyTask')) 'release path installs and verifies the task'

    $logRoot = Join-Path $runtimeRoot 'log'
    New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
    $expired = Join-Path $logRoot 'hs_script-old.log'
    $fresh = Join-Path $logRoot 'debug-screenshots\fresh.png'
    $active = Join-Path $logRoot 'hs_script.log'
    $settings = Join-Path $logRoot 'config\settings.json'
    $evidence = Join-Path $runtimeRoot 'evidence\e2e\old.png'
    $ocr = Join-Path $runtimeRoot 'ocr_res\old.png'
    $ocrJson = Join-Path $runtimeRoot 'ocr_res\state.json'
    $mouseTrace = Join-Path $runtimeRoot 'debug\mouse\old.csv'
    $otherDebug = Join-Path $runtimeRoot 'debug\other\old.png'
    $resourceImage = Join-Path $runtimeRoot 'resources\old.png'
    foreach ($path in @($expired, $fresh, $active, $settings, $evidence, $ocr, $ocrJson, $mouseTrace, $otherDebug, $resourceImage)) {
        New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
        [System.IO.File]::WriteAllText($path, 'test')
    }
    $now = [datetime]::UtcNow
    [System.IO.File]::SetLastWriteTimeUtc($expired, $now.AddDays(-31))
    foreach ($path in @($active, $settings, $evidence, $ocr, $ocrJson, $mouseTrace, $otherDebug, $resourceImage)) {
        [System.IO.File]::SetLastWriteTimeUtc($path, $now.AddDays(-31))
    }

    $output = & $source -Channel stable -RuntimeRoot $runtimeRoot -NowUtc $now
    Assert-True (-not (Test-Path -LiteralPath $expired)) 'manual entrypoint deletes only expired allowed diagnostics'
    Assert-True (Test-Path -LiteralPath $fresh) 'manual entrypoint retains fresh diagnostics'
    Assert-True (Test-Path -LiteralPath $active) 'manual entrypoint retains active app log'
    Assert-True (Test-Path -LiteralPath $settings) 'manual entrypoint retains nested settings data'
    Assert-True (-not (Test-Path -LiteralPath $evidence)) 'manual entrypoint cleans approved evidence root outside log'
    Assert-True (-not (Test-Path -LiteralPath $ocr)) 'manual entrypoint cleans approved OCR screenshot root'
    Assert-True (-not (Test-Path -LiteralPath $mouseTrace)) 'manual entrypoint cleans approved debug mouse traces'
    Assert-True (Test-Path -LiteralPath $ocrJson) 'OCR root accepts PNG only'
    Assert-True (Test-Path -LiteralPath $otherDebug) 'neighboring debug directories remain out of scope'
    Assert-True (Test-Path -LiteralPath $resourceImage) 'application resources remain out of scope'
    Assert-True (($output -join ' ') -match 'deleted=4') 'manual entrypoint reports its cleanup count'
    'Diagnostic retention scheduler and entrypoint tests passed.'
} finally {
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}
