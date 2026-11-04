@echo off
rem Runs store-secret.ps1 for this one call only, whatever the machine's script policy.
rem Usage: store-secret.cmd [otlp^|ci]   (double-click = otlp)
set "KIND=%~1"
if "%KIND%"=="" set "KIND=otlp"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0store-secret.ps1" -Kind %KIND%
echo.
pause
