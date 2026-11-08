#!/usr/bin/env bash
# Build dist/SlangDictionary.jar WITHOUT Maven (chỉ cần JDK 17+).
#   ./build.sh         -> biên dịch + đóng gói JAR
#   ./build.sh test    -> biên dịch + chạy JUnit 5 (cần junit-platform-console-standalone; đặt đường dẫn bằng JUNIT_CONSOLE)
# Dùng Maven:  xvfb-run -a mvn clean verify  (Linux; test GUI cần màn hình)
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out out-test
mkdir -p out dist
javac --release 17 -encoding UTF-8 -d out $(find src/main/java -name '*.java')
mkdir -p out/data out/fonts
cp data/original/slang.raw.txt data/original/slang.normalized.txt data/original/malformed-report.txt out/data/
cp src/main/resources/fonts/*.ttf out/fonts/

if [ "${1:-}" = "test" ]; then
  JU="${JUNIT_CONSOLE:-/usr/share/java/junit-platform-console-standalone.jar}"
  [ -f "$JU" ] || { echo "Không thấy $JU. Đặt JUNIT_CONSOLE=/đường/dẫn/junit-platform-console-standalone.jar hoặc dùng: mvn test"; exit 1; }
  mkdir -p out-test
  javac --release 17 -encoding UTF-8 -cp "out:$JU" -d out-test $(find src/test/java -name '*.java')
  java -Dfile.encoding=UTF-8 -jar "$JU" --class-path "out:out-test" --scan-class-path out-test --disable-banner
  exit $?
fi

printf 'Main-Class: dictionary.ui.Main\n' > out/MANIFEST.MF
rm -f dist/SlangDictionary.jar
jar cfm dist/SlangDictionary.jar out/MANIFEST.MF -C out dictionary -C out data -C out fonts
rm -rf dist/data && mkdir -p dist/data/original
cp data/original/slang.raw.txt data/original/slang.normalized.txt data/original/malformed-report.txt dist/data/original/
echo "Built dist/SlangDictionary.jar  (run: java -jar dist/SlangDictionary.jar)"
