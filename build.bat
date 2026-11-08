@echo off
REM Build dist\SlangDictionary.jar WITHOUT Maven (needs JDK 17+ in PATH).
REM   build.bat        -> compile + package
REM   build.bat test   -> compile + run JUnit 5 (set JUNIT_CONSOLE to junit-platform-console-standalone.jar), or use: mvn test
cd /d "%~dp0"
if exist out rmdir /s /q out
if exist out-test rmdir /s /q out-test
mkdir out dist
dir /s /b src\main\java\*.java > sources.txt
javac --release 17 -encoding UTF-8 -d out @sources.txt || exit /b 1
del sources.txt
mkdir out\data out\fonts
copy /y data\original\slang.raw.txt out\data\ >nul
copy /y data\original\slang.normalized.txt out\data\ >nul
copy /y data\original\malformed-report.txt out\data\ >nul
copy /y src\main\resources\fonts\*.ttf out\fonts\ >nul

if /i "%1"=="test" goto test

echo Main-Class: dictionary.ui.Main> out\MANIFEST.MF
if exist dist\SlangDictionary.jar del dist\SlangDictionary.jar
jar cfm dist\SlangDictionary.jar out\MANIFEST.MF -C out dictionary -C out data -C out fonts || exit /b 1
if exist dist\data rmdir /s /q dist\data
mkdir dist\data\original
copy /y data\original\slang.raw.txt dist\data\original\ >nul
copy /y data\original\slang.normalized.txt dist\data\original\ >nul
copy /y data\original\malformed-report.txt dist\data\original\ >nul
echo Built dist\SlangDictionary.jar  (run: java -jar dist\SlangDictionary.jar)
exit /b 0

:test
if "%JUNIT_CONSOLE%"=="" (echo Set JUNIT_CONSOLE to junit-platform-console-standalone.jar or use: mvn test & exit /b 1)
mkdir out-test
dir /s /b src\test\java\*.java > tests.txt
javac --release 17 -encoding UTF-8 -cp "out;%JUNIT_CONSOLE%" -d out-test @tests.txt || exit /b 1
del tests.txt
java -Dfile.encoding=UTF-8 -jar "%JUNIT_CONSOLE%" --class-path "out;out-test" --scan-class-path out-test --disable-banner
exit /b %ERRORLEVEL%
