#!/bin/bash
# Linux/macOS start script. Assumes "java" is on PATH.
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec java -jar "$DIR/target/mitsa.jar" "$@"
