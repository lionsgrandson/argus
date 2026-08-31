@echo off
setlocal
title ARGUS Cloudflare Deploy

echo ============================================================
echo                ARGUS CLOUDFLARE DEPLOY
echo ============================================================
echo.

cd /d "%~dp0"

where node >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Node.js is not installed or is not in PATH.
    echo Install Node.js once, then run this file again.
    echo.
    pause
    exit /b 1
)

where npm >nul 2>&1
if errorlevel 1 (
    echo [ERROR] npm is not installed or is not in PATH.
    echo Install Node.js once, then run this file again.
    echo.
    pause
    exit /b 1
)

if not exist "relay-cloudflare\package.json" (
    echo [ERROR] relay-cloudflare\package.json was not found.
    echo Run this CMD from inside the ARGUS repository.
    echo.
    pause
    exit /b 1
)

pushd "relay-cloudflare"

if not exist "node_modules\.bin\wrangler.cmd" (
    echo [SETUP] Installing Cloudflare deploy tools...
    call npm install
    if errorlevel 1 goto :fail
)

echo [AUTH] Checking saved Cloudflare login...
call npx wrangler whoami >nul 2>&1
if errorlevel 1 (
    echo.
    echo [FIRST TIME ONLY] Cloudflare sign in is required on this PC.
    echo A browser will open. Sign in once and Wrangler will remember it.
    echo You do NOT need to paste an API key into this file.
    echo.
    call npx wrangler login
    if errorlevel 1 goto :authfail
) else (
    echo [OK] Saved Cloudflare login found.
)

echo.
echo [DEPLOY] Deploying ARGUS relay to Cloudflare...
call npx wrangler deploy
if errorlevel 1 goto :fail

echo.
echo ============================================================
echo [SUCCESS] ARGUS Cloudflare relay deployed.
echo No APK rebuild is required for relay-only changes.
echo ============================================================
echo.
popd
pause
exit /b 0

:authfail
echo.
echo [ERROR] Cloudflare sign in failed or was cancelled.
echo Run this CMD again and complete the browser login.
echo.
popd
pause
exit /b 1

:fail
echo.
echo [ERROR] Cloudflare deployment failed.
echo Read the error above. Your saved login is not deleted.
echo.
popd
pause
exit /b 1
