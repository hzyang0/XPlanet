@echo off
setlocal
cd /d "%~dp0"
java -version >nul 2>&1
if errorlevel 1 (
  echo Java 17 or newer is required. Install Java and reopen this window.
  pause
  exit /b 1
)
if not exist "xplanet-booking\target\xplanet-booking-2.0.0.jar" (
  echo Building application. First run needs network access for Maven dependencies.
  pushd xplanet-booking
  call mvnw.cmd -B package
  if errorlevel 1 (
    popd
    pause
    exit /b 1
  )
  popd
)
echo Open http://localhost:8080 after startup. Press Ctrl+C to stop.
java -jar xplanet-booking\target\xplanet-booking-2.0.0.jar --spring.profiles.active=demo
if errorlevel 1 pause
