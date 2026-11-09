$ErrorActionPreference = 'Stop'

function Assert-FileHash([string]$Path, [string]$Expected, [string]$Label) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Label is missing: $Path" }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $Expected.ToLowerInvariant()) { throw "$Label hash mismatch: expected=$Expected actual=$actual" }
}

function Resolve-Deployment([string]$ScriptDirectory) {
    $root = [System.IO.Path]::GetFullPath($ScriptDirectory).TrimEnd('\')
    $rootName = Split-Path -Leaf $root
    $manifestPath = Join-Path $root 'deployment-manifest.json'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw "Deployment manifest was not found: $manifestPath" }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ([int]$manifest.schema -ne 1) { throw "Unsupported deployment manifest schema: $($manifest.schema)" }
    $channelRoots = @{
        stable = 'Hearthstone Script'
        beta = 'Hearthstone Script Beta'
        'release-candidate' = 'Hearthstone Script Release Candidate'
    }
    $manifestChannel = ([string]$manifest.releaseChannel).ToLowerInvariant()
    if (-not $channelRoots.ContainsKey($manifestChannel)) {
        throw "Unsupported deployment release channel: $manifestChannel"
    }
    if ($rootName -cne $channelRoots[$manifestChannel] -or
        [string]$manifest.runtimeDirectoryName -cne $channelRoots[$manifestChannel]) {
        throw "Deployment channel/root mismatch: channel=$manifestChannel root=$rootName declared=$($manifest.runtimeDirectoryName)"
    }

    $appJar = Join-Path $root ([string]$manifest.appJar)
    $strategyLib = Join-Path $root ([string]$manifest.strategyPluginLib)
    $strategyPlugin = Join-Path $root ([string]$manifest.strategyPlugin)
    $cardSdk = Join-Path $root ([string]$manifest.cardSdk)
    foreach ($path in @($appJar, $strategyLib, $strategyPlugin, $cardSdk)) {
        $resolved = [System.IO.Path]::GetFullPath($path)
        if (-not $resolved.StartsWith($root + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Manifest path escapes deployment root: $resolved"
        }
    }
    Assert-FileHash $appJar ([string]$manifest.appJarSha256) 'application JAR'
    Assert-FileHash $strategyLib ([string]$manifest.strategyPluginSha256) 'strategy plugin in lib'
    Assert-FileHash $strategyPlugin ([string]$manifest.strategyPluginSha256) 'strategy plugin'
    Assert-FileHash $cardSdk ([string]$manifest.cardSdkSha256) 'card SDK'
    if ($manifest.runtimeLibs) {
        foreach ($property in $manifest.runtimeLibs.PSObject.Properties) {
            Assert-FileHash (Join-Path $root ([string]$property.Name)) ([string]$property.Value) "runtime library $($property.Name)"
        }
    }
    [pscustomobject]@{
        Root = $root
        ManifestPath = $manifestPath
        Manifest = $manifest
        AppJar = $appJar
        DeploymentId = [string]$manifest.deploymentId
    }
}

function Get-HsScriptCanonicalRuntimeRoot([string]$ScriptDirectory) {
    return [System.IO.Path]::GetFullPath($ScriptDirectory).TrimEnd('\')
}

function Get-HsScriptRuntimeGateName([string]$ScriptDirectory) {
    $root = (Get-HsScriptCanonicalRuntimeRoot $ScriptDirectory).ToLowerInvariant()
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($root)
        $digest = ([System.BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', '')
        return "Global\HearthstoneScriptRuntime_$($digest.Substring(0, 24))"
    } finally { $sha.Dispose() }
}

function Enter-HsScriptRuntimeGate([string]$ScriptDirectory) {
    $mutex = [System.Threading.Mutex]::new($false, (Get-HsScriptRuntimeGateName $ScriptDirectory))
    try {
        try { $acquired = $mutex.WaitOne(0) }
        catch [System.Threading.AbandonedMutexException] { $acquired = $true }
        if (-not $acquired) { throw "Channel runtime is being deployed or launched: $ScriptDirectory" }
        return $mutex
    } catch {
        $mutex.Dispose()
        throw
    }
}

function Exit-HsScriptRuntimeGate($Mutex) {
    if ($null -eq $Mutex) { return }
    try { $Mutex.ReleaseMutex() } finally { $Mutex.Dispose() }
}

function Get-HsScriptNativeExecutablePath([int]$ProcessId) {
    # PROCESS_QUERY_LIMITED_INFORMATION permits a read-only image-name lookup
    # for an elevated peer when Process.MainModule and CIM hide the path from a
    # medium-integrity launcher. The returned path remains only one component
    # of the existing exact PID/start-time/path marker verification.
    if ($null -eq ('HsScriptProcessImagePathNative' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

public static class HsScriptProcessImagePathNative
{
    private const uint PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(uint desiredAccess, bool inheritHandle, uint processId);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool QueryFullProcessImageName(
        IntPtr process,
        uint flags,
        StringBuilder executablePath,
        ref uint size);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(IntPtr handle);

    public static string ReadImagePath(uint processId)
    {
        IntPtr handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, processId);
        if (handle == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
        try
        {
            var path = new StringBuilder(32768);
            uint length = (uint)path.Capacity;
            if (!QueryFullProcessImageName(handle, 0, path, ref length))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            return path.ToString();
        }
        finally { CloseHandle(handle); }
    }
}
'@
    }
    $path = [HsScriptProcessImagePathNative]::ReadImagePath([uint32]$ProcessId)
    if ([string]::IsNullOrWhiteSpace($path)) {
        throw "QueryFullProcessImageNameW returned an empty executable path for process $ProcessId"
    }
    return [System.IO.Path]::GetFullPath($path)
}

function Get-HsScriptExecutablePath(
    $Process,
    [int]$ProcessId,
    [scriptblock]$CimLookup = { param($ClassName, $Filter) Get-CimInstance -ClassName $ClassName -Filter $Filter -ErrorAction Stop },
    [scriptblock]$NativeImagePathLookup = ${function:Get-HsScriptNativeExecutablePath}
) {
    # Process.Path is not consistently populated by the .NET provider on Windows.
    # MainModule is authoritative when accessible; CIM and then the read-only
    # limited-information API are narrow fallbacks for access/provider gaps.
    # Never return an empty identity as if it were verified.
    try {
        $path = [string]$Process.MainModule.FileName
        if (-not [string]::IsNullOrWhiteSpace($path)) { return [System.IO.Path]::GetFullPath($path) }
    } catch {
        # Access denied or a process exiting during the query can still allow a
        # lower-rights image-name read. Any ultimate failure remains fail-closed.
    }
    try {
        $record = & $CimLookup 'Win32_Process' "ProcessId = $ProcessId"
        $path = [string]$record.ExecutablePath
        if (-not [string]::IsNullOrWhiteSpace($path)) { return [System.IO.Path]::GetFullPath($path) }
    } catch {
    }
    try {
        $path = [string](& $NativeImagePathLookup $ProcessId)
        if (-not [string]::IsNullOrWhiteSpace($path)) { return [System.IO.Path]::GetFullPath($path) }
    } catch {
        $nativeFailure = $_.Exception.Message
        throw "Could not resolve executable path for process $ProcessId from MainModule, CIM, or QueryFullProcessImageNameW: $nativeFailure"
    }
    throw "Could not resolve executable path for process $ProcessId from MainModule, CIM, or QueryFullProcessImageNameW"
}

function Get-HsScriptProcessIdentity([int]$ProcessId) {
    $process = $null
    try {
        $process = [System.Diagnostics.Process]::GetProcessById($ProcessId)
        return [pscustomobject]@{
            ProcessId = $process.Id
            ProcessStartTimeUtc = $process.StartTime.ToUniversalTime().ToString('o')
            ExecutablePath = Get-HsScriptExecutablePath $process $ProcessId
        }
    } catch [System.ArgumentException] {
        return $null
    } finally {
        if ($null -ne $process) { $process.Dispose() }
    }
}

function Get-HsScriptProcessStartTimeUtc([int]$ProcessId) {
    $process = $null
    try {
        $process = [System.Diagnostics.Process]::GetProcessById($ProcessId)
        return $process.StartTime.ToUniversalTime().ToString('o')
    } catch [System.ArgumentException] {
        return $null
    } finally {
        if ($null -ne $process) { $process.Dispose() }
    }
}

function Get-HsScriptMarkerProcessStartTimeUtc([string]$RawMarker, [string]$MarkerPath) {
    # ConvertFrom-Json on PowerShell 7 may eagerly turn a JSON ISO-8601 UTC
    # string into DateTime. Casting that value back to string loses its offset
    # (for example, "...Z" becomes a locale-local timestamp) and makes an
    # exact foreign-channel marker look stale. Read the persisted JSON token
    # directly, require its offset, and normalize it once at this boundary.
    $startMatch = [regex]::Match($RawMarker, '"processStartTimeUtc"\s*:\s*"(?<value>[^"]+)"')
    if (-not $startMatch.Success) {
        throw "Runtime ownership marker has an invalid process start time: $MarkerPath"
    }
    $startText = $startMatch.Groups['value'].Value
    if ($startText -notmatch '(?i)(Z|[+-]\d{2}:\d{2})$') {
        throw "Runtime ownership marker has an offset-less process start time: $MarkerPath"
    }
    try {
        return [DateTimeOffset]::Parse(
            $startText,
            [System.Globalization.CultureInfo]::InvariantCulture,
            [System.Globalization.DateTimeStyles]::AllowWhiteSpaces
        ).ToUniversalTime()
    } catch {
        throw "Runtime ownership marker has an invalid process start time: $MarkerPath"
    }
}

function Get-HsScriptRuntimeMarkers([string]$ScriptDirectory) {
    $root = Get-HsScriptCanonicalRuntimeRoot $ScriptDirectory
    $parent = Split-Path -Parent $root
    $roots = @($root)
    foreach ($name in @('Hearthstone Script', 'Hearthstone Script Beta', 'Hearthstone Script Release Candidate')) {
        $candidate = Join-Path $parent $name
        if ($candidate -ine $root -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            $roots += $candidate
        }
    }
    $markers = [System.Collections.Generic.List[object]]::new()
    foreach ($candidateRoot in $roots) {
        $markerPath = Join-Path $candidateRoot 'hs-script.pid.json'
        if (-not (Test-Path -LiteralPath $markerPath -PathType Leaf)) { continue }
        try {
            $rawMarker = [System.IO.File]::ReadAllText($markerPath)
            $marker = $rawMarker | ConvertFrom-Json -ErrorAction Stop
        }
        catch { throw "Runtime ownership marker is unreadable; refusing to assume idle: $markerPath" }
        $channelConfigPath = Join-Path $candidateRoot 'release-channel.json'
        if (-not (Test-Path -LiteralPath $channelConfigPath -PathType Leaf)) {
            throw "Runtime channel configuration is missing beside ownership marker: $channelConfigPath"
        }
        try { $runtimeChannelConfig = Get-Content -LiteralPath $channelConfigPath -Raw | ConvertFrom-Json -ErrorAction Stop }
        catch { throw "Runtime channel configuration is unreadable: $channelConfigPath" }
        $recordedRoot = [string]$marker.runtimeRoot
        $processId = 0
        if (-not [int]::TryParse([string]$marker.processId, [ref]$processId) -or $processId -le 0 -or
            [string]::IsNullOrWhiteSpace([string]$marker.executablePath) -or
            [string]::IsNullOrWhiteSpace([string]$marker.releaseChannel) -or
            ([string]$marker.releaseChannel).ToLowerInvariant() -cne ([string]$runtimeChannelConfig.channel).ToLowerInvariant() -or
            [string]$marker.runtimeDirectoryName -cne [string]$runtimeChannelConfig.runtimeDirectoryName -or
            (Get-HsScriptCanonicalRuntimeRoot $recordedRoot) -ine (Get-HsScriptCanonicalRuntimeRoot $candidateRoot)
        ) { throw "Runtime ownership marker is incomplete or mismatched; refusing to assume idle: $markerPath" }
        $processStartTimeUtc = Get-HsScriptMarkerProcessStartTimeUtc $rawMarker $markerPath
        $markers.Add([pscustomobject]@{
            RuntimeRoot = Get-HsScriptCanonicalRuntimeRoot $candidateRoot
            ReleaseChannel = ([string]$marker.releaseChannel).ToLowerInvariant()
            ProcessId = $processId
            ProcessStartTimeUtc = $processStartTimeUtc.ToString('o')
            ExecutablePath = [System.IO.Path]::GetFullPath([string]$marker.executablePath)
        })
    }
    return $markers.ToArray()
}

function Resolve-HsScriptProcessAssessment(
    [string]$ScriptDirectory,
    [object[]]$ProcessRecords,
    [object[]]$Markers,
    [scriptblock]$GetIdentity = ${function:Get-HsScriptProcessIdentity}
) {
    $root = Get-HsScriptCanonicalRuntimeRoot $ScriptDirectory
    $verifiedMarkers = @{}
    $blockingIds = [System.Collections.Generic.List[int]]::new()
    $staleMarkerPids = [System.Collections.Generic.HashSet[int]]::new()
    $ambiguousMarkerPids = [System.Collections.Generic.HashSet[int]]::new()
    foreach ($marker in $Markers) {
        $targetPid = [int]$marker.ProcessId
        $recordExists = @($ProcessRecords | Where-Object { [int]$_.ProcessId -eq $targetPid }).Count -gt 0
        $identity = $null
        try { $identity = & $GetIdentity $targetPid } catch { $identity = $null }
        if ($null -eq $identity) {
            if ($recordExists) { [void]$ambiguousMarkerPids.Add($targetPid) }
            else { [void]$staleMarkerPids.Add($targetPid) }
            continue
        }
        if ([string]::IsNullOrWhiteSpace([string]$identity.ProcessStartTimeUtc) -or
            [string]::IsNullOrWhiteSpace([string]$identity.ExecutablePath)) {
            [void]$ambiguousMarkerPids.Add($targetPid)
            continue
        }
        $startMatches = $false
        try {
            $markerStart = [DateTimeOffset]::Parse([string]$marker.ProcessStartTimeUtc).ToUniversalTime()
            $processStart = [DateTimeOffset]::Parse([string]$identity.ProcessStartTimeUtc).ToUniversalTime()
            $startMatches = [Math]::Abs(($markerStart - $processStart).TotalMilliseconds) -le 1000
        } catch { }
        $pathMatches = -not [string]::IsNullOrWhiteSpace([string]$identity.ExecutablePath) -and
            ([System.IO.Path]::GetFullPath([string]$identity.ExecutablePath) -ieq [string]$marker.ExecutablePath)
        if ($startMatches -and $pathMatches) {
            $verifiedMarkers[$targetPid] = $marker
            if ([string]$marker.RuntimeRoot -ieq $root) { [void]$blockingIds.Add($targetPid) }
        }
        else { [void]$staleMarkerPids.Add($targetPid) }
    }

    foreach ($process in $ProcessRecords) {
        $targetPid = [int]$process.ProcessId
        $name = [string]$process.Name
        if ($name -ieq 'Hearthstone.exe') {
            return [pscustomobject]@{ Status = 'GAME_PROCESS_ACTIVE'; ProcessIds = @($targetPid); Reason = 'Hearthstone process may own an active queue' }
        }
        if ($name -notin @('java.exe', 'javaw.exe')) { continue }
        $commandLine = [string]$process.CommandLine
        if ($ambiguousMarkerPids.Contains($targetPid)) {
            return [pscustomobject]@{ Status = 'AMBIGUOUS'; ProcessIds = @($targetPid); Reason = 'launcher PID exists but process identity is unavailable' }
        }
        if ($verifiedMarkers.ContainsKey($targetPid)) {
            $markerRoot = [string]$verifiedMarkers[$targetPid].RuntimeRoot
            if ($markerRoot -ieq $root) { continue }
            if (-not [string]::IsNullOrWhiteSpace($commandLine) -and
                $commandLine.IndexOf($root, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -and
                $commandLine -match 'hs-script_.*\.jar') {
                return [pscustomobject]@{ Status = 'AMBIGUOUS'; ProcessIds = @($targetPid); Reason = 'verified foreign marker conflicts with current runtime CommandLine' }
            }
            continue
        }
        if ([string]::IsNullOrWhiteSpace($commandLine)) {
            if ($staleMarkerPids.Contains($targetPid)) {
                return [pscustomobject]@{ Status = 'AMBIGUOUS'; ProcessIds = @($targetPid); Reason = 'stale PID marker proves reuse but blank CommandLine cannot identify the new channel' }
            }
            return [pscustomobject]@{ Status = 'AMBIGUOUS'; ProcessIds = @($targetPid); Reason = 'JVM CommandLine is blank and no verified channel marker identifies it' }
        }

        if ($commandLine.IndexOf($root, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -and
            $commandLine -match 'hs-script_.*\.jar') {
            [void]$blockingIds.Add($targetPid)
            continue
        }
        # A visible command line proves a foreign channel only when it names
        # one of the canonical channel roots. A jar name without a root is
        # still ambiguous and must not be treated as an idle runtime.
        if ($commandLine -match 'hs-script_.*\.jar') {
            if ($commandLine -match 'Hearthstone Script(?: Beta| Release Candidate)?[\\/]hs-script_.*\.jar') { continue }
            return [pscustomobject]@{ Status = 'AMBIGUOUS'; ProcessIds = @($targetPid); Reason = 'application JAR is visible but its channel runtime root is unknown' }
        }
        if ($staleMarkerPids.Contains($targetPid)) { continue }
    }
    if ($blockingIds.Count -gt 0) {
        return [pscustomobject]@{ Status = 'RUNTIME_ACTIVE'; ProcessIds = $blockingIds.ToArray(); Reason = 'channel-owned JVM is still running' }
    }
    return [pscustomobject]@{ Status = 'IDLE'; ProcessIds = @(); Reason = 'no identified channel JVM or Hearthstone process' }
}

function Assert-HsScriptRuntimeIdle([string]$ScriptDirectory) {
    $processes = @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop)
    $markers = @(Get-HsScriptRuntimeMarkers $ScriptDirectory)
    $assessment = Resolve-HsScriptProcessAssessment $ScriptDirectory $processes $markers
    if ($assessment.Status -ne 'IDLE') {
        throw "Refusing runtime operation: status=$($assessment.Status) pids=$($assessment.ProcessIds -join ',') reason=$($assessment.Reason)"
    }
    return $assessment
}
