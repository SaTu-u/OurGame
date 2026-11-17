#!/usr/bin/env bash
# Builds ClaudeBridge against the exact API bundled in your Purpur jar.
#
#   ./build.sh /path/to/purpur-26.2.jar
#
# Needs JDK 25 (javac, jar) and curl. Output: dist/ClaudeBridge-<version>.jar
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
PURPUR_JAR="$(realpath "${1:?usage: ./build.sh /path/to/purpur.jar}")"
BUILD="$ROOT/build"
LIBS="$BUILD/libs"
CLASSES="$BUILD/classes"
VERSION="$(sed -n "s/^version: *'\{0,1\}\([^']*\)'\{0,1\}$/\1/p" "$ROOT/src/main/resources/plugin.yml")"

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"

rm -rf "$CLASSES" "$BUILD/extract"
mkdir -p "$LIBS" "$CLASSES" "$BUILD/extract" "$ROOT/dist"

# 1. Compile classpath: the libraries bundled inside the Purpur jar (Paper/Purpur API, Adventure, SQLite driver...).
(cd "$BUILD/extract" && "$JAR" xf "$PURPUR_JAR" META-INF/libraries)
find "$BUILD/extract/META-INF/libraries" -name '*.jar' -exec cp {} "$LIBS/" \;

# 2. Compile-only jars that are not inside the Purpur bundle (Gson is provided by the server at runtime).
fetch() { # <maven path> <sha1>
  local file="$LIBS/$(basename "$1")"
  if [ ! -s "$file" ]; then
    curl -fsSL --retry 5 --retry-all-errors --retry-delay 10 -o "$file" "https://repo1.maven.org/maven2/$1"
  fi
  if [ "$(sha1sum "$file" | awk '{print $1}')" != "$2" ]; then
    echo "checksum mismatch for $(basename "$1")" >&2
    rm -f "$file"
    exit 1
  fi
}
fetch com/google/code/gson/gson/2.11.0/gson-2.11.0.jar 527175ca6d81050b53bdd4c457a6d6e017626b0e
fetch org/jetbrains/annotations/26.0.2/annotations-26.0.2.jar c7ce3cdeda3d18909368dfe5977332dfad326c6d
fetch org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar 7425a601c1c7ec76645a78d22b8c6a627edee507
fetch com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.jar 03fcc0a259f724c7de54a6a55ea7e26d3d5c0cac
# JSch (SSH tunnels) is bundled into the plugin jar, the server does not have it.
JSCH=jsch-2.28.7.jar
fetch com/github/mwiede/jsch/2.28.7/$JSCH 0a91185cefe0dac6d0720684eebb53f83d8160ce

# 3. Compile.
CP="$(find "$LIBS" -name '*.jar' | tr '\n' ':')"
find "$ROOT/src/main/java" -name '*.java' > "$BUILD/sources.txt"
"$JAVAC" --release 25 -encoding UTF-8 -Xlint:-options -cp "$CP" -d "$CLASSES" @"$BUILD/sources.txt"
cp -r "$ROOT/src/main/resources/." "$CLASSES/"

# 4. Bundle JSch. It is a multi-release jar (Ed25519 lives in META-INF/versions/15), so the plugin jar is multi-release too.
(cd "$CLASSES" && "$JAR" xf "$LIBS/$JSCH" && rm -f META-INF/MANIFEST.MF)
printf 'Manifest-Version: 1.0\nMulti-Release: true\n' > "$BUILD/MANIFEST.MF"

# 5. Package.
OUT="$ROOT/dist/ClaudeBridge-$VERSION.jar"
"$JAR" --create --file "$OUT" --manifest "$BUILD/MANIFEST.MF" -C "$CLASSES" .
echo "Built $OUT"
