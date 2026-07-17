# Skit SaaS 本地开发测试环境实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ] syntax for tracking.

**Goal:** 为后端、前端和 App 建立可重复的本地依赖栈、统一验证入口和 push 前自动门禁。

**Architecture:** 后端新增只承载 MySQL/Redis 的本地 Compose，Spring Boot 继续使用已有 local profile；三个独立 Git 仓库各自提供 scripts/verify-local.sh 与 .githooks/pre-push，通过仓库级 core.hooksPath 安装。验证命令与现有 GitHub Actions 对齐，生产 Compose、生产 workflow 和业务逻辑不改动。

**Tech Stack:** Docker Compose、MySQL 8、Redis 6.2、Spring Boot/Maven、pnpm/Vite/Vitest、Node.js、Gradle Android debug runtime、POSIX shell。

## Global Constraints

- 只使用 skit-saas-local 项目和本地 Docker volumes；普通 down 不删除 volume。
- 本地数据库使用 ruoyi-vue-pro-jdk8，MySQL 绑定 127.0.0.1:3306，Redis 绑定 127.0.0.1:6379。
- 凭据只放在未跟踪的 skit-saas-backend/deploy/local.env，仓库只提交 local.env.example。
- pre-push 不设置跳过变量；缺少依赖必须以明确错误退出。
- 不修改生产 Compose、生产 GitHub Actions、数据库迁移逻辑或现有未跟踪文件。
- 每个脚本使用 set -euo pipefail，不打印密码或密钥。

---

### Task 1: 后端本地 MySQL/Redis 栈

**Files:**
- Create: skit-saas-backend/deploy/docker-compose.local.yml
- Create: skit-saas-backend/deploy/local.env.example
- Modify: skit-saas-backend/.gitignore
- Create: skit-saas-backend/scripts/local-stack.sh
- Create: skit-saas-backend/scripts/run-local.sh
- Test: skit-saas-backend/scripts/test-local-stack-contract.sh

**Interfaces:**
- local-stack.sh supports up, down, status and reset.
- run-local.sh starts yudao-server with local profile.
- test-local-stack-contract.sh checks service, port, volume and SQL mount contracts without starting production services.

- [ ] **Step 1: Write the failing contract test**

The test must require name skit-saas-local, mysql:8, redis:6.2-alpine, loopback-only port mappings, isolated volumes, and read-only mounts for ruoyi-vue-pro.sql, skit-saas.sql and quartz.sql. Run the command bash scripts/test-local-stack-contract.sh; expected failure is local compose is missing.

- [ ] **Step 2: Add local env and Compose definition**

local.env.example contains development values: database name ruoyi-vue-pro-jdk8, root password skit-local-root, ports 3306/6379, local MySQL passwords, and development-only encryption/session keys. Compose declares MySQL 8 and Redis 6.2 healthchecks, local-only ports, volumes skit-saas-local-mysql and skit-saas-local-redis, and the three existing SQL files as read-only init mounts. It contains no backend or frontend production service.

- [ ] **Step 3: Add lifecycle commands**

local-stack.sh creates deploy/local.env from the example when absent, verifies Docker Compose, starts services and waits up to 120 seconds for health. down stops but preserves volumes. status prints compose status. reset refuses unless SKIT_CONFIRM_RESET=1 and is the only path that calls down -v. No command prints env contents.

- [ ] **Step 4: Add Spring Boot local runner**

run-local.sh calls local-stack.sh up, sources deploy/local.env, exports the local MySQL and encryption variables, then executes:

~~~bash
mvn -pl yudao-server -am spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.jvmArguments="-Dfile.encoding=UTF-8"
~~~

- [ ] **Step 5: Verify and commit**

Run bash scripts/test-local-stack-contract.sh and docker compose -f deploy/docker-compose.local.yml --env-file deploy/local.env.example config. Expected: contract passes, rendered config exits 0, and no production service is present. Commit with message chore(skit): add local mysql redis development stack.

### Task 2: 后端验证入口与 pre-push

**Files:**
- Create: skit-saas-backend/scripts/verify-local.sh
- Create: skit-saas-backend/.githooks/pre-push
- Create: skit-saas-backend/scripts/install-local-hooks.sh
- Modify: skit-saas-backend/deploy/README.md

**Interfaces:**
- verify-local.sh is the only backend command called by the hook.
- pre-push reads pushed refs and skips only deleted or documentation/CI-only pushes.
- install-local-hooks.sh sets git config core.hooksPath .githooks.

- [ ] **Step 1: Add CI-equivalent backend verifier**

