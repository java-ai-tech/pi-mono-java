---
entrypoint: "./build.sh"
args_schema: '{"type":"object","properties":{"env":{"type":"string","enum":["dev","staging","prod"]}}}'
---
# Build React
Build the React application for the specified environment.
