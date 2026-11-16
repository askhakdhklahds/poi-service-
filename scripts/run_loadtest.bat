@echo off
set "JAVA_HOME=D:\gis-env\jdk17"
cd /d D:\jue\gis\poi-service
call D:\gis-env\jmeter\bin\jmeter.bat -n -t scripts\poi_loadtest.jmx ^
  -l target\jmeter_results.jtl ^
  -Jjmeter.save.saveservice.output_format=csv
pause
