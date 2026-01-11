@echo off
set DIR=%~dp0
if "%JAVA_HOME%"=="" (
  set JAVA_CMD=java
) else (
  set JAVA_CMD=%JAVA_HOME%\bin\java
)
"%JAVA_CMD%" -jar "%DIR%gradle\wrapper\gradle-wrapper.jar" %*
