@echo off
setlocal
where gradle >nul 2>nul
if errorlevel 1 (
  echo Gradle 8.9 is not installed or not on PATH.
  exit /b 1
)
if "%ANDROID_HOME%%ANDROID_SDK_ROOT%"=="" (
  echo ANDROID_HOME or ANDROID_SDK_ROOT is not set. Install Android SDK Platform 35.
  exit /b 1
)
gradle --no-daemon assembleDebug
if errorlevel 1 exit /b %errorlevel%
if not exist app\build\outputs\apk\debug\app-debug.apk (
  echo APK output not found.
  exit /b 1
)
echo APK built successfully: app\build\outputs\apk\debug\app-debug.apk
