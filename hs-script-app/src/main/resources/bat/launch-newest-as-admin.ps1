[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaPath = "C:\Program Files\Java\jdk-25.0.4\bin\javaw.exe"
$logDirectory = Join-Path $scriptDirectory "log"
$selectionLog = Join-Path $logDirectory "launcher-selection.log"

$jar = Get-ChildItem -LiteralPath $scriptDirectory -Filter "hs-script_*.jar" -File |
    Where-Object { $_.Name -notmatch "\.before-|\.bak" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) { throw "No deployed hs-script JAR was found in: $scriptDirectory" }
if (-not (Test-Path -LiteralPath $javaPath)) { throw "javaw.exe was not found: $javaPath" }

$replaced = [System.Collections.Generic.List[int]]::new()
$processes = Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'"
foreach ($process in $processes) {
    $commandLine = [string]$process.CommandLine
    $window = Get-Process -Id ([int]$process.ProcessId) -ErrorAction SilentlyContinue
    $isVisibleHsScript = $null -ne $window -and $window.MainWindowTitle -eq "hs-script"
    $isOlderHsScript = $commandLine -match 'hs-script_.*\.jar' -and $commandLine -notlike "*$($jar.FullName)*"
    $isUnknownHsScript = [string]::IsNullOrWhiteSpace($commandLine) -and $isVisibleHsScript
    if ($isOlderHsScript -or $isUnknownHsScript) {
        Stop-Process -Id ([int]$process.ProcessId) -Force -ErrorAction SilentlyContinue
        [void]$replaced.Add([int]$process.ProcessId)
    }
}

Start-Sleep -Milliseconds 750
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
Add-Content -LiteralPath $selectionLog -Value ("{0} selected={1} replacedPids={2}" -f (Get-Date -Format o), $jar.FullName, ($replaced -join ','))

$arguments = '-Dhs.script.launch.source=shortcut -Djna.library.path="' +
    $scriptDirectory + '" -jar "' + $jar.FullName + '" --pause=false'
Start-Process -FilePath $javaPath -ArgumentList $arguments -WorkingDirectory $scriptDirectory
