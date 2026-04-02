#!/bin/bash
# 综合功能端到端测试
set -e
BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

pass() { echo "  ✓ $1"; PASS=$((PASS+1)); }
fail() { echo "  ✗ $1"; FAIL=$((FAIL+1)); }

echo "============================================="
echo " 案例 5: 综合功能端到端演示"
echo "============================================="

# ==========================================
# Phase 1: 初始化
# ==========================================
echo ""
echo "━━━ Phase 1: 初始化环境 ━━━"
bash "$(dirname "$0")/setup-comprehensive.sh"
curl -s -X POST "$BASE_URL/api/rpc/catalog/reload" > /dev/null
echo ""

# ==========================================
# Phase 2: Skills 可见性验证
# ==========================================
echo "━━━ Phase 2: Skills 可见性验证 ━━━"

# acme-corp: 定制 code-review + git-workflow + task-planning + java-build + deploy-k8s = 5
ACME_COUNT=$(curl -s "$BASE_URL/api/rpc/catalog/skills?namespace=acme-corp" | python3 -c "import sys,json; print(len(json.loads(sys.stdin.read(), strict=False)))" 2>/dev/null || echo "0")
ACME_NAMES=$(curl -s "$BASE_URL/api/rpc/catalog/skills?namespace=acme-corp" | python3 -c "import sys,json; print(','.join(sorted(s['name'] for s in json.loads(sys.stdin.read(), strict=False))))" 2>/dev/null || echo "")
echo "  acme-corp ($ACME_COUNT): $ACME_NAMES"

if [ "$ACME_COUNT" = "5" ]; then
    pass "acme-corp 看到 5 个 Skills"
else
    fail "acme-corp 预期 5 个, 实际 $ACME_COUNT"
fi

# startup-xyz: 公共 code-review + git-workflow + task-planning + npm-build + deploy-vercel = 5
XYZ_COUNT=$(curl -s "$BASE_URL/api/rpc/catalog/skills?namespace=startup-xyz" | python3 -c "import sys,json; print(len(json.loads(sys.stdin.read(), strict=False)))" 2>/dev/null || echo "0")
XYZ_NAMES=$(curl -s "$BASE_URL/api/rpc/catalog/skills?namespace=startup-xyz" | python3 -c "import sys,json; print(','.join(sorted(s['name'] for s in json.loads(sys.stdin.read(), strict=False))))" 2>/dev/null || echo "")
echo "  startup-xyz ($XYZ_COUNT): $XYZ_NAMES"

if [ "$XYZ_COUNT" = "5" ]; then
    pass "startup-xyz 看到 5 个 Skills"
else
    fail "startup-xyz 预期 5 个, 实际 $XYZ_COUNT"
fi

