$ErrorActionPreference = 'Stop'

function Assert-FileHash([string]$Path, [string]$Expected, [string]$Label) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Label is missing: $Path" }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $Expected.ToLowerInvariant()) { throw "$Label hash mismatch: expected=$Expected actual=$actual" }
}

function Resolve-Deployment([string]$ScriptDirectory) {
    $root = [System.IO.Path]::GetFullPath($ScriptDirectory).TrimEnd('\')
    $manifestPath = Join-Path $root 'deployment-manifest.json'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw "Deployment manifest was not found: $manifestPath" }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ([int]$manifest.schema -ne 1) { throw "Unsupported deployment manifest schema: $($manifest.schema)" }

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

function Stop-ManagedHsScriptProcesses([string]$ScriptDirectory) {
    $root = [System.IO.Path]::GetFullPath($ScriptDirectory).TrimEnd('\')
    $targets = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -in @('java.exe', 'javaw.exe') -and
        ([string]$_.CommandLine).IndexOf($root, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        ([string]$_.CommandLine) -match 'hs-script_.*\.jar'
    })
    $stopped = [System.Collections.Generic.List[int]]::new()
    foreach ($target in $targets) {
        Stop-Process -Id ([int]$target.ProcessId) -Force -ErrorAction SilentlyContinue
        [void]$stopped.Add([int]$target.ProcessId)
    }
    Remove-Item -LiteralPath (Join-Path $root 'hs-script.pid.json') -Force -ErrorAction SilentlyContinue
    return $stopped.ToArray()
}
