@echo off
REM ====================================
REM XPlanet 完整启动脚本
REM ====================================

cd /d d:\User\Desktop\xplanet

echo.
echo ====== start Article (8081) ======
start "Article" cmd /k "mvn -f xplanet-article\pom.xml spring-boot:run"

timeout /t 3 /nobreak

echo.
echo ====== start Interaction (8082) ======
start "Interaction" cmd /k "mvn -f xplanet-interaction\pom.xml spring-boot:run"

timeout /t 3 /nobreak

echo.
echo ====== start User (8083) ======
start "User" cmd /k "mvn -f xplanet-user\pom.xml spring-boot:run"
echo.

pause
