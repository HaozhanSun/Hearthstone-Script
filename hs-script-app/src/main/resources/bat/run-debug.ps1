$ErrorActionPreference = "Stop"

$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaPath = "C:\Program Files\Java\jdk-25.0.4\bin\java.exe"
$manifestPath = Join-Path $scriptDirectory "deployment-manifest.json"
$manifest = if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
    Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
} else {
    $null
}
$jar = if ($manifest -and $manifest.appJar) {
    Get-Item -LiteralPath (Join-Path $scriptDirectory ([string]$manifest.appJar)) -ErrorAction Stop
} else {
    Get-ChildItem -LiteralPath $scriptDirectory -Filter "hs-script_*.jar" -File |
        Where-Object { $_.Name -notmatch "\.before-|\.bak" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}
if (-not $jar) { throw "No deployed hs-script JAR was found in: $scriptDirectory" }
$jarName = $jar.Name
$jarPath = $jar.FullName
$logDirectory = Join-Path $scriptDirectory "log"
$scriptLog = Join-Path $logDirectory "hs_script.log"
$powerLogRoot = "D:\Hearthstone\Logs"
$e2ePlayerName = if ($env:HS_E2E_PLAYER) { $env:HS_E2E_PLAYER } else { "laz#12793" }
$testOnlySkipSurrender = $env:HS_E2E_SKIP_SURRENDER -eq "true"
$testOnlySkipPersistentStreakGuard = $env:HS_E2E_SKIP_PERSISTENT_STREAK -eq "true"
$gamesRequired = 2
$maxRestarts = 50
$runId = "{0}_{1}" -f (Get-Random -Minimum 10000 -Maximum 99999), (Get-Random -Minimum 1000 -Maximum 9999)
$runDirectory = Join-Path (Join-Path $logDirectory "e2e-runs") $runId
$consoleLog = Join-Path $runDirectory "java-console-debug.log"
$ledgerPath = Join-Path $runDirectory "run-ledger.jsonl"

New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null

function Write-LedgerEvent([string]$event, [hashtable]$fields = @{}) {
    $record = [ordered]@{
        timestamp = (Get-Date).ToString("o")
        runId = $runId
        event = $event
    }
    foreach ($entry in $fields.GetEnumerator()) { $record[$entry.Key] = $entry.Value }
    $line = ($record | ConvertTo-Json -Compress -Depth 6) + [Environment]::NewLine
    for ($retry = 0; $retry -lt 20; $retry++) {
        try {
            [System.IO.File]::AppendAllText($ledgerPath, $line, [System.Text.Encoding]::UTF8)
            return
        } catch {
            Start-Sleep -Milliseconds 100
        }
    }
    Write-Host ("E2E_LEDGER_WRITE_FAILED: " + $event)
}

## The ProcessStartInfo below supplies the working directory. Keeping the
## launcher in the caller's directory avoids permission failures from a
## secondary PowerShell session.
Set-Content -Path $consoleLog -Value @(
    "==== debug launcher start $(Get-Date -Format o) ====",
    "JAVA=$javaPath",
    "JAR=$jarName",
    "E2E watchdog max restarts=$maxRestarts",
    "E2E run id=$runId",
    "E2E run directory=$runDirectory",
    "E2E ledger=$ledgerPath",
    "E2E manifest=$manifestPath",
    "E2E wins required=$gamesRequired",
    "E2E consecutive valid wins required=$gamesRequired",
    "E2E surrender-after-out-card=false",
    "E2E test-only skip-surrender-policy=$testOnlySkipSurrender",
    "E2E test-only skip-persistent-streak-guard=$testOnlySkipPersistentStreakGuard",
    "E2E mulligan-screenshot=true",
    "E2E mulligan-input=SendInput-absolute"
)
Write-LedgerEvent "run-start" @{ jar = $jarName; jarPath = $jarPath; scriptDirectory = $scriptDirectory; powerLogRoot = $powerLogRoot; gamesRequired = $gamesRequired; maxRestarts = $maxRestarts }

function Write-Trace([string]$message) {
    # The monitor and the redirected Java output can finish at the same time.
    # Add-Content opens a new handle for every line and can lose the entire
    # watchdog on Windows when another reader briefly holds the file.  Retry
    # with an explicit append and keep the watchdog alive even if diagnostics
    # are temporarily unavailable.
    $line = $message + [Environment]::NewLine
    for ($retry = 0; $retry -lt 20; $retry++) {
        try {
            [System.IO.File]::AppendAllText($consoleLog, $line, [System.Text.Encoding]::UTF8)
            return
        } catch {
            Start-Sleep -Milliseconds 100
        }
    }
    Write-Host ("E2E_TRACE_WRITE_FAILED: " + $message)
}

function Append-ScriptLog([string]$message) {
    # hs_script.log is also held by Logback.  Never let a short-lived sharing
    # violation terminate the watchdog while it is recording a win marker.
    $line = $message + [Environment]::NewLine
    for ($retry = 0; $retry -lt 30; $retry++) {
        try {
            [System.IO.File]::AppendAllText($scriptLog, $line, [System.Text.Encoding]::UTF8)
            return $true
        } catch {
            Start-Sleep -Milliseconds 100
        }
    }
    Write-Trace ("E2E_SCRIPT_LOG_WRITE_FAILED: " + $message)
    return $false
}

function Test-ScriptWinMarker {
    # Logback can hold hs_script.log exclusively while it flushes an event.
    # A diagnostic read must never terminate the watchdog in that interval.
    for ($retry = 0; $retry -lt 20; $retry++) {
        try {
            if (Test-Path -LiteralPath $scriptLog) {
                return [bool](Select-String -LiteralPath $scriptLog -SimpleMatch -Quiet "E2E_WIN_RESULT $runId" -ErrorAction Stop)
            }
            return $false
        } catch {
            Start-Sleep -Milliseconds 100
        }
    }
    Write-Trace "E2E_SCRIPT_MARKER_READ_RETRY_EXHAUSTED path=$scriptLog"
    return $false
}

function Test-CurrentRunGameReady([int]$processId) {
    # A PLAYSTATE line can be produced by Hearthstone after the Java process
    # was restarted, or by a game that was already running before this test.
    # Only accept it after this exact JVM has attached to a live gameplay war.
    if (-not (Test-Path -LiteralPath $scriptLog)) { return $false }
    $stream = $null
    $reader = $null
    try {
        $stream = [System.IO.File]::Open(
            $scriptLog,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::ReadWrite
        )
        $length = $stream.Length
        $stream.Seek([Math]::Max(0L, $length - 1024L * 1024L), [System.IO.SeekOrigin]::Begin) | Out-Null
        $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8, $true, 4096, $true)
        $pattern = "pid=$processId .*mode=GAMEPLAY inWar=true"
        while ($null -ne ($line = $reader.ReadLine())) {
            if ($line -match $pattern) { return $true }
        }
        return $false
    } catch {
        return $false
    } finally {
        if ($reader) { $reader.Dispose() }
        elseif ($stream) { $stream.Dispose() }
    }
}

