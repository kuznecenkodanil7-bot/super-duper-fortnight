@echo off
setlocal
pushd "%~dp0"
if not exist "pc\dist\PhonePcMessengerPC.jar" (
  echo PC app is not built yet. Run build-pc.bat first.
  pause
  exit /b 1
)
java -jar "pc\dist\PhonePcMessengerPC.jar"
popd
