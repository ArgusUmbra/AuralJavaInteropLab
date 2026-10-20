@echo off
setlocal
java -cp "%~dp0..\build\classes-main" org.auralinterop.adapter.AuralConformanceAdapter
exit /b %errorlevel%
