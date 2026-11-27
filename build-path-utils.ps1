function Resolve-PhysicalDirectoryPath {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $fullPath -PathType Container)) {
        throw "Cannot resolve physical path because the directory is missing: $fullPath"
    }

    if ($null -eq ([System.Management.Automation.PSTypeName]'HearthstoneReleaseNativePathResolver').Type) {
        Add-Type @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

public static class HearthstoneReleaseNativePathResolver
{
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateFile(
        string name,
        uint access,
        uint share,
        IntPtr security,
        uint creation,
        uint flags,
        IntPtr template);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern uint GetFinalPathNameByHandle(
        IntPtr handle,
        StringBuilder path,
        uint length,
        uint flags);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr handle);

    public static string Resolve(string path)
    {
        var handle = CreateFile(
            path,
            0,
            7,
            IntPtr.Zero,
            3,
            0x02000000,
            IntPtr.Zero);
        if (handle == new IntPtr(-1))
        {
            throw new Win32Exception(Marshal.GetLastWin32Error());
        }

        try
        {
            var buffer = new StringBuilder(32768);
            var length = GetFinalPathNameByHandle(handle, buffer, (uint)buffer.Capacity, 0);
            if (length == 0 || length >= buffer.Capacity)
            {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            return buffer.ToString();
        }
        finally
        {
            CloseHandle(handle);
        }
    }
}
'@
    }

    try {
        $resolved = [HearthstoneReleaseNativePathResolver]::Resolve($fullPath)
    } catch {
        throw "Cannot resolve the physical path for '$fullPath': $($_.Exception.Message)"
    }

    if ($resolved.StartsWith('\\?\UNC\', [System.StringComparison]::OrdinalIgnoreCase)) {
        $resolved = '\\' + $resolved.Substring(8)
    } elseif ($resolved.StartsWith('\\?\', [System.StringComparison]::OrdinalIgnoreCase)) {
        $resolved = $resolved.Substring(4)
    }
    return $resolved
}
