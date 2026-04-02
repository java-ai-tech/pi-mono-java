---
entrypoint: "./build.sh"
args_schema: '{"type":"object","properties":{"target":{"type":"string","enum":["dev","prod"]}}}'
---
# NPM Build
Build the Node.js/Next.js application for the specified target.
