@echo off
setlocal
cd /d "%~dp0"
title ARGUS Remote Update Publisher

rem setup-firebase.cmd stores the Firebase Android client configuration here.
rem Loading it makes publishing deterministic without retyping Firebase values.
if exist "%~dp0.runtime\firebase\argus-firebase.cmd" call "%~dp0.runtime\firebase\argus-firebase.cmd"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish-update.ps1" %*
set "RC=%ERRORLEVEL%"

echo.
if not "%RC%"=="0" (
    echo ============================================================
    echo REMOTE UPDATE PUBLISH FAILED
    echo ============================================================
    echo Read the error above. Nothing else needs to be sent to users.
) else (
    echo ============================================================
    echo DONE
    echo ============================================================
    echo Future users only need to accept the update inside the app.
)

echo.
pause
exit /b %RC%
