@echo off
set "PGPASSWORD=postgres"
set "PGCLIENTENCODING=UTF8"
cd /d D:\jue\gis\poi-service

"C:\Program Files\QGIS 3.44.15\bin\ogr2ogr.exe" -f PostgreSQL "PG:host=127.0.0.1 port=5432 dbname=poi user=postgres password=postgres" "scripts\data\shanghai_districts.json" -nln district_raw -nlt PROMOTE_TO_MULTI -lco GEOMETRY_NAME=geom -lco FID=id -s_srs EPSG:4326 -t_srs EPSG:4326 -overwrite

"D:\gis-env\pgsql\bin\psql.exe" -h 127.0.0.1 -U postgres -d poi -f scripts\import_districts.sql

pause
