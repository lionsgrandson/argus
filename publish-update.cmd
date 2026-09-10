@echo off
setlocal
cd /d "%~dp0"
title ARGUS Remote Update Publisher

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
