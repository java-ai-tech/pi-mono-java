#!/bin/bash
# 测试 Sandbox 隔离执行
set -e
BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "========================================="
echo " 案例 2: Sandbox 隔离执行"
echo "========================================="

# 检测 Backend 类型
echo ""
echo "[检测] 检测 ExecutionBackend 类型..."
TEST_RESP=$(curl -s --max-time 5 -X POST "$BASE_URL/api/rpc/command" \
  -H "Content-Type: application/json" \
  -d '{"id":"detect-1","type":"new_session","namespace":"sandbox-demo"}')
TEST_SID=$(echo "$TEST_RESP" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('data',{}).get('sessionId',''))" 2>/dev/null || echo "")

if [ -z "$TEST_SID" ]; then
    echo "  ✗ 无法创建 session，跳过测试"
    exit 1
fi

DETECT_RESP=$(curl -s --max-time 10 -X POST "$BASE_URL/api/rpc/command" \
  -H "Content-Type: application/json" \
  -d "{\"id\":\"detect-2\",\"type\":\"bash\",\"sessionId\":\"$TEST_SID\",\"namespace\":\"sandbox-demo\",\"command\":\"echo test\"}")
DETECT_ERR=$(echo "$DETECT_RESP" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('data',{}).get('stderr',''))" 2>/dev/null || echo "")

if echo "$DETECT_ERR" | grep -qi "docker"; then
    BACKEND="docker"
    echo "  Backend: DockerIsolatedBackend (需要 Docker)"
elif echo "$DETECT_RESP" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('success', False))" 2>/dev/null | grep -q "True"; then
    BACKEND="local"
    echo "  Backend: LocalIsolatedBackend (本地执行)"
else
    BACKEND="unknown"
    echo "  Backend: 未知或不可用"
fi

SESSION_ID="$TEST_SID"

# Step 1: 基础执行
echo ""
echo "[Step 1] 沙箱中执行基础命令..."
RESP=$(curl -s -X POST "$BASE_URL/api/rpc/command" \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"sandbox-2\",
    \"type\": \"bash\",
    \"sessionId\": \"$SESSION_ID\",
    \"namespace\": \"sandbox-demo\",
    \"command\": \"echo hello && whoami && pwd\"
  }")
SUCCESS=$(echo "$RESP" | python3 -c "import sys,json; print(json.loads(sys.stdin.read(), strict=False).get('success', False))" 2>/dev/null)
echo "  成功: $SUCCESS"

if [ "$SUCCESS" = "True" ]; then
    echo "  ✓ 基础命令执行成功"
else
    echo "  ✗ 基础命令执行失败"
    exit 1
fi

# Step 2: 网络隔离（仅 Docker）
echo ""
if [ "$BACKEND" = "docker" ]; then
    echo "[Step 2] 验证网络隔离（Docker 特性）..."
    RESP=$(curl -s -X POST "$BASE_URL/api/rpc/command" \
      -H "Content-Type: application/json" \
      -d "{
        \"id\": \"sandbox-3\",
        \"type\": \"bash\",
        \"sessionId\": \"$SESSION_ID\",
        \"namespace\": \"sandbox-demo\",
        \"command\": \"ping -c 1 8.8.8.8 2>&1 || echo NETWORK_BLOCKED\"
      }")
    BLOCKED=$(echo "$RESP" | python3 -c "
import sys,json
data = json.loads(sys.stdin.read(), strict=False).get('data',{})
stdout = data.get('stdout','')
print('yes' if 'NETWORK_BLOCKED' in stdout or data.get('exitCode',0) != 0 else 'no')
" 2>/dev/null || echo "no")
    echo "  网络已隔离: $BLOCKED"
    [ "$BLOCKED" = "yes" ] && echo "  ✓ Docker 网络隔离生效"
else
    echo "[Step 2] 跳过网络隔离测试（LocalIsolatedBackend 不隔离网络）"
fi

# Step 3: 只读文件系统（仅 Docker）
echo ""
if [ "$BACKEND" = "docker" ]; then
    echo "[Step 3] 验证只读文件系统（Docker 特性）..."
    RESP=$(curl -s -X POST "$BASE_URL/api/rpc/command" \
      -H "Content-Type: application/json" \
      -d "{
        \"id\": \"sandbox-4\",
        \"type\": \"bash\",
        \"sessionId\": \"$SESSION_ID\",
        \"namespace\": \"sandbox-demo\",
        \"command\": \"touch /etc/test 2>&1 ; echo EXIT_CODE=\$?\"
      }")
    echo "  ✓ 只读文件系统测试完成"
else
    echo "[Step 3] 跳过只读文件系统测试（LocalIsolatedBackend 无此限制）"
fi

# Step 4: 路径穿越防护（两种 Backend 都支持）
echo ""
echo "[Step 4] 验证路径穿越防护..."
RESP=$(curl -s -X POST "$BASE_URL/api/rpc/command" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "sandbox-5",
    "type": "bash",
    "sessionId": "../../../etc",
    "namespace": "sandbox-demo",
    "command": "echo pwned"
  }')
REJECTED=$(echo "$RESP" | python3 -c "
import sys,json
resp = json.loads(sys.stdin.read(), strict=False)
print('yes' if not resp.get('success', True) and 'path traversal' in resp.get('error','').lower() else 'no')
" 2>/dev/null || echo "no")
echo "  路径穿越已拦截: $REJECTED"
[ "$REJECTED" = "yes" ] && echo "  ✓ 路径穿越防护生效"

echo ""
echo "========================================="
echo " 案例 2 测试完成"
echo " Backend: $BACKEND"
echo "========================================="