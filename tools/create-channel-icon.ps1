[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SourceExe,
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,
    [Parameter(Mandatory = $true)]
    [ValidateSet('B', 'RC')]
    [string]$BadgeText
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
if (-not (Test-Path -LiteralPath $SourceExe -PathType Leaf)) {
    throw "Application icon source executable missing: $SourceExe"
}

$outputDirectory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$temporaryPng = Join-Path ([System.IO.Path]::GetTempPath()) ("hs-script-channel-icon-" + [guid]::NewGuid().ToString('N') + '.png')
$sourceIcon = $null
$sourceBitmap = $null
$bitmap = $null
$graphics = $null
$font = $null
$format = $null
try {
    $sourceIcon = [System.Drawing.Icon]::ExtractAssociatedIcon($SourceExe)
    if ($null -eq $sourceIcon) { throw "Application icon could not be extracted: $SourceExe" }
    $sourceBitmap = $sourceIcon.ToBitmap()
    $bitmap = [System.Drawing.Bitmap]::new(256, 256, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.DrawImage($sourceBitmap, [System.Drawing.Rectangle]::new(0, 0, 256, 256))

    $badgeSize = if ($BadgeText -eq 'RC') { 84 } else { 88 }
    $badgeOffset = if ($BadgeText -eq 'RC') { 164 } else { 160 }
    $badge = [System.Drawing.Rectangle]::new($badgeOffset, 8, $badgeSize, $badgeSize)
    $graphics.FillEllipse([System.Drawing.Brushes]::Black, $badge)
    $innerInset = 4
    $innerBadge = [System.Drawing.RectangleF]::new(
        $badgeOffset + $innerInset,
        12,
        $badgeSize - (2 * $innerInset),
        $badgeSize - (2 * $innerInset)
    )
    $badgeBrush = if ($BadgeText -eq 'RC') {
        [System.Drawing.Brushes]::DarkOrange
    } else {
        [System.Drawing.Brushes]::Crimson
    }
    $graphics.FillEllipse($badgeBrush, $innerBadge)
    $fontSize = if ($BadgeText -eq 'RC') { 33.0 } else { 58.0 }
    $font = [System.Drawing.Font]::new('Arial', $fontSize, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $format = [System.Drawing.StringFormat]::new()
    $format.Alignment = [System.Drawing.StringAlignment]::Center
    $format.LineAlignment = [System.Drawing.StringAlignment]::Center
    $graphics.DrawString($BadgeText, $font, [System.Drawing.Brushes]::White, $innerBadge, $format)
    $bitmap.Save($temporaryPng, [System.Drawing.Imaging.ImageFormat]::Png)

    $pngBytes = [System.IO.File]::ReadAllBytes($temporaryPng)
    $header = [byte[]](0, 0, 1, 0, 1, 0)
    $directory = [byte[]](0, 0, 0, 0, 1, 0, 32, 0)
    $sizeBytes = [System.BitConverter]::GetBytes([uint32]$pngBytes.Length)
    $offsetBytes = [System.BitConverter]::GetBytes([uint32]22)
    $icoBytes = [byte[]]::new(22 + $pngBytes.Length)
    [Array]::Copy($header, 0, $icoBytes, 0, 6)
    [Array]::Copy($directory, 0, $icoBytes, 6, 8)
    [Array]::Copy($sizeBytes, 0, $icoBytes, 14, 4)
    [Array]::Copy($offsetBytes, 0, $icoBytes, 18, 4)
    [Array]::Copy($pngBytes, 0, $icoBytes, 22, $pngBytes.Length)
    [System.IO.File]::WriteAllBytes($OutputPath, $icoBytes)
} finally {
    if ($format) { $format.Dispose() }
    if ($font) { $font.Dispose() }
    if ($graphics) { $graphics.Dispose() }
    if ($bitmap) { $bitmap.Dispose() }
    if ($sourceBitmap) { $sourceBitmap.Dispose() }
    if ($sourceIcon) { $sourceIcon.Dispose() }
    Remove-Item -LiteralPath $temporaryPng -Force -ErrorAction SilentlyContinue
}
Write-Output "CHANNEL_ICON_CREATED badge=$BadgeText path=$OutputPath"
