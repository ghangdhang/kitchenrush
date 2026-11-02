@echo off
cd /d "%~dp0"
set "PATH=C:\Tools\gh\bin;%PATH%"
set "JAVA=C:\Program Files\Java\jdk-25.0.4.1\bin\java.exe"
if not exist "%JAVA%" set "JAVA=java"
"%JAVA%" -Xms2G -Xmx2G -jar paper.jar --nogui
pause
