@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title ARGUS Firebase / FCM Setup

rem Windows PowerShell 5.1 incorrectly promotes normal stderr/progress output
rem from tools like Firebase CLI into NativeCommandError records when the
rem script uses ErrorActionPreference=Stop. Run this setup under PowerShell 7
rem instead, which handles native CLI stderr correctly.
set "NPM_CONFIG_LOGLEVEL=error"
set "NO_UPDATE_NOTIFIER=1"
set "NPM_CONFIG_FUND=false"
set "NPM_CONFIG_AUDIT=false"

set "PWSH="
for /f "delims=" %%P in ('where pwsh.exe 2^>nul') do if not defined PWSH set "PWSH=%%P"
if not defined PWSH if exist "%ProgramFiles%\PowerShell\7\pwsh.exe" set "PWSH=%ProgramFiles%\PowerShell\7\pwsh.exe"
if not defined PWSH if exist "%LOCALAPPDATA%\Microsoft\WindowsApps\pwsh.exe" set "PWSH=%LOCALAPPDATA%\Microsoft\WindowsApps\pwsh.exe"

if not defined PWSH (
    echo [SETUP] PowerShell 7 is required for the Firebase CLI setup.
    echo [SETUP] Installing PowerShell 7 with winget...
    where winget.exe >nul 2>nul
    if errorlevel 1 (
        echo [ERROR] winget is not available, so PowerShell 7 could not be installed automatically.
        echo Install PowerShell 7, then run setup-firebase.cmd again.
        pause
        exit /b 1
    )

    winget install --id Microsoft.PowerShell --exact --source winget --accept-package-agreements --accept-source-agreements --silent
    if errorlevel 1 (
        echo [ERROR] PowerShell 7 installation failed.
        pause
        exit /b 1
    )

    for /f "delims=" %%P in ('where pwsh.exe 2^>nul') do if not defined PWSH set "PWSH=%%P"
    if not defined PWSH if exist "%ProgramFiles%\PowerShell\7\pwsh.exe" set "PWSH=%ProgramFiles%\PowerShell\7\pwsh.exe"
    if not defined PWSH if exist "%LOCALAPPDATA%\Microsoft\WindowsApps\pwsh.exe" set "PWSH=%LOCALAPPDATA%\Microsoft\WindowsApps\pwsh.exe"
)

if not defined PWSH (
    echo [ERROR] PowerShell 7 was installed but pwsh.exe could not be located.
    echo Close this CMD window, open a new one, and run setup-firebase.cmd again.
    pause
    exit /b 1
)

echo [OK] Using PowerShell 7: %PWSH%
echo.
echo [FIREBASE AUTH CHECK]
echo [INFO] Refreshing Firebase authentication so projects:list has a valid token.
echo [INFO] Your browser may open. Sign in with the Google account that should own ARGUS.
call npx.cmd --yes firebase-tools@latest login --reauth
if errorlevel 1 (
    echo.
    echo [ERROR] Firebase re-authentication failed.
    echo Close any old Google login tab, run setup-firebase.cmd again, and complete the browser sign-in.
    pause
    exit /b 1
)

echo [OK] Firebase authentication refreshed.
echo.
"%PWSH%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-firebase.ps1"
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
