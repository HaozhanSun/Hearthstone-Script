$ErrorActionPreference = 'Stop'

function ConvertTo-IniPath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return '' }
    # INI values are read with backslash escaping disabled. Forward slashes
    # keep Windows drive letters and spaces lossless without escaping ':'.
    return $Path.Replace('\', '/')
}

function Get-IniValue([string]$Content, [string]$Key) {
    $pattern = "(?m)^\s*$([regex]::Escape($Key))\s*=\s*(.*?)\s*$"
    $match = [regex]::Match($Content, $pattern)
    if (-not $match.Success) { return '' }
    return $match.Groups[1].Value.Trim().Trim('"')
}

function ConvertFrom-IniPath([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return '' }
    $value = $Value.Trim().Trim('"').Replace('/', '\')
    # Older ini writers escaped the drive colon and sometimes doubled the
    # remaining separators. Accept that legacy representation when reading a
    # fallback config, while keeping UNC prefixes untouched.
    $value = [regex]::Replace($value, '\\u([0-9a-fA-F]{4})', {
        param($match) ([char][Convert]::ToInt32($match.Groups[1].Value, 16)).ToString()
    })
    $value = [regex]::Replace($value, '^([A-Za-z])\\+:', '$1:')
    $drive = [regex]::Match($value, '^([A-Za-z]:)(.*)$')
    if ($drive.Success) {
        $value = $drive.Groups[1].Value +
            ([regex]::Replace($drive.Groups[2].Value, '\\{2,}', '\'))
    }
    return $value
}

function Test-ValidGamePath([string]$Path) {
    if (-not (Test-WellFormedWindowsPath $Path)) { return $false }
    try { $resolved = [System.IO.Path]::GetFullPath($Path) } catch { return $false }
    return (Test-Path -LiteralPath $resolved -PathType Container) -and
        (Test-Path -LiteralPath (Join-Path $resolved 'Hearthstone.exe') -PathType Leaf)
}

function Test-ValidPlatformPath([string]$Path) {
    if (-not (Test-WellFormedWindowsPath $Path)) { return $false }
    try { $resolved = [System.IO.Path]::GetFullPath($Path) } catch { return $false }
    return (Test-Path -LiteralPath $resolved -PathType Leaf) -and
        ([System.IO.Path]::GetExtension($resolved) -ieq '.exe')
}

function Test-WellFormedWindowsPath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return $false }
    $value = $Path.Trim()
    return ($value -match '^[A-Za-z]:[\\/]') -or ($value -match '^\\\\')
}

function Get-VerifiedPathFallback([string]$FallbackRoot, [string]$Key) {
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($FallbackRoot)) {
        $fallbackConfigPath = Join-Path $FallbackRoot 'config\script.ini'
        if (Test-Path -LiteralPath $fallbackConfigPath -PathType Leaf) {
            $fallbackContent = [System.IO.File]::ReadAllText($fallbackConfigPath)
            $fallbackValue = ConvertFrom-IniPath (Get-IniValue $fallbackContent $Key)
            if (-not [string]::IsNullOrWhiteSpace($fallbackValue)) { $candidates += $fallbackValue }
        }
    }

    $userProfile = [Environment]::GetFolderPath('UserProfile')
    $programFilesX86 = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
    $programFiles = [Environment]::GetEnvironmentVariable('ProgramFiles')
    if ($Key -eq 'GAME_PATH') {
        $candidates += 'D:\Hearthstone'
        if (-not [string]::IsNullOrWhiteSpace($programFilesX86)) { $candidates += (Join-Path $programFilesX86 'Hearthstone') }
        if (-not [string]::IsNullOrWhiteSpace($programFiles)) { $candidates += (Join-Path $programFiles 'Hearthstone') }
    } elseif ($Key -eq 'PLATFORM_PATH') {
        if (-not [string]::IsNullOrWhiteSpace($programFilesX86)) { $candidates += (Join-Path $programFilesX86 'Battle.net\Battle.net.exe') }
        if (-not [string]::IsNullOrWhiteSpace($programFiles)) { $candidates += (Join-Path $programFiles 'Battle.net\Battle.net.exe') }
        $localizedGameFolder = ConvertFrom-IniPath '\u7089\u77f3\u7f8e\u670d'
        $candidates += (Join-Path $userProfile ("OneDrive - Duke University\Desktop\$localizedGameFolder\Battle.net\Battle.net.exe"))
    }

    $validator = if ($Key -eq 'GAME_PATH') { { param($value) Test-ValidGamePath $value } } else { { param($value) Test-ValidPlatformPath $value } }
    foreach ($candidate in $candidates) {
        if (-not [string]::IsNullOrWhiteSpace([string]$candidate) -and (& $validator ([string]$candidate))) {
            return [System.IO.Path]::GetFullPath([string]$candidate)
        }
    }
    return ''
}

