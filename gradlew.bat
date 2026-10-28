@echo off
setlocal
if defined JAVA_HOME (set "READER_JAVA=%JAVA_HOME%\bin\java.exe") else (set "READER_JAVA=java.exe")
"%READER_JAVA%" %JAVA_OPTS% %GRADLE_OPTS% -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
