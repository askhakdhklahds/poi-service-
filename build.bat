@echo off
set "JAVA_HOME=D:\gis-env\jdk17"
cd /d D:\jue\gis\poi-service
call D:\gis-env\maven\bin\mvn.cmd clean package -DskipTests
pause
