function Get-PomVersion([string]$Text) {
    $match = [regex]::Match($Text, '(?s)(<artifactId>hs-script</artifactId>\s*<version>)([^<]+)(</version>)')
    if (-not $match.Success) { throw 'Root hs-script version was not found in pom.xml' }
    return $match.Groups[2].Value.Trim()
}

function Get-BaseVersion([string]$Version) {
    $match = [regex]::Match($Version, '^v(?<major>\d+)\.(?<minor>\d+)\.(?<patch>\d+)(?:-local-\d{8}-\d{6}(?:PDT|PST))?$')
    if (-not $match.Success) { throw "Unsupported application version: $Version" }
    return [version]::new(
        [int]$match.Groups['major'].Value,
        [int]$match.Groups['minor'].Value,
        [int]$match.Groups['patch'].Value
    )
}

function Get-DeployedVersionFromJarName([string]$JarName) {
    if ([string]::IsNullOrWhiteSpace($JarName)) { throw 'Deployment manifest appJar is empty' }
    $match = [regex]::Match(
        $JarName.Trim(),
        '^hs-script_(v\d+\.\d+\.\d+(?:-local-\d{8}-\d{6}(?:PDT|PST))?)\.jar$'
    )
    if (-not $match.Success) { throw "Unsupported deployed appJar name: $JarName" }
    return $match.Groups[1].Value
}

function Get-NextApplicationVersion([string]$CurrentVersion, [string]$DeployedVersion, [datetime]$Now) {
    $currentBase = Get-BaseVersion $CurrentVersion
    $deployedBase = Get-BaseVersion $DeployedVersion
    if ($currentBase -gt $deployedBase) { return $CurrentVersion }

    $zoneName = if ([System.TimeZoneInfo]::Local.IsDaylightSavingTime($Now)) { 'PDT' } else { 'PST' }
    return "v$($deployedBase.Major).$($deployedBase.Minor).$($deployedBase.Build + 1)-local-$($Now.ToString('yyyyMMdd-HHmmss'))$zoneName"
}
