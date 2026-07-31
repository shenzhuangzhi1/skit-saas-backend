# Skit SaaS Backend

Skit 的后端是一个以 Spring Boot 2.7 和 Java 8 为基线的模块化单体，负责管理端、App 与广告回调共用的服务端能力。当前生产装配只包含 `system`、`infra` 和 `skit` 三个业务模块；仓库中的目录名不能作为运行中能力的证明，实际边界以根 POM、`yudao-server/pom.xml`、测试和部署配置为准。

## 当前模块边界

| 路径 | 职责 |
| --- | --- |
| `yudao-server` | Spring Boot 启动与生产 JAR 装配 |
| `yudao-module-system` | 管理员认证、权限、租户和系统基础能力 |
| `yudao-module-infra` | 文件、配置、日志、任务和基础设施能力 |
| `yudao-module-skit` | 代理商、会员、邀请、短剧配置、App 发布、广告会话与回调、收益、分佣和对账 |
| `yudao-framework` | Web、安全、租户、MyBatis、Redis、测试等共享框架 |
| `yudao-dependencies` | Maven 依赖版本管理 |

这是一个模块化单体，不需要为本地开发分别启动多个业务服务。所有租户、广告和收益写入仍由同一个后端进程处理；若未来拆分服务，应先按现有领域边界解耦数据所有权和可靠消息，再改变部署形态。

## 本地启动

准备 Docker Desktop、Docker Compose v2、Maven 3.9+ 和可用 JDK。正式构建与 CI 使用 JDK 8。

```bash
cp deploy/local.env.example deploy/local.env
./scripts/local-stack.sh up
./scripts/run-local.sh
```

`local.env` 只用于本机，不能提交。脚本会启动隔离的 MySQL 8 与 Redis 6.2，然后在 `48080` 端口运行后端。查看或停止依赖：

```bash
./scripts/local-stack.sh status
./scripts/local-stack.sh down
```

`down` 会保留数据。只有明确设置 `SKIT_CONFIRM_RESET=1` 并执行 `./scripts/local-stack.sh reset` 才会删除本地 volume。

## 构建与验证

正式的 Java 8 全量验证使用容器，避免本机 JDK 漂移：

```bash
docker run --rm \
  -e TESTCONTAINERS_RYUK_DISABLED=true \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$PWD:/workspace" \
  -v "$HOME/.m2:/root/.m2" \
  -w /workspace \
  maven:3.9.9-eclipse-temurin-8 \
  mvn -T1C -pl yudao-server -am clean verify
```

日常提交前可以执行仓库验证入口：

```bash
./scripts/install-local-hooks.sh
./scripts/verify-local.sh
```

后端体量与装配边界可重复生成和校验：

```bash
./scripts/backend-footprint-inventory.sh HEAD
./scripts/test-backend-footprint-contract.sh
```

体量脚本接受任意 Git commit、tag 或 branch，输出 tracked 文件数、字节数、活跃模块树、数据库 SQL、文档、部署文件和 Maven 装配清单。瘦身前基线保存在 `docs/backend-footprint-baseline.tsv`。

生产 JAR 构建后，可生成并核对其实际携带的运行时依赖：

```bash
./scripts/backend-packaged-dependencies.sh
./scripts/test-backend-packaged-dependencies.sh
```

清单保存在 `docs/backend-packaged-dependencies.txt`，CI 会在镜像构建前检查清单漂移、三个产品模块是否齐全，以及已删除模块是否意外回到产物中。

## 数据库规则

全新 MySQL volume 按顺序加载：

1. `sql/mysql/ruoyi-vue-pro.sql`
2. `sql/mysql/skit-saas.sql`
3. `sql/mysql/quartz.sql`

已有数据库由 Skit 的校验和保护迁移升级。不要向已有库重新导入完整初始化 SQL，也不要为了启动绕过 schema 校验。仓库保留 DB2、DM、HighGo、KingbaseES、MySQL、OpenGauss、Oracle、PostgreSQL 和 SQL Server 等数据库方言资产；本次代码瘦身不改变它们。

## 广告与租户安全边界

- App 广告会话、服务端奖励回调、权益发放、收益事件、分佣与对账是同一条授权链，改动后必须按完整闭环验证，不能用单个 HTTP 200 或单元测试代替端到端证据。
- 回调路由密钥、广告平台密钥和加密材料都是秘密，禁止写入日志、工单、README 或提交记录。
- 管理端与 App 请求必须保持租户绑定；代理租户不能通过请求参数、Header 或回调内容越权访问其他租户。
- 回调签名、交易标识幂等、广告会话状态和权益范围不能在普通重构中放宽。
- 生产密钥缺失或格式错误时应启动失败，不能退回示例值或本地默认值。

## 部署与运维

- 生产拓扑、密钥、回滚和发布顺序见 [`deploy/README.md`](deploy/README.md)。
- 广告收益灰度、租户开通、观测和回滚见 [`docs/runbooks/ad-revenue-rollout.md`](docs/runbooks/ad-revenue-rollout.md)。
- 后端 GitHub Actions 使用 JDK 8 运行单元、安全和 MySQL 8 集成检查，随后构建不可变镜像。
- 生产数据库初始化 SQL 只用于新建 volume；日常发布通过应用迁移升级已有数据。

## 目录约束

以下路径属于当前产品核心，瘦身时不得作为“上游脚手架”误删：

- `yudao-module-system`
- `yudao-module-infra`
- `yudao-module-skit`
- `yudao-framework`
- `yudao-dependencies`
- `sql`
- `deploy`

新增业务模块前，应同时更新根 reactor、`yudao-server` 装配、体量合同、测试和部署说明。仅创建一个未接入 reactor 的目录不会让能力进入生产。

## 上游与许可证

本项目基于 [RuoYi-Vue-Pro](https://github.com/YunaiV/ruoyi-vue-pro) 演进，并保留其基础框架和历史版权归属。上游作者与社区的工作为 Skit 提供了系统、基础设施和通用框架基础。

代码继续遵循仓库根目录 [`LICENSE`](LICENSE) 中的 MIT License。保留上游版权声明和许可文本是再分发的必要条件。
