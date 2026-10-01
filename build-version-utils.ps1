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

function Get-ReleaseBuildMetadata(
    [string]$CurrentVersion,
    [string]$DeployedVersion,
    [datetime]$Now,
    [string]$PacificZoneName
) {
    if ($PacificZoneName -notin @('PDT', 'PST')) {
        throw "Unsupported Pacific timezone label: $PacificZoneName"
    }

    $version = $CurrentVersion
    if (-not [string]::IsNullOrWhiteSpace($DeployedVersion)) {
        $version = Get-NextApplicationVersion $CurrentVersion $DeployedVersion $Now
    }

    return [pscustomobject]@{
        Version = $version
        BuildTimestampPacific = "$($Now.ToString('yyyy-MM-dd HH:mm:ss')) $PacificZoneName"
    }
}

function Set-PomBuildTimestampPacific([string]$PomText, [string]$BuildTimestampPacific) {
    $pattern = '(?s)(<local-build-timestamp-pacific>)[^<]*(</local-build-timestamp-pacific>)'
    if (-not [regex]::IsMatch($PomText, $pattern)) {
        throw 'Root POM local-build-timestamp-pacific property was not found'
    }
    $updated = [regex]::new($pattern).Replace(
        $PomText,
        { param($match) $match.Groups[1].Value + $BuildTimestampPacific + $match.Groups[2].Value },
        1
    )
    return $updated
}

function Get-BuildInfoTimestampPacific([string]$BuildInfoText) {
    $match = [regex]::Match($BuildInfoText, '(?m)^buildTimestampPacific=(.+)$')
    if (-not $match.Success) { throw 'build.info buildTimestampPacific property was not found' }
    return $match.Groups[1].Value.Trim()
}
