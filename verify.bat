@echo off
setlocal
cd /d "%~dp0xplanet-booking"
call mvnw.cmd -B clean verify
if errorlevel 1 pause
