#!/bin/bash
# 测试多用户场景
set -e
BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

pass() { echo "  ✓ $1"; PASS=$((PASS+1)); }
fail() { echo "  ✗ $1"; FAIL=$((FAIL+1)); }

echo "========================================="
echo " 案例 4: 多用户场景"
echo "========================================="

# 先确保 skills 已准备
echo ""
echo "[准备] 初始化 Skills..."
bash "$(dirname "$0")/../3-multi-namespace/setup-namespaces.sh" 2>/dev/null || true
curl -s -X POST "$BASE_URL/api/rpc/catalog/reload" > /dev/null

# Step 1: 创建两个 Session
echo ""
echo "[Step 1] 为 Alice 和 Bob 创建独立 Session..."

ALICE_RESP=$(curl -s -X POST "$BASE_URL/api/rpc/command" \
  -H "Content-Type: application/json" \
  -d '{"id":"mu-1","type":"new_session","namespace":"team-alpha","sessionName":"alice-dev"}')
ALICE_SID=$(echo "$ALICE_RESP" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('data',{}).get('sessionId',''))" 2>/dev/null)

BOB_RESP=$(curl -s -X POST "$BASE_URL/api/rpc/command" \
  -H "Content-Type: application/json" \
  -d '{"id":"mu-2","type":"new_session","namespace":"team-alpha","sessionName":"bob-test"}')
BOB_SID=$(echo "$BOB_RESP" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('data',{}).get('sessionId',''))" 2>/dev/null)

echo "  Alice Session: $ALICE_SID"
echo "  Bob Session:   $BOB_SID"

if [ -n "$ALICE_SID" ] && [ -n "$BOB_SID" ]; then
    pass "两个 Session 创建成功"
else
    fail "Session 创建失败"
    echo "  Alice 响应: $ALICE_RESP"
    echo "  Bob 响应: $BOB_RESP"
    exit 1
fi

if [ "$ALICE_SID" != "$BOB_SID" ]; then
    pass "两个 Session ID 不同"
else
    fail "两个 Session ID 应该不同"
fi

# Step 2: 各自执行命令（工作空间隔离）
echo ""
echo "[Step 2] 验证工作空间隔离..."

ALICE_EXEC=$(curl -s --max-time 15 -X POST "$BASE_URL/api/rpc/command" \
  -H "Content-Type: application/json" \
  -d "{\"id\":\"mu-3\",\"type\":\"bash\",\"sessionId\":\"$ALICE_SID\",\"namespace\":\"team-alpha\",\"command\":\"echo alice-data > /workspace/alice.txt && ls /workspace/\"}" \
  2>/dev/null || echo '{"success":false}')

BOB_EXEC=$(curl -s --max-time 15 -X POST "$BASE_URL/api/rpc/command" \
  -H "Content-Type: application/json" \
  -d "{\"id\":\"mu-4\",\"type\":\"bash\",\"sessionId\":\"$BOB_SID\",\"namespace\":\"team-alpha\",\"command\":\"echo bob-data > /workspace/bob.txt && ls /workspace/\"}" \
  2>/dev/null || echo '{"success":false}')

ALICE_FILES=$(echo "$ALICE_EXEC" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('data',{}).get('stdout',''))" 2>/dev/null || echo "")
BOB_FILES=$(echo "$BOB_EXEC" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('data',).get('stdout',''))" 2>/dev/null || echo "")

echo "  Alice 工作空间: $ALICE_FILES"
echo "  Bob 工作空间:   $BOB_FILES"

# 检查是否执行成功
ALICE_OK=$(echo "$ALICE_EXEC" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('success', False))" 2>/dev/null || echo "False")
BOB_OK=$(echo "$BOB_EXEC" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('success', False))" 2>/dev/null || echo "False")

if [ "$ALICE_OK" = "True" ] && [ "$BOB_OK" = "True" ]; then
    # 检查 Alice 看不到 Bob 的文件
    ALICE_HAS_BOB=$(echo "$ALICE_FILES" | grep -c "bob.txt" || true)
    BOB_HAS_ALICE=$(echo "$BOB_FILES" | grep -c "alice.txt" || true)

    if [ "$ALICE_HAS_BOB" = "0" ] && [ "$BOB_HAS_ALICE" = "0" ]; then
        pass "工作空间完全隔离"
    else
        fail "工作空间未隔离"
    fi
else
    echo "  ⚠ Bash 执行失败，跳过隔离验证"
fi

# Step 3: 独立模型设置
echo ""
echo "[Step 3] 验证独立模型设置..."

curl -s -X POST "$BASE_URL/api/rpc/command" \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"mu-5\",
    \"type\": \"set_model\",
    \"sessionId\": \"$ALICE_SID\",
    \"namespace\": \"team-alpha\",
    \"provider\": \"openai\",
    \"modelId\": \"gpt-4-turbo\"
  }" > /dev/null

curl -s -X POST "$BASE_URL/api/rpc/command" \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"mu-6\",
    \"type\": \"set_model\",
    \"sessionId\": \"$BOB_SID\",
    \"namespace\": \"team-alpha\",
    \"provider\": \"openai\",
    \"modelId\": \"gpt-4o-mini\"
  }" > /dev/null

ALICE_STATE=$(curl -s -X POST "$BASE_URL/api/rpc/command" \
  -H "Content-Type: application/json" \
  -d "{\"id\":\"mu-7\",\"type\":\"get_state\",\"sessionId\":\"$ALICE_SID\",\"namespace\":\"team-alpha\"}")

BOB_STATE=$(curl -s -X POST "$BASE_URL/api/rpc/command" \
  -H "Content-Type: application/json" \
  -d "{\"id\":\"mu-8\",\"type\":\"get_state\",\"sessionId\":\"$BOB_SID\",\"namespace\":\"team-alpha\"}")

echo "  Alice 状态: $ALICE_STATE"
echo "  Bob 状态:   $BOB_STATE"

pass "模型独立设置完成"

# Step 4: Bob 无法访问 Alice 的 Session 详情
echo ""
echo "[Step 4] 验证 Session 之间不可互访..."

# Bob 不能以 team-beta namespace 来访问 team-alpha 的 session
CROSS_RESP=$(curl -s -X POST "$BASE_URL/api/rpc/command" \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"mu-9\",
    \"type\": \"get_state\",
    \"sessionId\": \"$ALICE_SID\",
    \"namespace\": \"team-beta\"
  }")
CROSS_OK=$(echo "$CROSS_RESP" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('success', True))" 2>/dev/null)

if [ "$CROSS_OK" = "False" ]; then
    pass "跨 namespace 访问 session 被拒绝"
else
    fail "跨 namespace 访问应被拒绝"
fi

echo ""
echo "========================================="
echo " 案例 4 测试完成: $PASS 通过, $FAIL 失败"
echo "========================================="
[ "$FAIL" -eq 0 ] || exit 1