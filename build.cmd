@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build.ps1" %*
set "buildExitCode=%errorlevel%"
if not "%buildExitCode%"=="0" pause
exit /b %buildExitCode%
