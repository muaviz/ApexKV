#!/usr/bin/env bash
set -e

echo "========================================================"
echo "          ApexKV Build Script (Java 17+ / Maven)        "
echo "========================================================"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

mkdir -p target/classes target/test-classes lib

# Ensure JUnit runner exists for offline testing
if [ ! -f "lib/junit-platform-console-standalone-1.10.2.jar" ]; then
    echo "[INFO] Fetching JUnit Platform Console runner..."
    curl -fsSL -o lib/junit-platform-console-standalone-1.10.2.jar \
        https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/1.10.2/junit-platform-console-standalone-1.10.2.jar || true
fi

# If maven is installed, attempt maven build
if command -v mvn &> /dev/null; then
    echo "[INFO] Maven detected. Compiling and packaging via Maven..."
    mvn clean package -DskipTests
    echo "[SUCCESS] Build completed successfully with Maven!"
    exit 0
fi

# Fallback: Compile with standard javac and package with jar
echo "[INFO] Compiling ApexKV main sources via javac..."
find src/main/java -name "*.java" > target/sources.txt
javac -d target/classes @target/sources.txt

echo "[INFO] Compiling ApexKV test sources via javac..."
find src/test/java -name "*.java" > target/test-sources.txt
javac -d target/test-classes -cp "target/classes:lib/junit-platform-console-standalone-1.10.2.jar" @target/test-sources.txt

echo "[INFO] Packaging target/apexkv-1.0.0.jar..."
jar --create --file target/apexkv-1.0.0.jar --main-class com.apexkv.cli.ApexCliRepl -C target/classes .

echo "========================================================"
echo "[SUCCESS] ApexKV compiled and packaged successfully!"
echo "JAR: target/apexkv-1.0.0.jar"
echo "Run: ./run.sh [options]"
echo "Test: ./test.sh"
echo "========================================================"
