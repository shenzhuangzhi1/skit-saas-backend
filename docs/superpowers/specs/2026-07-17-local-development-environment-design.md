# Skit SaaS 本地开发测试环境设计

## 目标

建立一套可重复的本地开发与提交前验证流程，覆盖 SaaS 后端、管理端前端和 App：

1. 本地 Docker 提供后端需要的 MySQL 8 与 Redis 6.2 基础设施。
2. 后端以现有 `local` profile 运行，前端以现有 `.env.local` 运行，二者通过本机端口联调。
3. 每个独立 Git 仓库提供一条明确的本地验证命令，并提供可安装、可审查的 `pre-push` hook。
4. hook 按仓库执行与 CI 对齐的验证；失败时阻断 push，成功时不修改工作区。
5. 不删除、不重置开发者已有数据库数据；初始化 SQL 只在新建 Docker volume 时执行。

## 现有能力与边界

- 后端已有 Maven 聚焦测试、MySQL 8 Testcontainers 集成检查和生产 Compose；本方案复用这些命令，不重新实现业务测试。
- 前端已有 `pnpm test:unit`、`pnpm ts:check`、`pnpm lint` 和 `.env.local`；本方案只提供统一入口和 hook。
- App 已有身份边界测试、广告会话测试和 Gradle Debug runtime 构建；本方案只增加本地入口，不改变 APK 生产流水线。
- 生产部署 Compose、GitHub Actions、数据库迁移和现有加密/租户逻辑不改动。
- 现有未跟踪文件不纳入本次提交。

## 方案

### 本地基础设施

后端新增 `deploy/docker-compose.local.yml`，只启动 MySQL 与 Redis：

- MySQL 8，默认绑定 `127.0.0.1:3306`，数据库 `ruoyi-vue-pro-jdk8`，root 密码由未提交的 `.env.local` 注入。
- Redis 6.2，默认绑定 `127.0.0.1:6379`。
- 挂载现有 `sql/mysql/ruoyi-vue-pro.sql`、`skit-saas.sql`、`quartz.sql`，只在全新 `skit-saas-local-mysql` volume 初始化。
- 项目名固定为 `skit-saas-local`，与生产 Compose 容器和 volume 隔离。

后端新增 `deploy/local.env.example` 和 `scripts/local-stack.sh`：

- `./scripts/local-stack.sh up`：创建本地 env（若不存在）、启动 MySQL/Redis 并等待健康状态。
- `./scripts/local-stack.sh down`：停止容器但保留 volume。
- `./scripts/local-stack.sh status`：显示服务健康状态。
- `./scripts/local-stack.sh reset`：明确要求 `SKIT_CONFIRM_RESET=1` 后才删除本地 volume，默认拒绝。

后端新增 `scripts/run-local.sh`，先调用 `local-stack.sh up`，再以 `SPRING_PROFILES_ACTIVE=local`、`LOCAL_MYSQL_MASTER_PASSWORD`、`LOCAL_MYSQL_SLAVE_PASSWORD`、`SKIT_AD_ENCRYPTION_KEY` 启动 `yudao-server`；凭据只从未跟踪的 `deploy/local.env` 读取。

### 仓库验证入口

每个仓库新增 `scripts/verify-local.sh`，退出码为验证结果，默认不改变源文件：

- 后端：部署安全契约、聚焦单元测试、MySQL 8 Testcontainers 集成迁移检查。
- 前端：单元测试、类型检查、ESLint/Stylelint/Prettier、生产构建。
- App：元数据 JSON 校验、身份边界测试、广告会话测试、Gradle Debug 单测与 APK 构建并检查关键 assets。

验证脚本支持 `SKIP_INTEGRATION=1` 仅用于快速本地迭代；pre-push 不设置该变量，确保 push 前与 CI 同等覆盖。

### Git hook

各仓库新增可跟踪的 `.githooks/pre-push` 和 `scripts/install-local-hooks.sh`：

- 安装脚本执行 `git config core.hooksPath .githooks`，不改全局 Git 配置。
- hook 检查当前仓库待推送提交是否包含源代码/构建配置变更；包含时调用 `scripts/verify-local.sh`，仅文档变更时快速通过。
- hook 不提交、不修改、不清库；缺少 Docker/Node/Java/Gradle 等依赖时以明确错误退出并给出安装提示。
- 三个仓库独立安装，避免工作区没有顶层 Git 仓库导致 hook 失效。

### 文档

在后端 `deploy/README.md` 增加本地启动、停止、日志、数据保留、端口冲突和首次初始化说明；前端 README 和 App README 分别增加验证命令及 hook 安装命令，并注明本地 Mac APK 构建仍不由 SaaS 服务器触发。

## 数据流

```text
local-stack.sh up
        |
        +--> MySQL 8 (127.0.0.1:3306, ruoyi-vue-pro-jdk8)
        +--> Redis 6.2 (127.0.0.1:6379)
                         |
run-local.sh ------------+--> Spring Boot local (127.0.0.1:48080)
                                      |
frontend .env.local ------------------+--> Vite dev server
```

## 验证与验收

1. 新机器按 README 操作可以启动 MySQL/Redis，重复执行不会重建 volume。
2. `scripts/verify-local.sh` 在三个仓库均能返回成功；其中后端 MySQL 集成检查真实启动 MySQL 8 容器。
3. 人为制造格式或测试失败时，pre-push 返回非零并阻断推送；恢复后 hook 通过。
4. 生产 Compose、GitHub Actions 和现有未跟踪文件不发生变更。
5. 所有新增脚本通过 shellcheck 能力范围内的语法检查，敏感值扫描不出现真实凭据。

