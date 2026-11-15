@echo off
title ALRADHY INFINITY - FREE Local APK Builder
echo.
echo ===============================================================
echo  ALRADHY WORLD WINDOW INFINITY -- FREE Windows APK Build
echo  No GitHub Actions usage, no payments.
echo ===============================================================
echo.
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0build-free-windows.ps1" %*
set "exitcode=%ERRORLEVEL%"
echo.
if not "%exitcode%"=="0" (
  echo Build did not succeed. Read the error above.
) else (
  echo Signed APK copied to Downloads\ALRADHY-APKs\INFINITY
)
echo.
pause
exit /b %exitcode%