function Get-PowerLogWinLine {
    if (-not (Test-Path -LiteralPath $powerLogRoot)) { return $null }
    $powerLog = Get-ChildItem -LiteralPath $powerLogRoot -Recurse -Filter "Power.log" -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $powerLog) { return $null }
    $winToken = "Entity=$e2ePlayerName tag=PLAYSTATE value=WON"
    return Get-Content -LiteralPath $powerLog.FullName -Tail 400 -ErrorAction SilentlyContinue |
        Where-Object { $_.Contains("GameState.DebugPrintPower()") -and $_.Contains($winToken) } | Select-Object -Last 1
}

function Get-LatestPowerLog {
    if (-not (Test-Path -LiteralPath $powerLogRoot)) { return $null }
    return Get-ChildItem -LiteralPath $powerLogRoot -Recurse -Filter "Power.log" -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

function Get-PowerLogWinLinesAfter([string]$path, [long]$offset) {
    if (-not $path -or -not (Test-Path -LiteralPath $path)) { return @() }
    $winToken = "Entity=$e2ePlayerName tag=PLAYSTATE value=WON"
    $matches = [System.Collections.Generic.List[string]]::new()
    try {
        $length = (Get-Item -LiteralPath $path).Length
        if ($length -le $offset) { return @() }
        $stream = [System.IO.File]::Open($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        try {
            $stream.Seek($offset, [System.IO.SeekOrigin]::Begin) | Out-Null
            $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8, $true, 4096, $true)
            try {
                while ($null -ne ($line = $reader.ReadLine())) {
                    # Power.log repeats each state in a PowerTaskList block.
                    # Only the canonical GameState record represents the game
                    # result; counting the replayed PowerTaskList record would
                    # turn one win into two games.
                    if ($line.Contains("GameState.DebugPrintPower()") -and $line.Contains($winToken)) {
                        [void]$matches.Add($line)
                    }
                }
            } finally { $reader.Dispose() }
        } finally { $stream.Dispose() }
    } catch { }
    return $matches.ToArray()
}

if (-not (Test-Path -LiteralPath $javaPath)) { throw "java.exe was not found: $javaPath" }
if (-not (Test-Path -LiteralPath $jarPath)) { throw "The script JAR was not found: $jarPath" }

$attempt = 0
$powerLogWinLines = [System.Collections.Generic.List[string]]::new()
$scriptResultLines = [System.Collections.Generic.List[string]]::new()
$powerLogPath = $null
$powerLogBaselineOffset = 0L
$scriptLogBaselineLength = if (Test-Path -LiteralPath $scriptLog) { (Get-Item -LiteralPath $scriptLog).Length } else { 0L }
$consecutiveValidWins = 0
$validatedPowerWinCount = 0
while ($true) {
    $attempt++
    Write-Trace "==== Java attempt $attempt start $(Get-Date -Format o) ===="
    Write-LedgerEvent "attempt-start" @{ attempt = $attempt }
    $currentPowerLog = Get-LatestPowerLog
    if ($currentPowerLog) {
        if ($null -eq $powerLogPath -or $currentPowerLog.FullName -ne $powerLogPath) {
            $powerLogPath = $currentPowerLog.FullName
            $powerLogBaselineOffset = $currentPowerLog.Length
            Write-Trace "E2E_POWERLOG_BASELINE path=$powerLogPath offset=$powerLogBaselineOffset"
        } else {
            Write-Trace "E2E_POWERLOG_BASELINE_KEEP path=$powerLogPath offset=$powerLogBaselineOffset"
        }
    }
    $arguments = @(
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=log",
        "-XX:ErrorFile=log\hs_err_pid%p.log",
        "-Dhs.script.autostart=true",
        "-Dhs.script.e2e=true",
        "-Dhs.script.e2e.win-required=true",
        "-Dhs.script.e2e.skip-inject=true",
        "-Dhs.script.e2e.real-input=true",
        "-Dhs.script.e2e.native-click=true",
        "-Dhs.script.e2e.mulligan-robot=true",
        "-Dhs.script.mulligan-screenshot=true",
        "-Dhs.script.e2e.run-id=$runId",
        "-Djna.library.path=$scriptDirectory",
        "-jar",
        $jarPath,
        "--pause=false"
    )

    if ($testOnlySkipSurrender) {
        # JVM system properties must precede -jar.  Appending this after the
        # application arguments makes Java treat it as an app argument and
        # leaves the production surrender policy enabled during the harness.
        $jarIndex = [Array]::IndexOf([object[]]$arguments, "-jar")
        $arguments = @($arguments[0..($jarIndex - 1)]) +
            "-Dhs.script.e2e.skip-surrender-policy=true" +
            @($arguments[$jarIndex..($arguments.Count - 1)])
    }

    if ($testOnlySkipPersistentStreakGuard) {
        $jarIndex = [Array]::IndexOf([object[]]$arguments, "-jar")
        $arguments = @($arguments[0..($jarIndex - 1)]) +
            "-Dhs.script.e2e.skip-persistent-streak-guard=true" +
            @($arguments[$jarIndex..($arguments.Count - 1)])
    }

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $javaPath
    $startInfo.WorkingDirectory = $scriptDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.Arguments = ($arguments | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ }
    }) -join ' '

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) { throw "Failed to start Java" }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $winSeen = $false
    $gameReadySeen = $false

    while (-not $process.HasExited) {
        # Do not query MainWindowHandle/MainWindowTitle from the watchdog.
        # Those .NET properties cross the Win32 GUI boundary and can block or
        # destabilize a JavaFX process while it is changing stages. Process
        # liveness and the app log are sufficient for this monitor.
        $title = "not-polled"
        $handle = "not-polled"
        $tail = ""
        if (Test-Path -LiteralPath $scriptLog) {
            $tail = (Get-Content -LiteralPath $scriptLog -Tail 1 -ErrorAction SilentlyContinue) -join " "
            if (-not $gameReadySeen -and (Test-CurrentRunGameReady $process.Id)) {
                $gameReadySeen = $true
                Write-Trace "E2E_GAME_READY pid=$($process.Id) source=current-process-lifecycle"
                Write-LedgerEvent "game-ready" @{ attempt = $attempt; pid = $process.Id; powerLog = $powerLogPath }
            }
            # The application marker is useful diagnostics, but the watchdog
            # only accepts fresh authoritative PLAYSTATE=WON lines below.
        }
        # Hearthstone creates a fresh per-run Power.log after Java starts.
        # The pre-launch baseline can therefore point at the previous game;
        # rotate to the newest file while this exact JVM is still alive and
        # start that file at offset 0 so its authoritative wins are counted.
        $latestDuringRun = Get-LatestPowerLog
        if ($latestDuringRun -and $latestDuringRun.FullName -ne $powerLogPath) {
            $powerLogPath = $latestDuringRun.FullName
            $powerLogBaselineOffset = 0L
            Write-Trace "E2E_POWERLOG_ROTATE path=$powerLogPath offset=0 source=post-java-latest"
        }
        if ($gameReadySeen) {
            $powerWinLines = @(Get-PowerLogWinLinesAfter $powerLogPath $powerLogBaselineOffset)
            foreach ($powerWinLine in $powerWinLines) {
                if (-not ($powerLogWinLines -contains $powerWinLine)) {
                    [void]$powerLogWinLines.Add($powerWinLine)
                }
            }
            # Pair the application's accepted E2E result with a fresh
            # canonical GameState PLAYSTATE=WON line. Any rejected, lost, or
            # conceded result resets the consecutive-game streak.
            if (Test-Path -LiteralPath $scriptLog) {
                try {
                    $scriptLength = (Get-Item -LiteralPath $scriptLog).Length
                    if ($scriptLength -lt $scriptLogBaselineLength) {
                        $scriptLogBaselineLength = 0L
                    }
                    if ($scriptLength -gt $scriptLogBaselineLength) {
                        $stream = [System.IO.File]::Open($scriptLog, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
                        try {
                            $stream.Seek($scriptLogBaselineLength, [System.IO.SeekOrigin]::Begin) | Out-Null
                            $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8, $true, 4096, $true)
                            try {
                                while ($null -ne ($resultLine = $reader.ReadLine())) {
                                    if (-not ($scriptResultLines -contains $resultLine) -and $resultLine.Contains($runId)) {
                                        [void]$scriptResultLines.Add($resultLine)
                                        if ($resultLine.Contains("E2E_GAME_RESULT_REJECTED") -or
                                            $resultLine.Contains("E2E_GAME_RESULT_LOSS") -or
                                            $resultLine.Contains("E2E_GAME_RESULT_CONCEDED")) {
                                            $consecutiveValidWins = 0
                                            $validatedPowerWinCount = $powerLogWinLines.Count
                                            Write-Trace "E2E_CONSECUTIVE_RESET $runId reason=non-winning-or-rejected-result line=$resultLine"
                                        } elseif ($resultLine.Contains("E2E_WIN_RESULT")) {
                                            if ($powerLogWinLines.Count -gt $validatedPowerWinCount) {
                                                $validatedPowerWinCount++
                                                $consecutiveValidWins++
                                                Write-Trace "E2E_WIN_RESULT game=$consecutiveValidWins/$gamesRequired $runId, authoritative Power.log PLAYSTATE=WON paired with accepted script result: $resultLine"
                                            } else {
                                                Write-Trace "E2E_WIN_RESULT_UNPAIRED $runId reason=no-new-authoritative-powerlog-win line=$resultLine"
                                            }
                                        }
                                    }
                                }
                            } finally { $reader.Dispose() }
                        } finally { $stream.Dispose() }
                        $scriptLogBaselineLength = $scriptLength
                    }
                } catch { }
            }
            $winSeen = $consecutiveValidWins -ge $gamesRequired
        }
        Write-Trace ("E2E_MONITOR pid={0} alive={1} mainWindowHandle={2} title={3} wins={4}/{5} authoritativeWins={6} winSeen={7} tail={8}" -f $process.Id, (-not $process.HasExited), $handle, $title, $consecutiveValidWins, $gamesRequired, $powerLogWinLines.Count, $winSeen, $tail)
        if ($winSeen) { break }
        Start-Sleep -Seconds 5
    }

    if (-not $process.HasExited) {
        $process.WaitForExit(120000)
    }
    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    if ($stdout) { Write-Trace ("JAVA_STDOUT_BEGIN attempt=$attempt`n$stdout`nJAVA_STDOUT_END attempt=$attempt") }
    if ($stderr) { Write-Trace ("JAVA_STDERR_BEGIN attempt=$attempt`n$stderr`nJAVA_STDERR_END attempt=$attempt") }
    Write-Trace "==== Java attempt $attempt exit $(Get-Date -Format o) code=$($process.ExitCode) winSeen=$winSeen ===="
    Write-LedgerEvent "attempt-exit" @{ attempt = $attempt; pid = $process.Id; exitCode = $process.ExitCode; winSeen = $winSeen; authoritativeWins = $powerLogWinLines.Count; consecutiveValidWins = $consecutiveValidWins; powerLog = $powerLogPath }

    if ($winSeen) {
        Write-Trace "==== E2E $gamesRequired authoritative win markers found; watchdog complete ===="
        Write-LedgerEvent "run-complete" @{ attempts = $attempt; exitCode = 0; authoritativeWins = $powerLogWinLines.Count; consecutiveValidWins = $consecutiveValidWins }
        exit 0
    }

    if ($attempt -gt $maxRestarts) {
        Write-Trace "==== watchdog exhausted without $gamesRequired authoritative E2E win markers ===="
        Write-LedgerEvent "run-exhausted" @{ attempts = $attempt; exitCode = 20; authoritativeWins = $powerLogWinLines.Count; consecutiveValidWins = $consecutiveValidWins }
        exit 20
    }
    Write-Trace "==== restarting Java after premature exit or non-win result; retry=$attempt ===="
    Write-LedgerEvent "restart-requested" @{ attempt = $attempt; reason = "premature-exit-or-non-win" }
    Start-Sleep -Seconds 2
}
