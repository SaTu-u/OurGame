#!/usr/bin/env bash
# Builds Mila against the server libraries that claude-bridge/build.sh extracted (run that once first).
set -euo pipefail
cd "$(dirname "$0")"
LIBS=../claude-bridge/build/libs
[ -d "$LIBS" ] || { echo "run ../claude-bridge/build.sh /path/to/purpur.jar first"; exit 1; }
rm -rf build/classes && mkdir -p build/classes dist
CP=$(find "$LIBS" -name '*.jar' | tr '\n' ':')
javac --release 25 -cp "$CP" -d build/classes $(find src/main/java -name '*.java')
cp src/main/resources/* build/classes/
VERSION=$(sed -n "s/^version: '\(.*\)'/\1/p" src/main/resources/plugin.yml)
jar --create --file "dist/Mila-$VERSION.jar" -C build/classes .
echo "Built dist/Mila-$VERSION.jar"
