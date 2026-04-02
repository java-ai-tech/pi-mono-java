#!/bin/bash
# 测试多 Namespace 隔离
set -e
BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

pass() { echo "  ✓ $1"; PASS=$((PASS+1)); }
fail() { echo "  ✗ $1"; FAIL=$((FAIL+1)); }

echo "========================================="
echo " 案例 3: 多 Namespace 隔离"
echo "========================================="

# Step 1: 准备 Skills
echo ""
echo "[Step 1] 准备多 Namespace Skills..."
bash "$(dirname "$0")/setup-namespaces.sh"
curl -s -X POST "$BASE_URL/api/rpc/catalog/reload" > /dev/null

# Step 2: 验证可见性矩阵
echo ""
echo "[Step 2] 验证 Skill 可见性矩阵..."

check_skills() {
    local ns=$1
    local expected=$2
    local desc=$3

    COUNT=$(curl -s "$BASE_URL/api/rpc/catalog/skills?namespace=$ns" | python3 -c "import sys,json; print(len(json.loads(sys.stdin.read(), strict=False)))" 2>/dev/null || echo "0")
    NAMES=$(curl -s "$BASE_URL/api/rpc/catalog/skills?namespace=$ns" | python3 -c "import sys,json; print(','.join(sorted(s['name'] for s in json.loads(sys.stdin.read(), strict=False))))" 2>/dev/null || echo "")

    echo "  $ns: [$NAMES] (共 $COUNT 个)"
    if [ "$COUNT" = "$expected" ]; then
        pass "$desc"
    else
        fail "$desc (预期 $expected, 实际 $COUNT)"
    fi
}

check_skills "team-alpha" "4" "team-alpha 看到 4 个 Skills (2 public + 2 private)"
check_skills "team-beta" "4" "team-beta 看到 4 个 Skills (2 public + 2 private)"
check_skills "unknown-team" "2" "unknown-team 只看到 2 个公共 Skills"

# Step 3: 验证私有 Skill 不可跨 Namespace 访问
echo ""
echo "[Step 3] 验证跨 Namespace Skill 隔离..."

HAS_DB_MIGRATE=$(curl -s "$BASE_URL/api/rpc/catalog/skills?namespace=team-alpha" | python3 -c "
import sys,json
skills = json.loads(sys.stdin.read(), strict=False)
print('yes' if any(s['name']=='db-migrate' for s in skills) else 'no')
" 2>/dev/null || echo "no")

if [ "$HAS_DB_MIGRATE" = "no" ]; then
    pass "team-alpha 看不到 team-beta 的 db-migrate"
else
    fail "team-alpha 不应看到 team-beta 的 db-migrate"
fi

HAS_LINT=$(curl -s "$BASE_URL/api/rpc/catalog/skills?namespace=team-beta" | python3 -c "
import sys,json
skills = json.loads(sys.stdin.read(), strict=False)
print('yes' if any(s['name']=='lint-frontend' for s in skills) else 'no')
" 2>/dev/null || echo "no")

if [ "$HAS_LINT" = "no" ]; then
    pass "team-beta 看不到 team-alpha 的 lint-frontend"
else
    fail "team-beta 不应看到 team-alpha 的 lint-frontend"
fi

# Step 4: 跨 Namespace Session 访问拒绝
echo ""
echo "[Step 4] 验证跨 Namespace Session 拒绝..."

ALPHA_RESP=$(curl -s -X POST "$BASE_URL/api/rpc/command" \
  -H "Content-Type: application/json" \
  -d '{"id":"ns-test-1","type":"new_session","namespace":"team-alpha"}')
ALPHA_SID=$(echo "$ALPHA_RESP" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('data',{}).get('sessionId',''))" 2>/dev/null)

if [ -n "$ALPHA_SID" ]; then
    CROSS_RESP=$(curl -s -X POST "$BASE_URL/api/rpc/command" \
      -H "Content-Type: application/json" \
      -d "{
        \"id\": \"ns-test-2\",
        \"type\": \"get_state\",
        \"sessionId\": \"$ALPHA_SID\",
        \"namespace\": \"team-beta\"
      }")
    CROSS_SUCCESS=$(echo "$CROSS_RESP" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('success', True))" 2>/dev/null)

    if [ "$CROSS_SUCCESS" = "False" ]; then
        pass "team-beta 访问 team-alpha session 被拒绝"
    else
        fail "跨 Namespace session 访问应被拒绝"
    fi
else
    echo "  (跳过: 无法创建 session)"
fi

# Step 5: 缺少 Namespace
echo ""
echo "[Step 5] 验证 Namespace 参数校验..."
NO_NS_RESP=$(curl -s -X POST "$BASE_URL/api/rpc/command" \
  -H "Content-Type: application/json" \
  -d '{"id":"ns-test-3","type":"bash","sessionId":"any","command":"echo hi"}')
NO_NS_SUCCESS=$(echo "$NO_NS_RESP" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('success', True))" 2>/dev/null)

if [ "$NO_NS_SUCCESS" = "False" ]; then
    pass "缺少 namespace 参数被拒绝"
else
    fail "缺少 namespace 参数应被拒绝"
fi

echo ""
echo "========================================="
echo " 案例 3 测试完成: $PASS 通过, $FAIL 失败"
echo "========================================="
[ "$FAIL" -eq 0 ] || exit 1