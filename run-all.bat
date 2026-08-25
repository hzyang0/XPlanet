@echo off
cd /d %~dp0
echo Starting User Service (8083) and Flash Sale Service (8080)...
start "XPlanet User" cmd /k "mvn -pl xplanet-user -am spring-boot:run"
timeout /t 3 /nobreak > nul
start "XPlanet Flash Sale" cmd /k "mvn -pl xplanet-seckill -am spring-boot:run"
echo Run docker compose -f docker\docker-compose-infra.yml up -d first.