function Sync-PaddleXOcrConfig(
    [string]$Root,
    [string]$FallbackRoot = '',
    [switch]$PreserveGamePlatformPaths
) {
    $configPath = Join-Path $Root 'config\script.ini'
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) { return }

    $modulePath = Join-Path $Root 'resources\paddlex-vision\src'
    if (-not (Test-Path -LiteralPath $modulePath -PathType Container)) {
        throw "PaddleX packaged module directory is missing: $modulePath"
    }
    $content = [System.IO.File]::ReadAllText($configPath)
    $configuredModule = ConvertFrom-IniPath (Get-IniValue $content 'PADDLEX_OCR_MODULE_PATH')
    $moduleValue = $modulePath
    if (-not [string]::IsNullOrWhiteSpace($configuredModule)) {
        try { $resolvedConfigured = [System.IO.Path]::GetFullPath($configuredModule) } catch { $resolvedConfigured = '' }
        if (-not [string]::IsNullOrWhiteSpace($resolvedConfigured) -and
            (Test-Path -LiteralPath $resolvedConfigured -PathType Container)) {
            $moduleValue = $resolvedConfigured
        }
    }

    $userHome = [Environment]::GetFolderPath('UserProfile')
    $python = [Environment]::GetEnvironmentVariable('PADDLEX_OCR_PYTHON')
    if ([string]::IsNullOrWhiteSpace($python) -and -not [string]::IsNullOrWhiteSpace($userHome)) {
        $venvPython = Join-Path $userHome '.codex\paddlex-ocr-venv\Scripts\python.exe'
        if (Test-Path -LiteralPath $venvPython -PathType Leaf) { $python = $venvPython }
    }
    $cachePath = if ([string]::IsNullOrWhiteSpace($userHome)) { '' } else { Join-Path $userHome '.cache\paddlex-ocr-models' }
    if ([string]::IsNullOrWhiteSpace($FallbackRoot)) {
        $FallbackRoot = Join-Path (Split-Path -Parent $Root) 'Hearthstone Script Beta'
    }
    $defaults = [ordered]@{
        PADDLEX_OCR_PYTHON = $python
        PADDLEX_OCR_MODULE_PATH = $moduleValue
        PADDLEX_OCR_MODEL_CACHE = $cachePath
    }

    # Beta historically preserves the user's game/platform selection exactly.
    # RC passes its validated Beta fallback and repairs invalid Windows paths.
    $pathKeys = if ($PreserveGamePlatformPaths) { @() } else { @('GAME_PATH', 'PLATFORM_PATH') }
    foreach ($pathKey in $pathKeys) {
        $configuredPath = ConvertFrom-IniPath (Get-IniValue $content $pathKey)
        $isValid = if ($pathKey -eq 'GAME_PATH') { Test-ValidGamePath $configuredPath } else { Test-ValidPlatformPath $configuredPath }
        if ($isValid) {
            Write-Output "${pathKey}_CONFIG=preserved:$configuredPath"
            continue
        }
        $replacement = Get-VerifiedPathFallback $FallbackRoot $pathKey
        if ([string]::IsNullOrWhiteSpace($replacement)) {
            throw "Invalid $pathKey in $configPath and no verified fallback was found (configured='$configuredPath', fallbackRoot='$FallbackRoot')"
        }
        $defaults[$pathKey] = $replacement
        Write-Output "${pathKey}_CONFIG=repair:$replacement"
    }

    $updated = $content
    foreach ($entry in $defaults.GetEnumerator()) {
        if ([string]::IsNullOrWhiteSpace($entry.Value)) { continue }
        $pattern = "(?m)^([ \t]*$([regex]::Escape($entry.Key))[ \t]*=[ \t]*).*$"
        $existing = Get-IniValue $content ([string]$entry.Key)
        $isPathRepair = $entry.Key -in @('GAME_PATH', 'PLATFORM_PATH')
        $isModuleRepair = $entry.Key -eq 'PADDLEX_OCR_MODULE_PATH' -and
            ($existing -ne (ConvertTo-IniPath ([string]$entry.Value)))
        if ($isModuleRepair -or $isPathRepair -or [string]::IsNullOrWhiteSpace($existing)) {
            $value = ConvertTo-IniPath ([string]$entry.Value)
            $updated = [regex]::Replace($updated, $pattern, { param($match) $match.Groups[1].Value + $value })
        }
    }
    if ($updated -ne $content) {
        [System.IO.File]::WriteAllText($configPath, $updated, [System.Text.UTF8Encoding]::new($false))
        Write-Output "PADDLEX_CONFIG_SYNC=$configPath"
    } else {
        Write-Output "PADDLEX_CONFIG_SYNC=unchanged:$configPath"
    }
}

function Assert-PaddleXOcrModulePath([string]$Root) {
    $configPath = Join-Path $Root 'config\script.ini'
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
        throw "PaddleX preflight config is missing: $configPath"
    }
    $content = [System.IO.File]::ReadAllText($configPath)
    $configured = ConvertFrom-IniPath (Get-IniValue $content 'PADDLEX_OCR_MODULE_PATH')
    if ([string]::IsNullOrWhiteSpace($configured)) {
        throw "PaddleX preflight configuration is missing PADDLEX_OCR_MODULE_PATH: $configPath"
    }
    try { $resolved = [System.IO.Path]::GetFullPath($configured) }
    catch { throw "PaddleX preflight could not resolve PADDLEX_OCR_MODULE_PATH '$configured': $($_.Exception.Message)" }
    if (-not (Test-Path -LiteralPath $resolved -PathType Container)) {
        throw "PaddleX preflight module directory is missing or unresolved: $resolved (config=$configPath)"
    }
    return $resolved
}
