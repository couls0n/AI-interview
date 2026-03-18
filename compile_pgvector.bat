@echo off
call "D:\Visual Studio Build Tools\Common7\Tools\VsDevCmd.bat" -arch=amd64
set PGROOT=D:\PostgreSQL
cd /d D:\pgvector-0.8.0\pgvector-0.8.0
nmake /F Makefile.win
nmake /F Makefile.win install
pause
