@echo off
java -jar "%~dp0jdiff.jar" %*
exit /b %ERRORLEVEL%
