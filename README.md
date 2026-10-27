# 五年计划指标管理平台

福州软件职业技术学院 · 五年计划指标管理平台。技术栈：Spring Boot 3 + MyBatis-Plus + MySQL 8 / Vue 3 + TypeScript + Element Plus。

## 环境要求

- JDK 17、Node.js 20+（本工程自带 Maven Wrapper，无需安装 Maven）
- MySQL 8.0：任选其一
  - **Docker**：`docker compose up -d`（推荐，自动 utf8mb4/+08:00）
  - **本地 MySQL 8**：手工建库 `CREATE DATABASE metrics_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`

## 启动（开发）

```bash
# 1. 配置环境变量
cp .env.example .env   # 按需修改数据库密码、JWT 密钥等

# 2. 后端（首次启动 Flyway 自动建表 + 写入种子数据）
cd backend
./mvnw spring-boot:run
# Swagger UI: http://localhost:8080/swagger-ui.html（生产环境自动关闭）

# 3. 前端（另开终端，代理到 8080）
cd frontend
npm install
npm run dev   # http://localhost:5173
```

## 启动（生产，一条命令）

```bash
cp .env.example .env   # 填写生产数据库密码、JWT 密钥、飞书配置等
docker compose -f docker-compose.prod.yml up -d --build
```

包含 MySQL 8 + 后端 + 前端（Nginx 托管并反代 /api）。HTTPS 配置见 `deploy/nginx-https.conf.example`，数据库每日备份脚本见 `deploy/backup-mysql.sh`，完整步骤与飞书后台配置见 `docs/部署手册.md`。

## 开发测试账号（仅开发环境，密码统一 Test@123456）

| 账号 | 角色 |
| --- | --- |
| admin | 平台管理员 |
| leader | 校领导 |
| owner1 / owner2 | 校级项目负责人 |
| c01 – c10 / s01 | 各执行单元负责人 |

## 测试与构建

```bash
cd backend && ./mvnw test                  # 后端单元测试
cd frontend && npm run build               # 前端生产构建
cd frontend && npm run test                # 前端单元测试
cd frontend && npm run smoke               # 端到端冒烟（需后端 8080 + 前端 5173 已启动，Playwright）
```

冒烟脚本 `frontend/tests/smoke.mjs` 跑通主流程：登录 → 录入指标 → 分解下发 → 填报 → 审核 → 调整审批 → 大屏。

## 飞书集成（可选）

- 未配置飞书时，登录页自动隐藏飞书入口，消息推送置 SKIPPED，不影响账号密码登录。
- 配置项（全部环境变量，见 .env.example）：`FEISHU_APP_ID` / `FEISHU_APP_SECRET` / `FEISHU_REDIRECT_URI` / `FEISHU_ENCRYPT_KEY` / `FEISHU_VERIFICATION_TOKEN` / `FEISHU_APP_BASE_URL`。
- 飞书后台需要申请哪些权限、配置哪些回调地址：见 `docs/部署手册.md`；也可在系统管理 → 飞书集成 Tab 直接复制。

## 目录说明

- `docs/` 需求文档、API 约定、UI 规范、数据库设计、部署手册、安全自查清单、进度与决策记录
- `backend/` Spring Boot 工程（包名 edu.fzrj.metrics）
- `frontend/` Vue 3 工程
- `deploy/` HTTPS 示例与数据库备份脚本
- `db/demo/` 演示数据与验证脚本
