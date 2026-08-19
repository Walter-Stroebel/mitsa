@echo off
rem Windows start script. Assumes "java" is on PATH.
set DIR=%~dp0..
java -jar "%DIR%\target\mitsa.jar" %*
