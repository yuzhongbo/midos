@echo off
setlocal

set "ROOT_DIR=%~dp0"
cd /d "%ROOT_DIR%"

echo [MindOS] Starting assistant-api with solo profile...
call "%ROOT_DIR%mvnw.cmd" -pl assistant-api -am spring-boot:run -Dspring-boot.run.profiles=solo -Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" (
  echo [MindOS] Startup failed with exit code %EXIT_CODE%.
)
exit /b %EXIT_CODE%

