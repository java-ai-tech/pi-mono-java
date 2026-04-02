#!/bin/bash
MODULE="${1:-all}"
SKIP_TESTS="${2:-false}"
echo "=== Acme Corp Java Build ==="
echo "Module: $MODULE"
echo "Skip Tests: $SKIP_TESTS"
if [ "$MODULE" = "all" ]; then
    echo "Running: mvn clean package"
else
    echo "Running: mvn clean package -pl $MODULE"
fi
echo "BUILD SUCCESS"
echo "Total time: 12.345s"
