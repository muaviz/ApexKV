#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "========================================================"
echo "          ApexKV Automated Test Suite Runner            "
echo "========================================================"

if command -v mvn &> /dev/null; then
    echo "[INFO] Running tests via Maven Surefire..."
    mvn test
    exit 0
fi

# Fallback: Standalone JUnit Platform Console
if [ ! -d "target/test-classes" ] || [ ! -d "target/classes" ]; then
    echo "[INFO] Compiling sources and tests..."
    ./build.sh
fi

echo "[INFO] Executing JUnit 5 test suites via JUnit Platform Console..."
java -jar lib/junit-platform-console-standalone-1.10.2.jar \
    --class-path "target/classes:target/test-classes" \
    --scan-class-path
