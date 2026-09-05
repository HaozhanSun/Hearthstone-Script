[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SourceExe,
    [Parameter(Mandatory = $true)]
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

if (-not (Test-Path -LiteralPath $SourceExe -PathType Leaf)) {
    throw "Stable icon source executable missing: $SourceExe"
}

$outputDirectory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$temporaryPng = Join-Path ([System.IO.Path]::GetTempPath()) ("hs-script-beta-icon-" + [guid]::NewGuid().ToString('N') + '.png')
$sourceIcon = [System.Drawing.Icon]::ExtractAssociatedIcon($SourceExe)
$sourceBitmap = $sourceIcon.ToBitmap()
$bitmap = [System.Drawing.Bitmap]::new(256, 256, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$graphics.DrawImage($sourceBitmap, [System.Drawing.Rectangle]::new(0, 0, 256, 256))

$badge = [System.Drawing.Rectangle]::new(160, 8, 88, 88)
$graphics.FillEllipse([System.Drawing.Brushes]::Black, $badge)
$innerBadge = [System.Drawing.RectangleF]::new(164, 12, 80, 80)
$graphics.FillEllipse([System.Drawing.Brushes]::Crimson, $innerBadge)
$font = [System.Drawing.Font]::new('Arial', 58, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$format = [System.Drawing.StringFormat]::new()
$format.Alignment = [System.Drawing.StringAlignment]::Center
$format.LineAlignment = [System.Drawing.StringAlignment]::Center
$graphics.DrawString('B', $font, [System.Drawing.Brushes]::White, $innerBadge, $format)
$bitmap.Save($temporaryPng, [System.Drawing.Imaging.ImageFormat]::Png)

# ICO files may contain a PNG-compressed 256x256 image. Write the minimal
# single-image ICO container around the generated PNG for shell/shortcut use.
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

$format.Dispose()
$font.Dispose()
$graphics.Dispose()
$bitmap.Dispose()
$sourceBitmap.Dispose()
$sourceIcon.Dispose()
Remove-Item -LiteralPath $temporaryPng -Force -ErrorAction SilentlyContinue
Write-Output "BETA_ICON_CREATED=$OutputPath"
