@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
title ARGUS - Local Android Build

set "GRADLE_VERSION=8.11.1"
set "TOOLS_DIR=%CD%\.tools"
set "GRADLE_DIR=%TOOLS_DIR%\gradle-%GRADLE_VERSION%"
set "GRADLE_ZIP=%TOOLS_DIR%\gradle-%GRADLE_VERSION%-bin.zip"
set "OUTPUT_DIR=%CD%\OUTPUT"
set "APK_SOURCE=%CD%\app\build\outputs\apk\debug\app-debug.apk"
set "APK_OUTPUT=%OUTPUT_DIR%\ARGUS-debug.apk"

echo ============================================================
echo                     ARGUS LOCAL BUILD
echo ============================================================
echo.

rem ------------------------------------------------------------
rem 1. Find Java. Prefer JAVA_HOME, then Android Studio's JBR.
rem ------------------------------------------------------------
set "JAVA_FOUND="

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA_FOUND=%JAVA_HOME%"
)

if not defined JAVA_FOUND if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
    set "JAVA_FOUND=%ProgramFiles%\Android\Android Studio\jbr"
)

if not defined JAVA_FOUND if exist "%ProgramFiles%\Android\Android Studio\jre\bin\java.exe" (
    set "JAVA_FOUND=%ProgramFiles%\Android\Android Studio\jre"
)

if not defined JAVA_FOUND (
    for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-17*") do (
        if exist "%%~fD\bin\java.exe" set "JAVA_FOUND=%%~fD"
    )
)

if not defined JAVA_FOUND (
    for /d %%D in ("%ProgramFiles%\Java\jdk-17*") do (
        if exist "%%~fD\bin\java.exe" set "JAVA_FOUND=%%~fD"
    )
)

if not defined JAVA_FOUND (
    where java >nul 2>nul
    if not errorlevel 1 (
        for /f "delims=" %%J in ('where java 2^>nul') do (
            if not defined JAVA_FOUND (
                for %%P in ("%%~dpJ..") do set "JAVA_FOUND=%%~fP"
            )
        )
    )
)

if not defined JAVA_FOUND (
    echo [ERROR] Java was not found.
    echo.
    echo Easiest fix:
    echo   Install Android Studio, then run buildapp.cmd again.
    echo.
    echo ARGUS will automatically use Android Studio's built-in Java.
    echo.
    pause
    exit /b 1
)

set "JAVA_HOME=%JAVA_FOUND%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo [OK] Java: %JAVA_HOME%
"%JAVA_HOME%\bin\java.exe" -version 2>&1 | findstr /i "version" >nul
if errorlevel 1 (
    echo [ERROR] Java exists but could not run.
    pause
    exit /b 1
)

rem ------------------------------------------------------------
rem 2. Find Android SDK.
rem ------------------------------------------------------------
set "SDK_FOUND="

if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%" set "SDK_FOUND=%ANDROID_SDK_ROOT%"
if not defined SDK_FOUND if defined ANDROID_HOME if exist "%ANDROID_HOME%" set "SDK_FOUND=%ANDROID_HOME%"
if not defined SDK_FOUND if exist "%LOCALAPPDATA%\Android\Sdk" set "SDK_FOUND=%LOCALAPPDATA%\Android\Sdk"
if not defined SDK_FOUND if exist "%USERPROFILE%\AppData\Local\Android\Sdk" set "SDK_FOUND=%USERPROFILE%\AppData\Local\Android\Sdk"

if not defined SDK_FOUND (
    echo [ERROR] Android SDK was not found.
    echo.
    echo Open Android Studio once and install Android SDK 35,
    echo then run buildapp.cmd again.
    echo.
    pause
    exit /b 1
)

set "ANDROID_HOME=%SDK_FOUND%"
set "ANDROID_SDK_ROOT=%SDK_FOUND%"

echo [OK] Android SDK: %ANDROID_SDK_ROOT%

rem ------------------------------------------------------------
rem 3. Make sure Android 35 build components exist.
rem ------------------------------------------------------------
set "NEED_SDK_PACKAGES=0"
if not exist "%ANDROID_SDK_ROOT%\platforms\android-35\android.jar" set "NEED_SDK_PACKAGES=1"
if not exist "%ANDROID_SDK_ROOT%\build-tools\35.0.0\aapt2.exe" set "NEED_SDK_PACKAGES=1"

