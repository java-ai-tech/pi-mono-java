#!/bin/bash
# 准备多 Namespace Skill 文件
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SKILLS_ROOT="$PROJECT_ROOT/skills"

echo "=== 创建多 Namespace Skill 目录 ==="

# ---- Public Skills ----
mkdir -p "$SKILLS_ROOT/public/code-review"
cat > "$SKILLS_ROOT/public/code-review/SKILL.md" << 'EOF'
# Code Review
Review code for security vulnerabilities, performance issues, and best practices.
EOF

mkdir -p "$SKILLS_ROOT/public/task-planning"
cat > "$SKILLS_ROOT/public/task-planning/SKILL.md" << 'EOF'
# Task Planning
Break down complex tasks into actionable steps with priorities and dependencies.
EOF

echo "  ✓ public/code-review"
echo "  ✓ public/task-planning"

# ---- team-alpha (前端团队) ----
mkdir -p "$SKILLS_ROOT/namespaces/team-alpha/lint-frontend"
cat > "$SKILLS_ROOT/namespaces/team-alpha/lint-frontend/SKILL.md" << 'EOF'
# Lint Frontend
Run ESLint and Prettier checks on frontend source code.
Report all violations with auto-fix suggestions.
EOF

mkdir -p "$SKILLS_ROOT/namespaces/team-alpha/build-react"
cat > "$SKILLS_ROOT/namespaces/team-alpha/build-react/SKILL.md" << 'EOF'
---
entrypoint: "./build.sh"
args_schema: '{"type":"object","properties":{"env":{"type":"string","enum":["dev","staging","prod"]}}}'
---
# Build React
Build the React application for the specified environment.
EOF

cat > "$SKILLS_ROOT/namespaces/team-alpha/build-react/build.sh" << 'SCRIPT'
#!/bin/bash
ENV="${1:-dev}"
echo "Building React app for environment: $ENV"
echo "Running: npm run build -- --mode $ENV"
echo "Build completed successfully."
SCRIPT
chmod +x "$SKILLS_ROOT/namespaces/team-alpha/build-react/build.sh"

echo "  ✓ namespaces/team-alpha/lint-frontend"
echo "  ✓ namespaces/team-alpha/build-react"

# ---- team-beta (后端团队) ----
mkdir -p "$SKILLS_ROOT/namespaces/team-beta/db-migrate"
cat > "$SKILLS_ROOT/namespaces/team-beta/db-migrate/SKILL.md" << 'EOF'
---
entrypoint: "./migrate.sh"
args_schema: '{"type":"object","properties":{"action":{"type":"string","enum":["up","down","status"]}}}'
---
# DB Migrate
Run database migrations. Supports up, down, and status commands.
EOF

cat > "$SKILLS_ROOT/namespaces/team-beta/db-migrate/migrate.sh" << 'SCRIPT'
#!/bin/bash
ACTION="${1:-status}"
echo "Database migration: $ACTION"
case "$ACTION" in
  up)     echo "Applied 3 pending migrations." ;;
  down)   echo "Rolled back 1 migration." ;;
  status) echo "Current version: v2024.03.15 (3 pending)" ;;
  *)      echo "Unknown action: $ACTION" && exit 1 ;;
esac
SCRIPT
chmod +x "$SKILLS_ROOT/namespaces/team-beta/db-migrate/migrate.sh"

mkdir -p "$SKILLS_ROOT/namespaces/team-beta/api-test"
cat > "$SKILLS_ROOT/namespaces/team-beta/api-test/SKILL.md" << 'EOF'
---
entrypoint: "./run-tests.sh"
---
# API Test
Run API integration tests against the current service endpoints.
EOF

cat > "$SKILLS_ROOT/namespaces/team-beta/api-test/run-tests.sh" << 'SCRIPT'
#!/bin/bash
echo "Running API integration tests..."
echo "  GET  /api/users       ✓ 200"
echo "  POST /api/users       ✓ 201"
echo "  GET  /api/users/1     ✓ 200"
echo "  PUT  /api/users/1     ✓ 200"
echo "  DELETE /api/users/1   ✓ 204"
echo ""
echo "5/5 tests passed."
SCRIPT
chmod +x "$SKILLS_ROOT/namespaces/team-beta/api-test/run-tests.sh"

echo "  ✓ namespaces/team-beta/db-migrate"
echo "  ✓ namespaces/team-beta/api-test"

echo ""
echo "=== 多 Namespace Skills 准备完成 ==="
echo ""
echo "目录结构:"
find "$SKILLS_ROOT" -name "SKILL.md" | sort | while read f; do
    REL=$(echo "$f" | sed "s|$SKILLS_ROOT/||")
    echo "  $REL"
done