@echo off
echo Checking service health...
curl -fsS http://localhost:8083/actuator/health || exit /b 1
curl -fsS http://localhost:8080/actuator/health || exit /b 1
echo Services are healthy. See README.md for authenticated order verification.
