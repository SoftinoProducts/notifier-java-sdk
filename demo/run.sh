#!/usr/bin/env bash
# Builds the SDK locally and runs the non-blocking demo.
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q -DskipTests install
cd demo
mvn -q compile exec:java