The script checks docker, mvn and java, then runs the five existing deployment contract scripts, the bounded Skit build-material/tenant/security Maven suite already used for local feature verification, and the exact MySQL 8 Failsafe migration command from backend CI. The broader backend lifecycle matrix remains in GitHub Actions. It may honor SKIP_INTEGRATION=1 for manual quick iteration, but pre-push never sets it.

- [ ] **Step 2: Add ref-aware hook**

Read local_ref, local_sha, remote_ref and remote_sha from stdin. For a new branch diff the parent of local_sha to local_sha; otherwise diff remote_sha to local_sha. Ignore deleted refs. Skip only when every changed path is docs, Markdown, or CI metadata; all source, config, SQL and build changes execute verify-local.sh. The hook must never stage, commit, reset, or alter files.

- [ ] **Step 3: Add installer and documentation**

The installer verifies the repo root, sets core.hooksPath to .githooks and prints the result. README documents copying local.env, starting the stack, running the backend, installing hooks, running verification, preserving volumes with down, and the explicit reset confirmation.

- [ ] **Step 4: Verify and commit**

Run bash scripts/verify-local.sh, git diff --check, and the installer. Expected: security contracts, focused tests and MySQL integration exit 0; git config --get core.hooksPath prints .githooks. Commit with message chore(skit): gate backend pushes with local verification.

### Task 3: 前端验证入口与 pre-push

**Files:**
- Create: skit-saas-frontend/scripts/verify-local.sh
- Create: skit-saas-frontend/.githooks/pre-push
- Create: skit-saas-frontend/scripts/install-local-hooks.sh
- Modify: skit-saas-frontend/README.md

- [ ] **Step 1: Add verifier**

Check Node and pnpm, run pnpm install --frozen-lockfile, pnpm test:unit, pnpm ts:check, pnpm lint, and pnpm build:prod in that order. The script only checks formatting and never writes formatting changes.

- [ ] **Step 2: Add the same ref-aware hook and installer**

Reuse the hook policy from Task 2, scoped to the frontend repo. README states that .env.local targets localhost:48080, the backend must run locally, and pnpm run dev starts Vite.

- [ ] **Step 3: Verify and commit**

Run bash scripts/verify-local.sh and expect unit tests, typecheck, lint and production build to exit 0. Commit with message chore(skit): gate frontend pushes with local verification.

### Task 4: App 验证入口与 pre-push

**Files:**
- Create: skit-saas-app/scripts/verify-local.sh
- Create: skit-saas-app/.githooks/pre-push
- Create: skit-saas-app/scripts/install-local-hooks.sh
- Modify: skit-saas-app/docs/android-ad-mediation.md

- [ ] **Step 1: Add verifier**

Check Node, npm, Java and Gradle; parse package.json, manifest.json and pages.json; run npm ci --ignore-scripts, npm run check:identity, npm run test:app, and gradle --no-daemon -p android-djx-runtime :app:testDebugUnitTest :app:assembleDebug. Verify the debug APK contains assets/www/index.html and assets/www/djx-runtime.js. Do not create a production keystore or upload artifacts.

- [ ] **Step 2: Add hook, installer and documentation**

Apply the same ref-aware hook policy to App source/build/config changes. Document macOS Android SDK/Gradle requirements and that the result is a local debug APK only.

- [ ] **Step 3: Verify and commit**

Run bash scripts/verify-local.sh; all metadata, identity, ad-session, Android unit and asset checks must exit 0. Commit with message chore(skit): gate app pushes with local verification.

### Task 5: 安装 hooks 与最终验收

**Files:**
- Modify only Git config through installers; never commit .git/config.
- Preserve all pre-existing untracked files.

- [ ] **Step 1: Install hooks**

Run each repository's scripts/install-local-hooks.sh and confirm core.hooksPath is .githooks in all three repositories.

- [ ] **Step 2: Start and inspect dependencies**

Run backend local-stack.sh up twice and status. Expected: MySQL and Redis healthy, second up reuses volumes, and no production container is created.

- [ ] **Step 3: Run all three verifiers**

Run backend, frontend and App verify-local.sh with no skip variables. Record exit code 0 for each; install missing local dependencies and rerun instead of bypassing checks.

- [ ] **Step 4: Exercise safety boundary**

Run reset without SKIT_CONFIRM_RESET=1 and verify refusal. Run down and verify local volumes remain. Do not remove volumes during acceptance.

- [ ] **Step 5: Final hygiene and push**

Run git status --short --branch and git diff --check in all three repos. Confirm only new tracked files are committed and known untracked files remain untouched. Push each repository only after its local verifier exits 0.
