@echo off
echo.

echo ======  Vertify User (8083) ======
curl -s http://localhost:8083/api/user/1 | find "id"
if %errorlevel% equ 0 (
    echo  User Success
) else (
    echo  User Error
)
echo.

echo ======  Vertify Article (8081) ======
curl -s http://localhost:8081/api/article/1 | find "id"
if %errorlevel% equ 0 (
    echo  Article Success
) else (
    echo  Article Error
)
echo.

echo ======  Vertify Like (8082) ======
curl -s -X POST -H "X-User-Id: 100" http://localhost:8082/api/like/1 | find "code"
if %errorlevel% equ 0 (
    echo  Like Success
) else (
    echo  Like Error
)
echo.

pause
