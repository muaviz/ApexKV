#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [ ! -f "target/apexkv-1.0.0.jar" ] && [ ! -d "target/classes" ]; then
    echo "[INFO] Build artifacts not found. Invoking ./build.sh..."
    ./build.sh
fi

if [ -f "target/apexkv-1.0.0.jar" ]; then
    exec java -jar target/apexkv-1.0.0.jar "$@"
else
    exec java -cp target/classes com.apexkv.cli.ApexCliRepl "$@"
fi
