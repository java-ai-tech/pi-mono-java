---
entrypoint: "./migrate.sh"
args_schema: '{"type":"object","properties":{"action":{"type":"string","enum":["up","down","status"]}}}'
---
# DB Migrate
Run database migrations. Supports up, down, and status commands.
