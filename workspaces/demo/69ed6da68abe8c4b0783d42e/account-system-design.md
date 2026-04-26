# 账号系统设计文档

## 1. 核心功能范围

| 功能模块 | 子功能 | 说明 |
|---------|--------|------|
| **注册** | 邮箱/手机号注册、用户名注册 | 支持基本注册方式 |
| **登录** | 密码登录、验证码登录 | 多种登录方式 |
| **密码管理** | 密码设置、修改、重置 | 含忘记密码流程 |
| **会话管理** | Token签发、刷新、注销 | JWT + Refresh Token |
| **权限控制** | 角色-权限模型（RBAC） | 细粒度接口权限 |
| **用户管理** | 信息查询、更新、注销账号 | 基础CRUD |
| **安全机制** | 密码加密、限流、防暴力破解 | 安全防护 |

---

## 2. 数据表结构设计

### 2.1 user（用户表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGINT UNSIGNED | PK, AUTO_INCREMENT | 用户ID |
| username | VARCHAR(64) | UNIQUE, NOT NULL | 用户名 |
| email | VARCHAR(255) | UNIQUE, NULL | 邮箱 |
| phone | VARCHAR(20) | UNIQUE, NULL | 手机号 |
| password_hash | VARCHAR(255) | NOT NULL | bcrypt密码哈希 |
| nickname | VARCHAR(64) | NULL | 昵称 |
| avatar_url | VARCHAR(512) | NULL | 头像URL |
| status | TINYINT | NOT NULL, DEFAULT 1 | 状态: 1=正常, 0=禁用, -1=已删除 |
| email_verified | TINYINT | NOT NULL, DEFAULT 0 | 邮箱是否已验证 |
| phone_verified | TINYINT | NOT NULL, DEFAULT 0 | 手机号是否已验证 |
| last_login_at | DATETIME | NULL | 最后登录时间 |
| created_at | DATETIME | NOT NULL, DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| updated_at | DATETIME | NOT NULL, DEFAULT CURRENT_TIMESTAMP ON UPDATE | 更新时间 |

```sql
CREATE TABLE `user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `username` VARCHAR(64) NOT NULL,
  `email` VARCHAR(255) DEFAULT NULL,
  `phone` VARCHAR(20) DEFAULT NULL,
  `password_hash` VARCHAR(255) NOT NULL,
  `nickname` VARCHAR(64) DEFAULT NULL,
  `avatar_url` VARCHAR(512) DEFAULT NULL,
  `status` TINYINT NOT NULL DEFAULT 1,
  `email_verified` TINYINT NOT NULL DEFAULT 0,
  `phone_verified` TINYINT NOT NULL DEFAULT 0,
  `last_login_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_email` (`email`),
  UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 2.2 role（角色表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGINT UNSIGNED | PK, AUTO_INCREMENT | 角色ID |
| name | VARCHAR(64) | UNIQUE, NOT NULL | 角色名称（如 admin, user） |
| description | VARCHAR(255) | NULL | 角色描述 |
| created_at | DATETIME | NOT NULL | 创建时间 |

### 2.3 permission（权限表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGINT UNSIGNED | PK, AUTO_INCREMENT | 权限ID |
| name | VARCHAR(128) | UNIQUE, NOT NULL | 权限标识（如 user:create） |
| description | VARCHAR(255) | NULL | 权限描述 |
| created_at | DATETIME | NOT NULL | 创建时间 |

### 2.4 user_role（用户-角色关联表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| user_id | BIGINT UNSIGNED | PK, FK -> user.id | 用户ID |
| role_id | BIGINT UNSIGNED | PK, FK -> role.id | 角色ID |

### 2.5 role_permission（角色-权限关联表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| role_id | BIGINT UNSIGNED | PK, FK -> role.id | 角色ID |
| permission_id | BIGINT UNSIGNED | PK, FK -> permission.id | 权限ID |

### 2.6 refresh_token（刷新令牌表）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGINT UNSIGNED | PK, AUTO_INCREMENT | ID |
| user_id | BIGINT UNSIGNED | FK, NOT NULL | 用户ID |
| token_hash | VARCHAR(255) | NOT NULL | 令牌哈希 |
| expires_at | DATETIME | NOT NULL | 过期时间 |
| revoked | TINYINT | NOT NULL, DEFAULT 0 | 是否已撤销 |
| created_at | DATETIME | NOT NULL | 创建时间 |

### 2.7 login_attempt（登录尝试记录 — 防暴力破解）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGINT UNSIGNED | PK, AUTO_INCREMENT | ID |
| identifier | VARCHAR(255) | NOT NULL | 用户名/IP/邮箱 |
| attempt_type | VARCHAR(32) | NOT NULL | login / reset_password |
| success | TINYINT | NOT NULL | 是否成功 |
| ip_address | VARCHAR(45) | NULL | 客户端IP |
| attempted_at | DATETIME | NOT NULL DEFAULT CURRENT_TIMESTAMP | 尝试时间 |

---

## 3. API 端点设计

### 3.1 注册与登录

