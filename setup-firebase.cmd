@echo off
setlocal
cd /d "%~dp0"
title ARGUS Firebase / FCM Setup

rem Windows PowerShell 5.1 can turn harmless npm stderr warnings (for example
rem dependency deprecation notices from npx) into NativeCommandError records.
rem Suppress npm warning chatter; real Firebase/npm failures still return a
rem non-zero exit code and setup-firebase.ps1 will stop normally.
set "NPM_CONFIG_LOGLEVEL=error"
set "NO_UPDATE_NOTIFIER=1"
set "NPM_CONFIG_FUND=false"
set "NPM_CONFIG_AUDIT=false"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-firebase.ps1"
set "RC=%ERRORLEVEL%"

if not "%RC%"=="0" (
    echo.
    echo ============================================================
    echo FIREBASE SETUP FAILED
    echo ============================================================
    echo Read the error above and run setup-firebase.cmd again.
    echo.
    pause
    exit /b %RC%
)

rem Load the freshly generated values into this CMD session too.
if exist "%~dp0.runtime\firebase\argus-firebase.cmd" call "%~dp0.runtime\firebase\argus-firebase.cmd"

echo.
echo ============================================================
echo FIREBASE / FCM SETUP COMPLETE
echo ============================================================
echo.
echo ARGUS_FIREBASE_API_KEY is configured.
echo ARGUS_FIREBASE_APP_ID is configured.
echo ARGUS_FIREBASE_PROJECT_ID is configured.
echo ARGUS_FIREBASE_SENDER_ID is configured.
echo.
echo You can now run:
echo   publish-update.cmd "Restart recovery and remote wake update"
echo.
pause
exit /b 0
