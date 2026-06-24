@echo off
setlocal
pushd "%~dp0"

if not exist "pc\dist" mkdir "pc\dist"
if not exist "pc\out" mkdir "pc\out"

echo Checking javac...
where javac >nul 2>nul
if errorlevel 1 (
  echo ERROR: javac not found. Install JDK 17+ and add it to PATH.
  pause
  exit /b 1
)

echo Building PC app...
javac -encoding UTF-8 -d "pc\out" "pc\src\main\java\rm\phonepc\PcMessenger.java"
if errorlevel 1 (
  echo ERROR: PC compile failed.
  pause
  exit /b 1
)

jar --create --file "pc\dist\PhonePcMessengerPC.jar" --main-class rm.phonepc.PcMessenger -C "pc\out" .
if errorlevel 1 (
  echo ERROR: Jar creation failed.
  pause
  exit /b 1
)

echo OK: pc\dist\PhonePcMessengerPC.jar
pause
popd