| 方法 | 端点 | 说明 | 认证 |
|------|------|------|------|
| POST | /api/v1/auth/register | 用户注册 | 否 |
| POST | /api/v1/auth/login | 密码登录 | 否 |
| POST | /api/v1/auth/logout | 退出登录 | 是 |

**POST /api/v1/auth/register**
Request:
{
  "username": "john_doe",
  "email": "john@example.com",
  "password": "SecurePass123!",
  "nickname": "John"
}

Response 201:
{
  "code": 0,
  "message": "ok",
  "data": {
    "user_id": 1,
    "username": "john_doe",
    "email": "john@example.com"
  }
}

**POST /api/v1/auth/login**
Request:
{
  "identifier": "john_doe",
  "password": "SecurePass123!"
}

Response 200:
{
  "code": 0,
  "message": "ok",
  "data": {
    "access_token": "eyJhbGciOiJIUzI1NiIs...",
    "refresh_token": "dGhpcyBpcyBhIHJlZnJl...",
    "expires_in": 3600,
    "user": {
      "id": 1,
      "username": "john_doe",
      "nickname": "John",
      "avatar_url": null
    }
  }
}

### 3.2 令牌管理

| 方法 | 端点 | 说明 | 认证 |
|------|------|------|------|
| POST | /api/v1/auth/refresh | 刷新Access Token | 否（用Refresh Token） |
| POST | /api/v1/auth/revoke | 撤销Refresh Token | 是 |

### 3.3 密码管理

| 方法 | 端点 | 说明 | 认证 |
|------|------|------|------|
| PUT | /api/v1/auth/password | 修改密码 | 是 |
| POST | /api/v1/auth/password/reset | 请求密码重置 | 否 |
| POST | /api/v1/auth/password/reset/confirm | 确认密码重置 | 否 |

### 3.4 用户信息

| 方法 | 端点 | 说明 | 认证 |
|------|------|------|------|
| GET | /api/v1/users/me | 获取当前用户信息 | 是 |
| PUT | /api/v1/users/me | 更新个人信息 | 是 |
| DELETE | /api/v1/users/me | 注销账号 | 是 |
| GET | /api/v1/users/{id} | 获取指定用户信息（管理员） | 是（管理员） |

### 3.5 权限管理（管理员接口）

| 方法 | 端点 | 说明 | 认证 |
|------|------|------|------|
| POST | /api/v1/roles | 创建角色 | 是（管理员） |
| GET | /api/v1/roles | 角色列表 | 是（管理员） |
| PUT | /api/v1/roles/{id} | 更新角色 | 是（管理员） |
| DELETE | /api/v1/roles/{id} | 删除角色 | 是（管理员） |
| POST | /api/v1/users/{id}/roles | 为用户分配角色 | 是（管理员） |
| GET | /api/v1/users/{id}/roles | 获取用户角色 | 是（管理员） |

---

## 4. 通用响应格式

成功:
{
  "code": 0,
  "message": "ok",
  "data": { ... }

失败:
{
  "code": 40001,
  "message": "用户名已存在",
  "data": null
}

错误码定义:
| 错误码 | 说明 |
|--------|------|
| 0 | 成功 |
| 40001 | 参数错误 |
| 40002 | 用户名已存在 |
| 40003 | 邮箱已注册 |
| 40004 | 验证码错误/过期 |
| 40101 | 未登录/Token无效 |
| 40102 | Token已过期 |
| 40301 | 无权限 |
| 40401 | 用户不存在 |
| 42901 | 请求过于频繁 |
| 50001 | 服务器内部错误 |

---

## 5. 安全策略

| 策略 | 说明 |
|------|------|
| 密码加密 | 使用 bcrypt（cost=12）存储密码哈希 |
| JWT签名 | 使用 RS256 或 HS256 算法，Access Token 有效期 1 小时 |
| Refresh Token | 随机字符串，SHA256哈希存储，有效期 7 天，支持撤销 |
| 登录限流 | 同一IP/用户 5分钟内失败5次则锁定15分钟 |
| 密码强度 | 至少8位，含大小写字母、数字、特殊字符 |
| HTTPS | 全站强制HTTPS |

---

## 6. 技术选型建议

| 层级 | 技术 |
|------|------|
| 语言 | Go / Java / Node.js |
| 框架 | Gin / Spring Boot / Express |
| 数据库 | MySQL 8.0+ / PostgreSQL |
| 缓存 | Redis（会话、限流计数） |
| ORM | GORM / MyBatis-Plus / Prisma |
| 认证 | JWT (golang-jwt / jjwt / jsonwebtoken) |

---

## 7. 后续实施步骤

1. 环境搭建 — 初始化项目结构、数据库、依赖
2. 数据模型实现 — 编写ORM模型与数据库迁移脚本
3. 注册/登录接口 — 实现核心认证流程
4. JWT中间件 — 实现Token验证与鉴权
5. 密码管理 — 修改/重置密码
6. RBAC权限 — 角色与权限管理
7. 安全加固 — 限流、日志、错误处理
8. 测试与文档 — 单元测试、集成测试、API文档
