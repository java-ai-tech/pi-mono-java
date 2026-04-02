#!/bin/bash
# 准备综合演示的 Skill 文件
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SKILLS_ROOT="$PROJECT_ROOT/skills"

echo "=== 创建综合演示 Skill 目录 ==="

# ---- Public Skills ----
mkdir -p "$SKILLS_ROOT/public/code-review"
cat > "$SKILLS_ROOT/public/code-review/SKILL.md" << 'EOF'
# Code Review
Review code for security vulnerabilities, performance issues, and best practices.

## Checklist
- [ ] No hardcoded secrets or credentials
- [ ] Input validation on all external inputs
- [ ] Error handling covers edge cases
- [ ] No SQL injection or XSS vulnerabilities
EOF
echo "  ✓ public/code-review"

mkdir -p "$SKILLS_ROOT/public/git-workflow"
cat > "$SKILLS_ROOT/public/git-workflow/SKILL.md" << 'EOF'
# Git Workflow
Guide the user through a standard Git branching workflow.

## Steps
1. Create feature branch from main
2. Make changes with atomic commits
3. Push and create pull request
4. Request review and address feedback
5. Merge after approval
EOF
echo "  ✓ public/git-workflow"

# ---- acme-corp (企业客户 A) ----

# 覆盖公共 code-review（定制版本）
mkdir -p "$SKILLS_ROOT/namespaces/acme-corp/code-review"
cat > "$SKILLS_ROOT/namespaces/acme-corp/code-review/SKILL.md" << 'EOF'
# Code Review (Acme Corp Standard)
Review code following Acme Corp's internal coding standards.

## Acme Corp 编码规范
- 所有 public 方法必须有 Javadoc
- 使用 SLF4J 而非 System.out
- DAO 层必须使用参数化查询
- REST Controller 必须标注 @Validated
- 日志必须包含 traceId
- 敏感数据必须脱敏后才可记录日志

## 严重级别
- [P0] 安全漏洞 — 必须立即修复
- [P1] 违反公司规范 — 需要修复
- [P2] 建议优化 — 可选修复
EOF
echo "  ✓ namespaces/acme-corp/code-review (覆盖公共版本)"

mkdir -p "$SKILLS_ROOT/namespaces/acme-corp/java-build"
cat > "$SKILLS_ROOT/namespaces/acme-corp/java-build/SKILL.md" << 'EOF'
---
entrypoint: "./build.sh"
args_schema: '{"type":"object","properties":{"module":{"type":"string"},"skipTests":{"type":"boolean"}}}'
---
# Java Build
Build Java Maven project. Supports building specific modules and skipping tests.
EOF

cat > "$SKILLS_ROOT/namespaces/acme-corp/java-build/build.sh" << 'SCRIPT'
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
SCRIPT
chmod +x "$SKILLS_ROOT/namespaces/acme-corp/java-build/build.sh"
echo "  ✓ namespaces/acme-corp/java-build"

mkdir -p "$SKILLS_ROOT/namespaces/acme-corp/deploy-k8s"
cat > "$SKILLS_ROOT/namespaces/acme-corp/deploy-k8s/SKILL.md" << 'EOF'
---
entrypoint: "./deploy.sh"
args_schema: '{"type":"object","properties":{"service":{"type":"string"},"env":{"type":"string","enum":["staging","prod"]}}}'
---
# Deploy K8s
Deploy a service to Acme Corp's Kubernetes cluster.
EOF

cat > "$SKILLS_ROOT/namespaces/acme-corp/deploy-k8s/deploy.sh" << 'SCRIPT'
#!/bin/bash
SERVICE="${1:-unknown}"
ENV="${2:-staging}"
echo "=== Acme Corp K8s Deployment ==="
echo "Service: $SERVICE"
echo "Environment: $ENV"
echo "Cluster: acme-$ENV.k8s.internal"
echo ""
echo "Steps:"
echo "  1. Building Docker image..."
echo "  2. Pushing to registry.acme.internal/$SERVICE:latest"
echo "  3. Applying k8s manifests..."
echo "  4. Waiting for rollout..."
echo ""
echo "✓ Deployment completed. Pod status: Running (3/3)"
SCRIPT
chmod +x "$SKILLS_ROOT/namespaces/acme-corp/deploy-k8s/deploy.sh"
echo "  ✓ namespaces/acme-corp/deploy-k8s"

# ---- startup-xyz (企业客户 B) ----

mkdir -p "$SKILLS_ROOT/namespaces/startup-xyz/npm-build"
cat > "$SKILLS_ROOT/namespaces/startup-xyz/npm-build/SKILL.md" << 'EOF'
---
entrypoint: "./build.sh"
args_schema: '{"type":"object","properties":{"target":{"type":"string","enum":["dev","prod"]}}}'
---
# NPM Build
Build the Node.js/Next.js application for the specified target.
EOF

cat > "$SKILLS_ROOT/namespaces/startup-xyz/npm-build/build.sh" << 'SCRIPT'
#!/bin/bash
TARGET="${1:-dev}"
echo "=== Startup XYZ NPM Build ==="
echo "Target: $TARGET"
echo "Running: npm run build:$TARGET"
echo ""
echo "Compiled successfully!"
echo "  Bundle size: 142 kB (gzip)"
echo "  Pages: 12"
echo "  Build time: 3.2s"
SCRIPT
chmod +x "$SKILLS_ROOT/namespaces/startup-xyz/npm-build/build.sh"
echo "  ✓ namespaces/startup-xyz/npm-build"

mkdir -p "$SKILLS_ROOT/namespaces/startup-xyz/deploy-vercel"
cat > "$SKILLS_ROOT/namespaces/startup-xyz/deploy-vercel/SKILL.md" << 'EOF'
---
entrypoint: "./deploy.sh"
args_schema: '{"type":"object","properties":{"env":{"type":"string","enum":["preview","production"]}}}'
---
# Deploy Vercel
Deploy the application to Vercel platform.
EOF

cat > "$SKILLS_ROOT/namespaces/startup-xyz/deploy-vercel/deploy.sh" << 'SCRIPT'
#!/bin/bash
ENV="${1:-preview}"
echo "=== Startup XYZ Vercel Deploy ==="
echo "Environment: $ENV"
echo "Running: vercel deploy $([ "$ENV" = "production" ] && echo "--prod")"
echo ""
echo "✓ Deployed to: https://startup-xyz-${ENV}.vercel.app"
echo "  Status: Ready"
echo "  Region: hnd1 (Tokyo)"
SCRIPT
chmod +x "$SKILLS_ROOT/namespaces/startup-xyz/deploy-vercel/deploy.sh"
echo "  ✓ namespaces/startup-xyz/deploy-vercel"

echo ""
echo "=== 综合演示 Skills 准备完成 ==="
echo ""
echo "目录结构:"
find "$SKILLS_ROOT" -name "SKILL.md" | sort | while read f; do
    REL=$(echo "$f" | sed "s|$SKILLS_ROOT/||")
    echo "  $REL"
done
echo ""
echo "下一步: curl -X POST http://localhost:8080/api/rpc/catalog/reload"