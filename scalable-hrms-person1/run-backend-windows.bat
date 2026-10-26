@echo off
cd /d "%~dp0backend"
call mvn spring-boot:run
pause
