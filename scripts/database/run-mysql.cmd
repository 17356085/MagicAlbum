@echo off
set "DB_USER=%DB_USER%"
if "%DB_USER%"=="" set "DB_USER=root"
set "DB_NAME=%DB_NAME%"
if "%DB_NAME%"=="" set "DB_NAME=MagicAlbum"
mysql -u "%DB_USER%" -p -D "%DB_NAME%" < "%~dp0..\..\backend\sql\mysql\dev_create_all_tables.sql"
