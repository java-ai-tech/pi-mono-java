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
