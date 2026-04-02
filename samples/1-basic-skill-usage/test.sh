#!/bin/bash
# 测试基础 Skill 使用
set -e
BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "========================================="
echo " 案例 1: 基础 Skill 使用"
echo "========================================="

# Step 1: 准备 Skills
echo ""
echo "[Step 1] 准备 Skill 文件..."
bash "$(dirname "$0")/setup-skills.sh"

# Step 2: 重载 Catalog
echo ""
echo "[Step 2] 重载 Catalog..."
RELOAD=$(curl -s -X POST "$BASE_URL/api/rpc/catalog/reload")
echo "  响应: $RELOAD"

# Step 3: 查询 Skills
echo ""
echo "[Step 3] 查询 demo namespace 的可见 Skills..."
SKILL_COUNT=$(curl -s "$BASE_URL/api/rpc/catalog/skills?namespace=demo" | python3 -c "import sys,json; print(len(json.loads(sys.stdin.read(), strict=False)))" 2>/dev/null || echo "0")
echo "  Skill 数量: $SKILL_COUNT"

if [ "$SKILL_COUNT" -ge 2 ]; then
    echo "  ✓ 通过: 至少包含 2 个 Skill"
else
    echo "  ✗ 失败: 预期至少 2 个 Skill，实际 $SKILL_COUNT"
    exit 1
fi

# Step 4: 验证 Skill 属性
echo ""
echo "[Step 4] 验证 Skill 属性..."

HAS_REVIEW=$(curl -s "$BASE_URL/api/rpc/catalog/skills?namespace=demo" | python3 -c "
import sys,json
skills = json.loads(sys.stdin.read(), strict=False)
review = [s for s in skills if s['name'] == 'code-review']
print('yes' if review and review[0].get('entrypoint') is None else 'no')
" 2>/dev/null || echo "no")

HAS_GREETING=$(curl -s "$BASE_URL/api/rpc/catalog/skills?namespace=demo" | python3 -c "
import sys,json
skills = json.loads(sys.stdin.read(), strict=False)
greeting = [s for s in skills if s['name'] == 'greeting']
print('yes' if greeting and greeting[0].get('entrypoint') else 'no')
" 2>/dev/null || echo "no")

echo "  code-review (指令型, 无 entrypoint): $HAS_REVIEW"
echo "  greeting (可执行型, 有 entrypoint): $HAS_GREETING"

if [ "$HAS_REVIEW" = "yes" ] && [ "$HAS_GREETING" = "yes" ]; then
    echo "  ✓ 全部通过"
else
    echo "  ✗ 失败"
    exit 1
fi

echo ""
echo "========================================="
echo " 案例 1 测试完成"
echo "========================================="