if "%NEED_SDK_PACKAGES%"=="1" (
    echo [INFO] Android SDK 35 components are missing. Installing them...

    set "SDKMANAGER="
    if exist "%ANDROID_SDK_ROOT%\cmdline-tools\latest\bin\sdkmanager.bat" set "SDKMANAGER=%ANDROID_SDK_ROOT%\cmdline-tools\latest\bin\sdkmanager.bat"

    if not defined SDKMANAGER (
        for /d %%D in ("%ANDROID_SDK_ROOT%\cmdline-tools\*") do (
            if exist "%%~fD\bin\sdkmanager.bat" set "SDKMANAGER=%%~fD\bin\sdkmanager.bat"
        )
    )

    if not defined SDKMANAGER (
        echo [ERROR] Android SDK Command-line Tools are not installed.
        echo.
        echo In Android Studio:
        echo   Settings ^> Android SDK ^> SDK Tools
        echo   Enable "Android SDK Command-line Tools (latest)"
        echo.
        echo Then run buildapp.cmd again.
        echo.
        pause
        exit /b 1
    )

    powershell -NoProfile -ExecutionPolicy Bypass -Command "1..50 ^| ForEach-Object { 'y' }" | call "%SDKMANAGER%" --licenses >nul 2>nul
    call "%SDKMANAGER%" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
    if errorlevel 1 (
        echo [ERROR] Could not install Android SDK 35 components.
        pause
        exit /b 1
    )
)

echo [OK] Android SDK 35 is ready.

rem ------------------------------------------------------------
rem 4. Download Gradle locally if needed.
rem ------------------------------------------------------------
if not exist "%GRADLE_DIR%\bin\gradle.bat" (
    echo [INFO] First local build: downloading Gradle %GRADLE_VERSION%...
    if not exist "%TOOLS_DIR%" mkdir "%TOOLS_DIR%" >nul 2>nul

    set "ARGUS_GRADLE_ZIP=%GRADLE_ZIP%"
    set "ARGUS_TOOLS_DIR=%TOOLS_DIR%"

    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile $env:ARGUS_GRADLE_ZIP; Expand-Archive -Path $env:ARGUS_GRADLE_ZIP -DestinationPath $env:ARGUS_TOOLS_DIR -Force"
    if errorlevel 1 (
        echo [ERROR] Could not download or extract Gradle.
        echo Check your internet connection and run buildapp.cmd again.
        pause
        exit /b 1
    )

    del /q "%GRADLE_ZIP%" >nul 2>nul
)

echo [OK] Gradle %GRADLE_VERSION% is ready.

rem ------------------------------------------------------------
rem 5. Build ARGUS locally.
rem ------------------------------------------------------------
echo.
echo [BUILD] Building ARGUS debug APK locally...
echo.

call "%GRADLE_DIR%\bin\gradle.bat" :app:assembleDebug --no-daemon
if errorlevel 1 (
    echo.
    echo ============================================================
    echo BUILD FAILED
    echo ============================================================
    echo.
    echo Scroll up to see the actual Gradle error.
    echo No GitHub Actions minutes were used.
    echo.
    pause
    exit /b 1
)

if not exist "%APK_SOURCE%" (
    echo [ERROR] Gradle finished, but the APK was not found at:
    echo %APK_SOURCE%
    pause
    exit /b 1
)

if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%" >nul 2>nul
copy /y "%APK_SOURCE%" "%APK_OUTPUT%" >nul

if errorlevel 1 (
    echo [ERROR] APK built, but could not copy it to OUTPUT.
    pause
    exit /b 1
)

echo.
echo ============================================================
echo BUILD SUCCESSFUL
echo ============================================================
echo.
echo APK:
echo %APK_OUTPUT%
echo.
echo You can copy ARGUS-debug.apk directly to the phones and install it.
echo No GitHub Actions minutes were used.
echo.

explorer /select,"%APK_OUTPUT%" >nul 2>nul
pause
exit /b 0
