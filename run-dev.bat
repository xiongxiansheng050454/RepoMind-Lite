@echo off
REM Windows 启动脚本 - 开发环境
echo Starting RepoMind-Lite in DEV mode...
echo Skip IP validation: ENABLED

set SPRING_PROFILES_ACTIVE=dev
set SKIP_IP_VALIDATION=true

REM 如果使用 Maven 启动
REM mvn spring-boot:run

REM 如果使用已编译的 jar
REM java -jar target\repomind-lite-0.0.1-SNAPSHOT.jar

echo.
echo Configuration:
echo   Profile: dev
echo   Skip IP Validation: true
echo.
echo Please run the application from your IDE with these environment variables set.
pause
