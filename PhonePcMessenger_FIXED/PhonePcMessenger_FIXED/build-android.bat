@echo off
setlocal
pushd "%~dp0android-app"

echo Building Android APK...

if exist "gradlew.bat" (
  call gradlew.bat assembleDebug
) else (
  where gradle >nul 2>nul
  if errorlevel 1 (
    echo ERROR: Gradle was not found.
    echo Install Android Studio, or upload this project to GitHub and use the GitHub Actions workflow.
    pause
    exit /b 1
  )
  call gradle assembleDebug
)

if errorlevel 1 (
  echo ERROR: Android APK build failed.
  pause
  exit /b 1
)

echo OK: APK built here:
echo %CD%\app\build\outputs\apk\debug\app-debug.apk
pause
popd
