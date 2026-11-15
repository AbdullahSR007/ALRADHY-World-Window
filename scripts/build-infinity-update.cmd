@echo off
title ALRADHY INFINITY - verified in-place update build
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-infinity-update.ps1" %*
echo.
pause
