[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaPath = "C:\Program Files\Java\jdk-25.0.4\bin\javaw.exe"
$logDirectory = Join-Path $scriptDirectory "log"
$selectionLog = Join-Path $logDirectory "launcher-selection.log"
. (Join-Path $scriptDirectory 'deployment-contract.ps1')
$deployment = Resolve-Deployment $scriptDirectory
if (-not (Test-Path -LiteralPath $javaPath)) { throw "javaw.exe was not found: $javaPath" }

$replaced = @(Stop-ManagedHsScriptProcesses $scriptDirectory)
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
Add-Content -LiteralPath $selectionLog -Value ("{0} selected={1} deploymentId={2} replacedPids={3}" -f (Get-Date -Format o), $deployment.AppJar, $deployment.DeploymentId, ($replaced -join ','))

$arguments = '-Dhs.script.launch.source=shortcut -Djna.library.path="' +
    $scriptDirectory + '" -Dhs.script.deployment.id="' + $deployment.DeploymentId +
    '" -Dhs.script.deployment.manifest="' + $deployment.ManifestPath + '" -jar "' + $deployment.AppJar + '" --pause=false'
Start-Process -FilePath $javaPath -ArgumentList $arguments -WorkingDirectory $scriptDirectory
