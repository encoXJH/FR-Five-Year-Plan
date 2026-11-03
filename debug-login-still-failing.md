# 调试记录：线上登录仍失败

- sessionId: login-still-failing
- PROJECT_ROOT: C:\Users\admin\five\FR-Five-Year-Plan
- DEBUG_EVIDENCE_DIR: C:\Users\admin\AppData\Local\Temp\trae-debug\fr-five-year-plan\login-still-failing
- NDJSON: C:\Users\admin\AppData\Local\Temp\trae-debug\fr-five-year-plan\login-still-failing\trae-debug-log-login-still-failing.ndjson
- ENV: C:\Users\admin\AppData\Local\Temp\trae-debug\fr-five-year-plan\login-still-failing\login-still-failing.env
- progress_status: collecting
- 目标页面: http://172.21.131.17/login

## 假设与观察点

| ID | 假设 | 观察点 | 状态 |
| --- | --- | --- | --- |
| H1 | MySQL 未完整部署或 Flyway 迁移失败 | 数据库连接、schema_history、应用启动日志 | REJECTED |
| H2 | admin 用户缺失、禁用或密码哈希不匹配 | sys_user 记录、BCrypt 校验、登录响应 | REJECTED |
| H3 | 浏览器点击未进入登录提交或未发出 POST | 浏览器网络、控制台、Nginx access log、插桩提交入口 | CONFIRMED |
| H4 | 登录后 /auth/me、字典或路由跳转失败 | Token、/auth/me、/dicts/enums、页面 URL | INCONCLUSIVE |

## 证据

- HTTP Debug Server: http://127.0.0.1:7777/event
- NDJSON: C:\Users\admin\AppData\Local\Temp\trae-debug\fr-five-year-plan\login-still-failing\trae-debug-log-login-still-failing.ndjson
- MySQL 8.0.42；metrics_db 33 张表；Flyway V1-V15 全部 success=1，失败数 0。
- sys_user 共 15 个有效用户；admin id=201、is_enabled=1、未锁定、deleted=0、60 字符 BCrypt，user_role 为 ADMIN。
- 浏览器 viewId 648279d6-f94c-4ec8-92a1-c7b45c8a1db9：用户名长度 5、密码长度 11、无校验错误、按钮 type=submit 且未禁用。
- pre-fix 点击按钮后仍停留 /login，Network requests: (none)，Console messages: (none)。

## 插桩设计

- H3 submit-entry：记录 handleSubmit 是否进入、formRef 是否存在、用户名和密码仅记录长度。
- H3 validation-result：记录 validate 的结果。
- H3 login-call / login-result：记录是否调用 userStore.login 及其成功或失败结果，不记录凭据和 Token。

## 修复与验证

尚未修改业务代码、配置或数据；下一步仅加入 H3 临时诊断日志并构建部署复现。
