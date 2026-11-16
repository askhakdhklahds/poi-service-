@echo off
set "JAVA_HOME=D:\gis-env\jdk17"
cd /d D:\jue\gis\poi-service
start "POI Service" "D:\gis-env\jdk17\bin\java.exe" -jar target\poi-service-0.0.1-SNAPSHOT.jar
