Set-StrictMode -Version Latest

function ConvertFrom-TaskbandResolvedLinkPaths {
    param([byte[]]$Bytes)

    if ($null -eq $Bytes -or $Bytes.Length -eq 0) { return @() }
    $paths = [System.Collections.Generic.List[string]]::new()
    # FavoritesResolve is a Shell PIDL blob with embedded path strings; depending
    # on the shell item, those strings can be visible as ASCII bytes or UTF-16.
    foreach ($encoding in @([System.Text.Encoding]::ASCII, [System.Text.Encoding]::Unicode)) {
        $text = $encoding.GetString($Bytes)
        $matches = [regex]::Matches(
            $text,
            '(?i)(?:[a-z]:\\|\\\\)[^\x00\r\n]{1,1024}?\.lnk(?=\x00|$)'
        )
        foreach ($match in $matches) {
            $path = $match.Value.Trim()
            if (-not [string]::IsNullOrWhiteSpace($path) -and -not $paths.Contains($path)) {
                $paths.Add($path)
            }
        }
    }
    return ,$paths.ToArray()
}

function Test-TaskbandPathRegistered {
    param(
        [Parameter(Mandatory = $true)][string]$ExpectedPath,
        [string[]]$ResolvedPaths = @()
    )

    $expected = [System.IO.Path]::GetFullPath($ExpectedPath).TrimEnd('\')
    foreach ($candidate in $ResolvedPaths) {
        try {
            $resolved = [System.IO.Path]::GetFullPath($candidate).TrimEnd('\')
            if ([string]::Equals($expected, $resolved, [System.StringComparison]::OrdinalIgnoreCase)) {
                return $true
            }
        } catch { }
    }
    return $false
}

function Get-TaskbarShortcutSyncPlan {
    param(
        [Parameter(Mandatory = $true)][string]$PinPath,
        [Parameter(Mandatory = $true)][bool]$PinRegistered,
        [Parameter(Mandatory = $true)][bool]$ShortcutFileExists
    )

    if (-not $PinRegistered) {
        return [pscustomobject]@{
            Allowed = $false
            Action = 'FAIL_MANUAL_PIN_REQUIRED'
            Reason = "Explorer Taskband does not resolve the canonical pin '$PinPath'. Pin this channel's launcher through Windows taskbar UI, then rerun deployment."
        }
    }
    if (-not $ShortcutFileExists) {
        return [pscustomobject]@{
            Allowed = $false
            Action = 'FAIL_PIN_FILE_MISSING'
            Reason = "Explorer Taskband references '$PinPath', but that shortcut file is missing. Restore the pin through Windows taskbar UI, then rerun deployment."
        }
    }
    return [pscustomobject]@{
        Allowed = $true
        Action = 'UPDATE_REGISTERED_SHORTCUT_IN_PLACE'
        Reason = 'Canonical shortcut is registered by Explorer and will be updated without unlinking or recreating it.'
    }
}

function Test-TaskbarShortcutContract {
    param([Parameter(Mandatory = $true)]$Evidence)

    $failures = [System.Collections.Generic.List[string]]::new()
    if (-not $Evidence.TaskbandRegistered) { $failures.Add('taskband-not-registered') }
    if (-not $Evidence.ShortcutExists) { $failures.Add('shortcut-file-missing') }

    if (-not [string]::Equals(
        [string]$Evidence.TargetPath,
        [string]$Evidence.ExpectedWscriptPath,
        [System.StringComparison]::OrdinalIgnoreCase
    )) { $failures.Add('shortcut-target-stale') }

    $actualLauncher = ([string]$Evidence.Arguments).Trim().Trim('"')
    if (-not [string]::Equals(
        $actualLauncher,
        [string]$Evidence.ExpectedLauncherPath,
        [System.StringComparison]::OrdinalIgnoreCase
    )) { $failures.Add('launcher-argument-stale') }

    if (-not [string]::Equals(
        [string]$Evidence.WorkingDirectory,
        [string]$Evidence.ExpectedRuntimeRoot,
        [System.StringComparison]::OrdinalIgnoreCase
    )) { $failures.Add('working-directory-stale') }

    $expectedIcon = ([string]$Evidence.ExpectedIconPath).TrimEnd('\') + ',0'
    if (-not [string]::Equals(
        ([string]$Evidence.IconLocation).Trim(),
        $expectedIcon,
        [System.StringComparison]::OrdinalIgnoreCase
    )) { $failures.Add('icon-path-stale') }
    if (-not $Evidence.LauncherExists) { $failures.Add('stable-launcher-missing') }
    if (-not $Evidence.IconExists) { $failures.Add('icon-file-missing') }
    if (-not $Evidence.ManifestJarExists) { $failures.Add('manifest-jar-missing') }
    if ([string]::IsNullOrWhiteSpace([string]$Evidence.ManifestDeploymentId)) {
        $failures.Add('manifest-deployment-id-missing')
    }
    if (-not [string]::Equals(
        [string]$Evidence.ManifestJarSha256,
        [string]$Evidence.ActualJarSha256,
        [System.StringComparison]::OrdinalIgnoreCase
    )) { $failures.Add('manifest-jar-hash-mismatch') }

    return [pscustomobject]@{
        IsValid = ($failures.Count -eq 0)
        Failures = $failures.ToArray()
        LaunchabilityScope = 'shortcut-to-stable-launcher-and-manifest-verified; actual taskbar click remains an operator UI check'
    }
}

function Get-TaskbandResolvedPathsFromCurrentUser {
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey(
        'Software\Microsoft\Windows\CurrentVersion\Explorer\Taskband',
        $false
    )
    if ($null -eq $key) { return @() }
    try {
        $bytes = $key.GetValue('FavoritesResolve', $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
        return ConvertFrom-TaskbandResolvedLinkPaths -Bytes $bytes
    } finally {
        $key.Dispose()
    }
}
