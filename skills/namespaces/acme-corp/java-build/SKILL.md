---
entrypoint: "./build.sh"
args_schema: '{"type":"object","properties":{"module":{"type":"string"},"skipTests":{"type":"boolean"}}}'
---
# Java Build
Build Java Maven project. Supports building specific modules and skipping tests.