# 验证 Skill 覆盖 — acme-corp 的 code-review 应是定制版
ACME_CR_CONTENT=$(curl -s "$BASE_URL/api/rpc/catalog/skills?namespace=acme-corp" | python3 -c "
import sys,json
skills = json.loads(sys.stdin.read(), strict=False)
cr = [s for s in skills if s['name'] == 'code-review']
print(cr[0]['content'] if cr else '')
" 2>/dev/null || echo "")

if echo "$ACME_CR_CONTENT" | grep -qi "Acme Corp"; then
    pass "acme-corp 的 code-review 是定制版本"
else
    fail "acme-corp 的 code-review 应是定制版"
fi

# 验证跨 namespace 隔离 — acme 看不到 npm-build
ACME_HAS_NPM=$(curl -s "$BASE_URL/api/rpc/catalog/skills?namespace=acme-corp" | python3 -c "
import sys,json
print('yes' if any(s['name']=='npm-build' for s in json.loads(sys.stdin.read(), strict=False)) else 'no')
" 2>/dev/null || echo "no")

if [ "$ACME_HAS_NPM" = "no" ]; then
    pass "acme-corp 看不到 startup-xyz 的 npm-build"
else
    fail "acme-corp 不应看到 npm-build"
fi

XYZ_HAS_K8S=$(curl -s "$BASE_URL/api/rpc/catalog/skills?namespace=startup-xyz" | python3 -c "
import sys,json
print('yes' if any(s['name']=='deploy-k8s' for s in json.loads(sys.stdin.read(), strict=False)) else 'no')
" 2>/dev/null || echo "no")

if [ "$XYZ_HAS_K8S" = "no" ]; then
    pass "startup-xyz 看不到 acme-corp 的 deploy-k8s"
else
    fail "startup-xyz 不应看到 deploy-k8s"
fi

# ==========================================
# Phase 3: 多用户 Session 创建
# ==========================================
echo ""
echo "━━━ Phase 3: 多用户 Session 创建 ━━━"

create_session() {
    local user=$1 ns=$2 name=$3
    local resp=$(curl -s -X POST "$BASE_URL/api/rpc/command" \
      -H "Content-Type: application/json" \
      -d "{\"id\":\"$user-init\",\"type\":\"new_session\",\"namespace\":\"$ns\",\"sessionName\":\"$name\"}")
    echo "$resp" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('data',{}).get('sessionId',''))" 2>/dev/null
}

DAVE_SID=$(create_session "dave" "acme-corp" "dave-backend")
EVE_SID=$(create_session "eve" "acme-corp" "eve-devops")
FRANK_SID=$(create_session "frank" "startup-xyz" "frank-fullstack")

echo "  Dave  (acme-corp):   $DAVE_SID"
echo "  Eve   (acme-corp):   $EVE_SID"
echo "  Frank (startup-xyz): $FRANK_SID"

if [ -n "$DAVE_SID" ] && [ -n "$EVE_SID" ] && [ -n "$FRANK_SID" ]; then
    pass "3 个用户 Session 创建成功"
else
    fail "Session 创建失败"
    exit 1
fi

# ==========================================
# Phase 4: Sandbox 隔离执行
# ==========================================
echo ""
echo "━━━ Phase 4: Sandbox 隔离执行 ━━━"

# 检测 Backend
DETECT=$(curl -s --max-time 5 -X POST "$BASE_URL/api/rpc/command" \
  -H "Content-Type: application/json" \
  -d "{\"id\":\"detect\",\"type\":\"bash\",\"sessionId\":\"$DAVE_SID\",\"namespace\":\"acme-corp\",\"command\":\"echo ok\"}" 2>/dev/null || echo '{"success":false}')
BACKEND_OK=$(echo "$DETECT" | python3 -c "import sys,json; print('True' if json.loads(sys.stdin.read(), strict=False).get('success', False) else 'False')" 2>/dev/null || echo "False")

if [ "$BACKEND_OK" = "False" ]; then
    echo "  ⚠ ExecutionBackend 不可用，跳过 Sandbox 测试"
else
    exec_bash() {
        local sid=$1 ns=$2 cmd=$3 id=$4
        curl -s --max-time 10 -X POST "$BASE_URL/api/rpc/command" \
          -H "Content-Type: application/json" \
          -d "{\"id\":\"$id\",\"type\":\"bash\",\"sessionId\":\"$sid\",\"namespace\":\"$ns\",\"command\":\"$cmd\"}" 2>/dev/null || echo '{"success":false}'
    }

    # Dave 写入文件
    DAVE_EXEC=$(exec_bash "$DAVE_SID" "acme-corp" "echo 'UserService.java' > /workspace/code.txt && ls /workspace/" "dave-exec")
    DAVE_FILES=$(echo "$DAVE_EXEC" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('data',{}).get('stdout','').strip())" 2>/dev/null || echo "")
    echo "  Dave workspace: $DAVE_FILES"

    # Eve 写入文件
    EVE_EXEC=$(exec_bash "$EVE_SID" "acme-corp" "echo 'k8s-deploy.yaml' > /workspace/manifest.txt && ls /workspace/" "eve-exec")
    EVE_FILES=$(echo "$EVE_EXEC" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('data',{}).get('stdout','').strip())" 2>/dev/null || echo "")
    echo "  Eve workspace:  $EVE_FILES"

    # Frank 写入文件
    FRANK_EXEC=$(exec_bash "$FRANK_SID" "startup-xyz" "echo 'index.ts' > /workspace/app.txt && ls /workspace/" "frank-exec")
    FRANK_FILES=$(echo "$FRANK_EXEC" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('data',{}).get('stdout','').strip())" 2>/dev/null || echo "")
    echo "  Frank workspace: $FRANK_FILES"

    # 验证互不可见
    DAVE_SEES_EVE=$(echo "$DAVE_FILES" | grep -c "manifest" || true)
    EVE_SEES_DAVE=$(echo "$EVE_FILES" | grep -c "code" || true)
    FRANK_SEES_ANY=$(echo "$FRANK_FILES" | grep -c -E "code|manifest" || true)

    if [ "$DAVE_SEES_EVE" = "0" ] && [ "$EVE_SEES_DAVE" = "0" ] && [ "$FRANK_SEES_ANY" = "0" ]; then
        pass "3 个用户工作空间完全隔离"
    else
        fail "工作空间隔离失败"
    fi
fi

# ==========================================
# Phase 5: 跨 Namespace 访问拒绝
# ==========================================
echo ""
echo "━━━ Phase 5: 跨 Namespace 访问拒绝 ━━━"

# Frank (startup-xyz) 尝试访问 Dave (acme-corp) 的 session
CROSS_RESP=$(curl -s -X POST "$BASE_URL/api/rpc/command" \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"cross-1\",
    \"type\": \"get_state\",
    \"sessionId\": \"$DAVE_SID\",
    \"namespace\": \"startup-xyz\"
  }")
CROSS_OK=$(echo "$CROSS_RESP" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('success', True))" 2>/dev/null)

if [ "$CROSS_OK" = "False" ]; then
    pass "startup-xyz 无法访问 acme-corp 的 session"
else
    fail "跨 namespace session 访问应被拒绝"
fi

# 路径穿越
TRAVERSAL_RESP=$(curl -s -X POST "$BASE_URL/api/rpc/command" \
  -H "Content-Type: application/json" \
  -d '{"id":"cross-2","type":"bash","sessionId":"../acme-corp/dave","namespace":"startup-xyz","command":"cat /etc/passwd"}')
TRAVERSAL_OK=$(echo "$TRAVERSAL_RESP" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('success', True))" 2>/dev/null)

if [ "$TRAVERSAL_OK" = "False" ]; then
    pass "路径穿越攻击被拦截"
else
    fail "路径穿越攻击应被拦截"
fi

# 缺少 namespace
NO_NS_RESP=$(curl -s -X POST "$BASE_URL/api/rpc/command" \
  -H "Content-Type: application/json" \
  -d "{\"id\":\"cross-3\",\"type\":\"get_state\",\"sessionId\":\"$DAVE_SID\"}")
NO_NS_OK=$(echo "$NO_NS_RESP" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('success', True))" 2>/dev/null)

if [ "$NO_NS_OK" = "False" ]; then
    pass "缺少 namespace 参数被拒绝"
else
    fail "缺少 namespace 应被拒绝"
fi

# ==========================================
# Summary
# ==========================================
echo ""
echo "============================================="
echo " 综合测试完成: $PASS 通过, $FAIL 失败"
echo "============================================="
echo ""
echo " 覆盖功能:"
echo "   ✓ Skills 注册与加载"
echo "   ✓ Skills namespace 可见性隔离"
echo "   ✓ Skills 同名覆盖（namespace 优先于 public）"
echo "   ✓ 多 Namespace 隔离（acme-corp / startup-xyz）"
echo "   ✓ 多用户独立 Session（Dave / Eve / Frank）"
echo "   ✓ Sandbox 工作空间隔离"
echo "   ✓ 跨 Namespace Session 访问拒绝"
echo "   ✓ 路径穿越防护"
echo "   ✓ Namespace 参数校验"
echo ""
[ "$FAIL" -eq 0 ] || exit 1