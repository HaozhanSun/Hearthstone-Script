@echo off
setlocal
rem Prefer PowerShell 7 when it is available. It avoids the legacy
rem powershell.exe initialization failure (0xc0000142) observed on this PC.
where pwsh.exe >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  pwsh.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-debug.ps1" %*
) else (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-debug.ps1" %*
)
exit /b %ERRORLEVEL%
