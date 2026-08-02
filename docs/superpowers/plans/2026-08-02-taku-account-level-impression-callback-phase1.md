# Taku 账号级展示收益回调阶段 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不触及归属、金额、报表或收益发布的前提下，为每个 Taku 外部账号交付一个长期稳定、可一次签发、可安全持久化的账号级展示回调地址。

**Architecture:** 新增全局 provider connection、callback route、hash-first registry、Inbox 和 Attempt；账号级 `/impression` 只在短 MySQL 事务中完成一次 registry 查找、边界检查、AEAD 原文封装与 Inbox/Attempt 持久化，再返回 200。既有 tenant callback key、奖励验签、会话、解锁与 tenant impression 表继续使用原路径；registry 以可重入双写、回填、校验、shadow read、切读六步接入，防止任何历史 Key 不可路由。

**Tech Stack:** Java 8, Spring MVC/Transactions, MyBatis-Plus, MySQL 8, Redis 限流, JUnit 5, Testcontainers MySQL, Nginx/Docker Compose, Maven/Failsafe。

## Global Constraints

- 范围只包括阶段 1 capture-only：稳定 provider connection/route、global registry、tenant Key 双写与回填、全局 Inbox/Attempt、宽容有界 raw query 捕获、AEAD、平台管理员操作、容量保护、schema/SQL/IT、CI、部署和生产验收。
- 归属绑定、展示上下文、eCPM 计算、金额、tenant projection、收益事件、报表拉取、对账、佣金和产品报表是后续阶段；本计划的 worker 不得写这些表或调用这些服务。
- 固定公网形状是 `GET /app-api/skit/ad-callback/taku/{connectionKey}/impression`；有效且在边界内的请求先完成数据库提交再返回 HTTP 200，内部入口 p99 目标不高于 250ms，事务超时必须明显短于 2 秒。
- 无效/阻断 Key、错误方法、错误路径或越过硬边界统一返回 602；仅保存不含 Key 和参数值的低基数指标，绝不保存 raw query 或 Attempt。
- 首版硬边界固定为 raw query 不超过 32 KiB、参数最多 64 个、percent-decode 后参数名为 `[A-Za-z0-9_.-]{1,64}`、单值不超过 24 KiB；总大小限制优先，Nginx 与应用必须使用相同限制。
- 新 Key 格式固定为 `acct_` 加 38 个 Base64URL 字符，总长 43；随机部分来自恰好 228 CSPRNG bits 的每 6-bit 映射；前缀只是人工识别，数据库路由只由 SHA-256 `key_hash` 决定。
- registry 的唯一查找和端点分派是确定性的：`PROVIDER_CALLBACK_ROUTE + impression` 进入全局 capture，`PROVIDER_CALLBACK_ROUTE + reward` 固定 602，`TENANT_CALLBACK_KEY + reward` 保持现有奖励链路，`TENANT_CALLBACK_KEY + impression` 保持迁移期既有兼容路径。
- provider connection 使用 `TAKU`、`SHARED_MASTER|TENANT_OWNED` 和 `CONFIGURING|ACTIVE|MIGRATING|BLOCKED|RETIRED`；阶段 1 仅允许创建/查看 `SHARED_MASTER`，`BLOCKED` 仍占共享唯一槽位。
- route 使用 `DRAFT|ISSUED|SUBMITTED|ACTIVE|BLOCKED|ABANDONED|RETIRED`、`PRIMARY_ACCEPTING|MIGRATION_TARGET|INACTIVE` 与不可变用途 `GATE_TEST|PRODUCTION`；阶段 1 API 允许 DRAFT、一次 ISSUED、确认从未共享后的 ISSUED -> ABANDONED、mark SUBMITTED 与 BLOCKED。`GATE_TEST` 永远不能 SUBMITTED/ACTIVE，`PRODUCTION` 签发必须先通过生产门禁；SUBMITTED 后禁止本地轮换、删除、明文重显或回到 DRAFT。
- provider key、完整 URL、query 值和设备标识不得出现在源码示例、数据库普通列、DTO `toString()`、异常、MDC、APM body、指标标签、Nginx access log、应用 access log、审计命令正文或常规 GET API；签发响应仅一次、`Cache-Control: no-store`、关闭 response-body log。
- 原始 query 只在边界内存为 AEAD envelope；每条 Attempt 的 AAD 必须绑定 provider connection、Attempt correlation ID 与 wire hash，使用独立 purpose/key id、唯一 96-bit nonce；默认 7 天、配置上限 30 天，密文到期前不得由普通 API 读取。
- `skit_provider_impression_inbox` 的唯一键是非空 `(provider_connection_id, dedupe_scheme, dedupe_key_hash)`；有效官方键使用严格 percent-decoded `req_id` 与规范化 `adsource_id`，缺失或无效时使用 wire-hash fallback 并隔离；每次有效投递都追加一条 Attempt。
- 不改已经发布的 `SkitAdSchemaDdl` baseline DDL 或任何已安装 migration 的 manifest/checksum；新增独立 migration version `2026080201`，并在两份 canonical SQL 中表达最终 schema。
- 所有全局 Mapper/Service 明确 `@TenantIgnore`，只可由 callback capture、受租约约束的内部 retention worker、super_admin API 调用；普通 tenant API 不得借此读取全局数据。
- 真实生产 Key 只能在全部生产门禁已留下证据后签发；当前单机 Compose 拓扑是交付门禁失败，而不是允许绕过双入口、两实例、MySQL HA、Redis 降级和故障切换演练的理由。

---

## File Structure

- `framework/schema/SkitSchemaInitializer.java`：注册不可变的 2026080201 加法 migration、预检、双写回填状态与 schema 验证；保留已发布 migration 的 checksum。
- `framework/schema/SkitProviderImpressionPhase1Schema.java`：只包含阶段 1 七张全局表、索引、CHECK、generated unique slot 和回填/验证 SQL fragments，避免把新的 DDL 追加到已发布 `SkitAdSchemaDdl`；除五张业务表外，还包含可重入 registry 迁移状态与平台命令审计表。
- `dal/dataobject/provider/*` 与 `dal/mysql/provider/*`：provider connection、route registry、callback route、Inbox、Attempt 的全局持久化模型与锁定查询。
- `service/ad/callback/SkitCallbackRouteRegistryService.java`：hash-first route lookup 及 tenant callback key 双写、回填、shadow comparison、切读的唯一入口。
- `service/provider/SkitProviderConnectionService.java`：共享连接、DRAFT route、一次签发、提交、阻断和健康只读状态机。
- `service/ad/callback/SkitProviderImpressionCaptureService.java`：有界 wire parser、幂等 key/hash、AEAD 和短事务 capture；绝不归属或计算金额。
- `controller/admin/provider/*`：super_admin 平台 API 和无敏感回显的 VO；`controller/app/ad/SkitTakuCallbackController.java` 只将 provider registry route 分派到 capture 服务。
- `framework/web/*`、`service/ad/callback/*RateLimiter*`：最前置日志清洗、602、容量保护与低基数 observation。
- `deploy/*`、`.github/workflows/cicd.yml`：Nginx size/timeout/log 配置、生产 topology gate 与 CI 的 unit/IT/contract/compose gates。

### Task 1: 建立独立的阶段 1 schema migration 与 canonical SQL

**Files:**
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/schema/SkitProviderImpressionPhase1Schema.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/schema/SkitSchemaInitializer.java`
- Modify: `sql/mysql/skit-saas.sql`
- Modify: `sql/mysql/ruoyi-vue-pro.sql`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/framework/schema/SkitProviderImpressionPhase1SchemaContractTest.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/framework/schema/SkitProviderImpressionPhase1MySqlIT.java`

**Interfaces:**
- Consumes: `SkitSchemaInitializer.migrations()` and its migration checksum protocol; existing `skit_ad_callback_key(callback_key_hash, tenant_id, ad_account_id, key_version, active, accept_until)`.
- Produces: migration `2026080201`, `SkitProviderImpressionPhase1Schema.steps()`, and these global tables: `skit_ad_provider_connection`, `skit_ad_provider_callback_route`, `skit_ad_callback_route_registry`, `skit_ad_callback_route_registry_migration`, `skit_provider_impression_inbox`, `skit_provider_callback_attempt`, `skit_platform_provider_command_audit`.

- [ ] **Step 1: Write the failing schema contract and MySQL migration tests**

```java
@Test
void providerPhase1MigrationIsAdditiveAndKeepsPublishedBaselineChecksum() {
    assertThat(SkitSchemaInitializer.migration(2026080201).getDescription())
            .isEqualTo("add account-level Taku impression capture");
    assertThat(SkitSchemaInitializer.migration(2026073001).getChecksum())
            .isEqualTo(PUBLISHED_PANGLE_MIGRATION_CHECKSUM);
    assertThat(SkitAdSchemaDdl.class.getDeclaredMethods()).noneMatch(method ->
            method.getName().contains("providerImpression"));
}

@Test
void migrationCreatesNonTenantInboxAndAttemptWithNonNullableDedupeKey() {
    runMigrationsThrough(2026080201);
    assertTable("skit_provider_impression_inbox");
    assertUniqueIndex("skit_provider_impression_inbox", "uk_provider_impression_inbox_dedupe",
            "provider_connection_id,dedupe_scheme,dedupe_key_hash");
    assertColumn("skit_provider_impression_inbox", "dedupe_key_hash", "binary(32)", false);
    assertTable("skit_provider_callback_attempt");
}
```

- [ ] **Step 2: Run the schema tests to verify they fail**

Run: `mvn -pl yudao-module-skit -Dtest=SkitProviderImpressionPhase1SchemaContractTest test`

Expected: FAIL because migration `2026080201` and the phase-1 schema class do not exist.

- [ ] **Step 3: Add the smallest independent migration, never editing `SkitAdSchemaDdl`**

```java
private static final int PROVIDER_IMPRESSION_PHASE_1_MIGRATION_VERSION = 2026080201;

result.add(migrationFromSteps(PROVIDER_IMPRESSION_PHASE_1_MIGRATION_VERSION,
        "add account-level Taku impression capture", SkitProviderImpressionPhase1Schema.steps()));
```

`SkitProviderImpressionPhase1Schema.steps()` must create all seven tables with InnoDB/utf8mb4 options and exact database invariants: one non-terminal `TAKU + SHARED_MASTER` generated unique key; owner nullability CHECK; route/provider state and immutable `GATE_TEST|PRODUCTION` purpose CHECKs; route slot generated unique keys; registry `key_hash` uniqueness and owner XOR; a singleton, monotonic registry-migration state/cursor row; Inbox non-null dedupe scheme/hash and canonical attempt FK; Attempt’s provider connection/inbox FK and 96-bit nonce (`binary(12)`); append-only platform audit with actor, original login tenant, action, resource IDs, reason, reauth time, safe hashes, trace and result but no command body/key/URL/query.  Write `CREATE TABLE IF NOT EXISTS` only for brand-new tables; use migration steps that verify each index/FK/CHECK/immutability trigger definition before registering 2026080201.  Do not add tenant ownership to any phase-1 global table; `original_login_tenant_id` is audit evidence only.

Add semantically identical DDL for the seven tables and indexes to both `sql/mysql/skit-saas.sql` and `sql/mysql/ruoyi-vue-pro.sql`, positioned with the existing Skit callback tables.  The two SQL files are baseline/canonical representations, not the production migration ledger.

- [ ] **Step 4: Run unit and real-MySQL schema verification**

Run: `mvn -pl yudao-module-skit -Dtest=SkitProviderImpressionPhase1SchemaContractTest test`

Expected: PASS; baseline checksum assertions and all seven table definitions match.

Run: `mvn -pl yudao-module-skit -Dit.test=SkitProviderImpressionPhase1MySqlIT failsafe:integration-test failsafe:verify`

Expected: PASS; a fresh MySQL database installs 2026080201 once, and re-run preserves the stored checksum and schema definitions.

- [ ] **Step 5: Commit the migration foundation**

```bash
git add yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/schema/SkitProviderImpressionPhase1Schema.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/schema/SkitSchemaInitializer.java sql/mysql/skit-saas.sql sql/mysql/ruoyi-vue-pro.sql yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/framework/schema/SkitProviderImpressionPhase1SchemaContractTest.java yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/framework/schema/SkitProviderImpressionPhase1MySqlIT.java
git commit -m "feat: add provider impression capture schema"
```

### Task 2: Add hash-first global route registry and tenant-key migration protocol

**Files:**
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/provider/SkitAdCallbackRouteRegistryDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/provider/SkitAdCallbackRouteRegistryMigrationDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/provider/SkitAdCallbackRouteRegistryMapper.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/provider/SkitAdCallbackRouteRegistryMigrationMapper.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitCallbackRouteRegistryService.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/SkitAdCredentialVersionService.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/SkitAdCredentialVersionServiceImpl.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitCallbackRoutingService.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitCallbackRouteRegistryServiceTest.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/integration/SkitCallbackRouteRegistryMySqlIT.java`

**Interfaces:**
- Consumes: `SkitAdCredentialVersionService.rotateCallbackKey(long,long,Duration)`, callback-key hashes, migration 2026080201.
- Produces: `RouteLookup lookup(byte[] keyHash, Instant receivedAt)`, `void registerTenantKey(TenantCallbackKeyRegistration registration)`, `RegistryMigrationReport backfillAndVerifyTenantKeys()`; `SkitCallbackRoutingService.resolveTenantReward(String rawKey, LocalDateTime receivedAt)` only accepts registry type `TENANT_CALLBACK_KEY`.

- [ ] **Step 1: Write failing route-registry tests**

```java
@Test
void issueRotateAndRevokeTenantKeyWritesRegistryInTheSameTransaction() {
    CallbackKeyIssue issued = credentialService.rotateCallbackKey(TENANT, ACCOUNT, Duration.ZERO);
    assertThat(registryMapper.selectByKeyHash(hash(issued.consumeCallbackKey())).getRouteType())
            .isEqualTo("TENANT_CALLBACK_KEY");
}

@Test
void hashFirstLookupNeverFallsBackFromProviderToTenant() {
    registry.insert(providerRegistryRow(HASH));
    assertThat(registryService.lookup(HASH, NOW).getRouteType()).isEqualTo(PROVIDER_CALLBACK_ROUTE);
    assertThatThrownBy(() -> routingService.resolveTenantReward(KEY, NOW))
            .isInstanceOf(CallbackRouteRejectedException.class);
}

@Test
void sixStepBackfillIsReentrantAndStopsBeforeCutoverOnAnySetDifference() {
    RegistryMigrationReport first = registryService.backfillAndVerifyTenantKeys();
    RegistryMigrationReport second = registryService.backfillAndVerifyTenantKeys();
    assertThat(second).isEqualTo(first);
    assertThat(registryService.enableHashFirstReads()).doesNotThrowAnyException();
}
```

- [ ] **Step 2: Run the route tests to verify they fail**

Run: `mvn -pl yudao-module-skit -Dtest=SkitCallbackRouteRegistryServiceTest test`

Expected: FAIL because neither global registry service nor tenant-key dual write exists.

- [ ] **Step 3: Implement registry-only identity resolution and the six explicit migration phases**

```java
public interface SkitCallbackRouteRegistryService {
    RouteLookup lookup(byte[] keyHash, LocalDateTime authoritativeReceivedAt);
    void registerTenantKey(TenantCallbackKeyRegistration registration);
    RegistryMigrationReport backfillAndVerifyTenantKeys();
    void enableHashFirstReads();
}

public final class RouteLookup {
    public enum RouteType { TENANT_CALLBACK_KEY, PROVIDER_CALLBACK_ROUTE }
    // provider route id or tenantId/adAccountId/keyVersion, never the raw key
}
```

In every tenant key create, rotate and revoke transaction, insert/update a registry row keyed by SHA-256 hash before committing the credential transaction; revoked hashes remain tombstones.  `backfillAndVerifyTenantKeys()` must run the exact ordered states `DUAL_WRITE -> BACKFILL -> VERIFY -> SHADOW_READ -> HASH_FIRST -> ENFORCED`, persist its cursor/progress in the migration’s own state table, compare owner tuple/row count/hash set/active state, and throw before `HASH_FIRST` if any count or set difference is non-zero.  During `SHADOW_READ`, resolve both paths and emit only a low-cardinality mismatch counter.  After `ENFORCED`, direct legacy `SkitAdCallbackKeyMapper` public-key lookup is removed from callback routing.

Maintain 43-character backward compatibility and reserve `acct_` by rejecting that prefix from future tenant key generation.  Hash each lookup once; do not query provider then tenant tables as a fallback.  `CallbackRoute.toString()` must retain only tenant IDs/version for tenant paths and never expose raw keys or hashes.

- [ ] **Step 4: Verify rotation, rollback, backfill, shadow read and concurrent cutover**

Run: `mvn -pl yudao-module-skit -Dtest=SkitCallbackRouteRegistryServiceTest,SkitCallbackRoutingServiceTest test`

Expected: PASS; reward routing remains tenant-only and a provider route cannot enter it.

Run: `mvn -pl yudao-module-skit -Dit.test=SkitCallbackRouteRegistryMySqlIT failsafe:integration-test failsafe:verify`

Expected: PASS; 20 concurrent rotation/backfill operations leave no tenant key without one registry row, no duplicate hash owner, and no issued but unrouteable key.

- [ ] **Step 5: Commit registry and tenant compatibility work**

```bash
git add yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/provider/SkitAdCallbackRouteRegistryDO.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/provider/SkitAdCallbackRouteRegistryMapper.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitCallbackRouteRegistryService.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/SkitAdCredentialVersionService.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/SkitAdCredentialVersionServiceImpl.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitCallbackRoutingService.java yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitCallbackRouteRegistryServiceTest.java yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/integration/SkitCallbackRouteRegistryMySqlIT.java
git commit -m "feat: route callback keys through global registry"
```

### Task 3: Model provider connections and one-time callback-route lifecycle

**Files:**
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/provider/SkitAdProviderConnectionDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/provider/SkitAdProviderCallbackRouteDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/provider/SkitAdProviderConnectionMapper.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/provider/SkitAdProviderCallbackRouteMapper.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/provider/SkitProviderConnectionService.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/provider/SkitProviderConnectionServiceImpl.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/SkitCallbackPublicUrlService.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/provider/SkitProviderConnectionServiceTest.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/integration/SkitProviderConnectionLifecycleMySqlIT.java`

**Interfaces:**
- Consumes: `SkitCallbackRouteRegistryService.registerProviderRoute(ProviderCallbackRouteRegistration registration)`, `SkitCallbackPublicUrlService.impressionCallbackUrl(String)`, global tables from Task 1.
- Produces: `ConnectionView createSharedMaster(CreateSharedMasterCommand)`, `RouteView createDraftRoute(long,RoutePurpose,String)`, `IssuedRoute issueOnce(IssueRouteCommand)`, `RouteView abandonNeverShared(AbandonRouteCommand)`, `RouteView markSubmitted(MarkSubmittedCommand)`, `ConnectionView block(BlockConnectionCommand)`, and `ProviderRouteResolution resolveProviderImpression(String,LocalDateTime)`.

- [ ] **Step 1: Write failing lifecycle tests**

```java
@Test
void sharedMasterCanHaveOneNonTerminalConnectionAndOnePrimaryRoute() {
    ConnectionView first = service.createSharedMaster(command("taku-shared-ref"));
    assertThatThrownBy(() -> service.createSharedMaster(command("another-ref")))
            .isInstanceOf(DuplicateKeyException.class);
    assertThat(service.createDraftRoute(first.getId(), GATE_TEST, "initial").getRouteStatus()).isEqualTo("DRAFT");
}

@Test
void issueReturnsPlaintextExactlyOnceAndStoresOnlyHashAndFingerprint() {
    IssuedRoute issued = service.issueOnce(new IssueRouteCommand(ROUTE_ID, "reauthenticated-admin"));
    assertThat(issued.consumeCallbackUrl()).contains("/taku/acct_");
    assertThatThrownBy(issued::consumeCallbackUrl).isInstanceOf(IllegalStateException.class);
    assertThat(registryMapper.selectByProviderRouteId(ROUTE_ID).getKeyHash()).hasSize(32);
}

@Test
void submittedRouteCannotBeReissuedOrReturnedToDraftAndBlockIsTerminalForIngress() {
    service.markSubmitted(new MarkSubmittedCommand(ROUTE_ID, "TK-123", "am@example", "reason"));
    assertThatThrownBy(() -> service.issueOnce(new IssueRouteCommand(ROUTE_ID, "reauthenticated-admin")))
            .isInstanceOf(IllegalStateException.class);
    service.block(new BlockConnectionCommand(CONNECTION_ID, "suspected exposure"));
    assertThat(service.resolveProviderImpression(KEY, NOW).isAccepting()).isFalse();
}
```

- [ ] **Step 2: Run lifecycle tests to verify they fail**

Run: `mvn -pl yudao-module-skit -Dtest=SkitProviderConnectionServiceTest test`

Expected: FAIL because provider connection and route lifecycle APIs are absent.

- [ ] **Step 3: Implement only the phase-1 state transitions**

```java
public interface SkitProviderConnectionService {
    ConnectionView createSharedMaster(CreateSharedMasterCommand command);
    RouteView createDraftRoute(long providerConnectionId, RoutePurpose purpose, String reason);
    IssuedRoute issueOnce(IssueRouteCommand command);
    RouteView abandonNeverShared(AbandonRouteCommand command);
    RouteView markSubmitted(MarkSubmittedCommand command);
    ConnectionView block(BlockConnectionCommand command);
    ProviderRouteResolution resolveProviderImpression(String callbackKey, LocalDateTime receivedAt);
}
```

Generate raw key bytes with `SecureRandom`, consume exactly 228 bits in 38 consecutive 6-bit chunks, prepend `acct_`, hash with SHA-256, and insert the registry owner plus route linkage in one transaction.  `IssuedRoute` holds the raw URL in a private `char[]`; `consumeCallbackUrl()` clears it after the first call; `toString()` returns route id/status/fingerprint only.  Persist only the hash-derived fingerprint on the route and the full irreversible hash in registry, plus `canonical_origin`, path/template version, origin fingerprint and contract fingerprint; never persist plaintext URL or key.  `RoutePurpose` is immutable: a `GATE_TEST` route can be issued and captured but only abandoned/blocked, while a `PRODUCTION` route can be submitted only after the production issuance gate passes.

`abandonNeverShared` accepts only an ISSUED route plus an explicit bounded “never shared” declaration and preserves the tombstoned registry hash. `markSubmitted` accepts only an ISSUED `PRODUCTION` primary route after the production issuance gate and writes only ticket/reference recipient, timestamp, actor, key-hash fingerprint and origin fingerprint.  `block` atomically changes connection to BLOCKED and every accepting route to `BLOCKED/INACTIVE`; do not create replacement or migration behavior in phase 1.  `SkitCallbackPublicUrlService` gets a provider-only `providerImpressionCallbackUrl(char[] rawKey)` that validates the same 43-character grammar and ordered macro template, while existing tenant reward/impression methods remain unchanged.

- [ ] **Step 4: Verify lifecycle and MySQL generated constraints**

Run: `mvn -pl yudao-module-skit -Dtest=SkitProviderConnectionServiceTest,SkitCallbackPublicUrlServiceTest test`

Expected: PASS; one-time URL consumption, status transition rejection, and raw-key redaction pass.

Run: `mvn -pl yudao-module-skit -Dit.test=SkitProviderConnectionLifecycleMySqlIT failsafe:integration-test failsafe:verify`

Expected: PASS; MySQL rejects a second active shared master, third accepting route, bad owner-mode combination, and route slot collision.

- [ ] **Step 5: Commit provider connection lifecycle**

```bash
git add yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/provider yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/provider yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/provider yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/SkitCallbackPublicUrlService.java yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/provider/SkitProviderConnectionServiceTest.java yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/integration/SkitProviderConnectionLifecycleMySqlIT.java
git commit -m "feat: add provider callback route lifecycle"
```

### Task 4: Capture valid provider impressions into global Inbox and Attempt

**Files:**
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/provider/SkitProviderImpressionInboxDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/provider/SkitProviderCallbackAttemptDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/provider/SkitProviderImpressionInboxMapper.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/provider/SkitProviderCallbackAttemptMapper.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionWireParser.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionCaptureService.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionCaptureServiceImpl.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/crypto/SkitProviderCallbackPayloadCryptoService.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/crypto/SkitCallbackPayloadCryptoService.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/crypto/SkitAdCredentialCryptoService.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/crypto/SkitAesGcmCredentialCryptoService.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionWireParserTest.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionCaptureServiceTest.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/integration/SkitProviderImpressionCaptureMySqlIT.java`

**Interfaces:**
- Consumes: `ProviderRouteResolution` from Task 3; `SkitCallbackPayloadCryptoService`; global Inbox/Attempt schema.
- Produces: `CaptureDecision capture(ProviderRouteResolution,String,String,LocalDateTime)` and `WirePayload parseBounded(String)`; `CaptureDecision` is exactly `ACK_200`, `REJECT_602`, or `PERSISTENCE_FAILURE_503`.

- [ ] **Step 1: Write failing parser/capture tests**

```java
@Test
void acceptsUnknownFieldsAndAllFiveFormatsButRetainsWireOrderAndDuplicates() {
    WirePayload wire = parser.parseBounded("req_id=A&adsource_id=01&adformat=4&future=x&future=y");
    assertThat(wire.getParameters()).hasSize(5);
    assertThat(wire.getWirePayloadHash()).hasSize(32);
    assertThat(wire.officialDedupeKey()).isPresent();
}

@Test
void invalidOfficialKeyFallsBackToWireHashAndIsQuarantined() {
    CaptureDecision decision = capture.capture(route(), "req_id=%00&unknown=value", IP, NOW);
    assertThat(decision).isEqualTo(ACK_200);
    assertThat(inbox().getDedupeScheme()).isEqualTo("FALLBACK_WIRE_V1");
    assertThat(inbox().getProcessingStatus()).isEqualTo("QUARANTINED");
}

@Test
void duplicateAndConflictWriteAnAttemptEveryTimeWithoutASecondInbox() {
    capture.capture(route(), GOOD, IP, NOW);
    capture.capture(route(), GOOD, IP, NOW.plusSeconds(1));
    capture.capture(route(), SAME_OFFICIAL_KEY_DIFFERENT_MATERIAL, IP, NOW.plusSeconds(2));
    assertThat(inboxCount()).isEqualTo(1);
    assertThat(attemptCount()).isEqualTo(3);
    assertThat(inbox().getIntegrityStatus()).isEqualTo("PAYLOAD_CONFLICT");
}
```

- [ ] **Step 2: Run capture tests to verify they fail**

Run: `mvn -pl yudao-module-skit -Dtest=SkitProviderImpressionWireParserTest,SkitProviderImpressionCaptureServiceTest test`

Expected: FAIL because the bounded parser, global Inbox and capture transaction do not exist.

- [ ] **Step 3: Implement bounded wire parsing, official/fallback keys and one short transaction**

```java
public interface SkitProviderImpressionCaptureService {
    CaptureDecision capture(ProviderRouteResolution route, String rawQuery, String clientIp,
                            LocalDateTime receivedAt);
}

public enum CaptureDecision { ACK_200, REJECT_602, PERSISTENCE_FAILURE_503 }
```

Use `HttpServletRequest.getQueryString()` bytes only; do not call Servlet parameter-map APIs.  Reject only the fixed boundary violations before persistence.  Parse once with strict percent decoding, preserving original order, repeats and encoded wire form.  Accept known `adformat` values `0..4`, capture unknown values for later quarantine, and never require package name, placement id or `show_custom_ext` at ingress.

For valid official fields, calculate `SHA-256("TAKU_IMPRESSION_OFFICIAL_V1" + lengthFrame(reqIdUtf8) + lengthFrame(normalizedAdsourceId))`; otherwise calculate `SHA-256("TAKU_IMPRESSION_FALLBACK_WIRE_V1" + wirePayloadHash)` and mark `FALLBACK_WIRE_V1/QUARANTINED`.  Material hash is deterministic over only known routing/money fields and missing markers; unknown and device fields remain only in wire ciphertext.  The transaction inserts or locks the one Inbox, creates every Attempt, assigns the first attempt as canonical, monotonically changes only `CANONICAL -> PAYLOAD_CONFLICT`, and commits before ACK_200.  DB exceptions must surface as `PERSISTENCE_FAILURE_503`; do not ACK before commit.

Add an independent `SkitAdCredentialCryptoService.Context.providerCallbackPayload(providerConnectionId, attemptCorrelationId, wirePayloadHash, envelopeVersion)` purpose and a small `SkitProviderCallbackPayloadCryptoService` wrapper so its AAD contains no tenant/account.  Extend `SkitAesGcmCredentialCryptoService` with a separate provider-context framing branch while keeping every existing tenant callback/reward AAD byte-for-byte compatible. Encrypt the raw query with one 12-byte nonce and persist the key id, version and seven-day expiry.  Clear byte/char plaintext buffers in `finally`; `Context` and envelope `toString()` stay redacted.

- [ ] **Step 4: Verify parsing, encryption, idempotency and real-MySQL races**

Run: `mvn -pl yudao-module-skit -Dtest=SkitProviderImpressionWireParserTest,SkitProviderImpressionCaptureServiceTest,SkitCallbackPayloadCryptoServiceTest test`

Expected: PASS; 32 KiB/64/24 KiB boundaries, unknown fields, duplicate parameters, invalid encoding, formats 0–4, redaction and AEAD AAD binding are covered.

Run: `mvn -pl yudao-module-skit -Dit.test=SkitProviderImpressionCaptureMySqlIT failsafe:integration-test failsafe:verify`

Expected: PASS; 20 concurrent duplicate/conflict submissions yield one Inbox, 20 Attempts, no null dedupe hash, and no duplicate canonical attempt.

- [ ] **Step 5: Commit capture-only persistence**

```bash
git add yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/provider/SkitProviderImpressionInboxDO.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/provider/SkitProviderCallbackAttemptDO.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/provider/SkitProviderImpressionInboxMapper.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/provider/SkitProviderCallbackAttemptMapper.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionWireParser.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionCaptureService.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionCaptureServiceImpl.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/crypto/SkitProviderCallbackPayloadCryptoService.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/crypto/SkitCallbackPayloadCryptoService.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/crypto/SkitAdCredentialCryptoService.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/crypto/SkitAesGcmCredentialCryptoService.java yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionWireParserTest.java yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionCaptureServiceTest.java yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/integration/SkitProviderImpressionCaptureMySqlIT.java
git commit -m "feat: capture provider impression callbacks"
```

### Task 5: Wire controller/filter/limiting without regressing reward or tenant impression

**Files:**
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/app/ad/SkitTakuCallbackController.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/web/SkitCallbackSecretSanitizingFilter.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/web/SkitCallbackLogSafetyConfiguration.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/RedisSkitCallbackRateLimiter.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitTakuCallbackIngressDispatcher.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitTakuCallbackIngressDispatcherImpl.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionCapacityGuard.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitCallbackIngressService.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitCallbackIngressServiceImpl.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/controller/app/ad/SkitTakuCallbackControllerTest.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/framework/web/SkitCallbackSecretSanitizingFilterTest.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionCapacityGuardTest.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/integration/SkitProviderImpressionNginxTomcatIT.java`

**Interfaces:**
- Consumes: Task 2 registry lookup, Task 3 provider resolution, Task 4 capture decision; existing `receiveReward` and tenant `receiveImpression` behavior.
- Produces: provider `/impression` 200/503/602 transport mapping and `SkitProviderImpressionCapacityGuard.tryAcquire(long): Permit`; provider capture never calls `TenantUtils.execute`.

- [ ] **Step 1: Write failing HTTP/filter/regression tests**

```java
@Test
void providerRouteImpressionUsesGlobalCaptureAndNeverCreatesTenantContext() throws Exception {
    mockMvc.perform(get("/app-api/skit/ad-callback/taku/" + PROVIDER_KEY + "/impression")
                    .queryParam("future", "value"))
            .andExpect(status().isOk());
    verify(captureService).capture(any(), any(), any(), any());
    verifyNoInteractions(tenantUtilsFacade);
}

@Test
void providerRouteCannotReachRewardAndTenantRewardAndImpressionStayCompatible() throws Exception {
    mockMvc.perform(get(providerRewardPath())).andExpect(status().is(602));
    mockMvc.perform(get(tenantRewardPath()).queryParam("trans_id", "tx")).andExpect(status().is2xxSuccessful());
    mockMvc.perform(get(tenantImpressionPath()).queryParam("req_id", "r")).andExpect(status().is2xxSuccessful());
}

@Test
void oversizedOrWrongMethodIs602WithoutRawPayloadPersistence() throws Exception {
    mockMvc.perform(post(providerImpressionPath()).content("req_id=x"))
            .andExpect(status().is(602));
    verifyNoInteractions(captureService);
}
```

- [ ] **Step 2: Run transport tests to verify they fail**

Run: `mvn -pl yudao-module-skit -Dtest=SkitTakuCallbackControllerTest,SkitCallbackSecretSanitizingFilterTest,SkitProviderImpressionCapacityGuardTest test`

Expected: FAIL because the controller cannot distinguish provider registry routes and no account-capacity guard exists.

- [ ] **Step 3: Implement deterministic dispatch and capacity protection**

```java
public interface SkitProviderImpressionCapacityGuard {
    Permit tryAcquire(long providerConnectionId);
    interface Permit extends AutoCloseable { @Override void close(); }
}
```

The controller delegates both endpoints to `SkitTakuCallbackIngressDispatcher`, which hashes and registry-resolves exactly once and never embeds persistence logic in the MVC layer.  For provider impression route, enforce GET/bounds/filter, acquire connection-scoped permit, call Task 4 capture, map ACK to 200, persistence failure to 503, and all routing/boundary failures to 602.  For tenant routes, pass the already resolved tenant route into overloads on the existing `SkitCallbackIngressService` so it does not perform a second key lookup; reward behavior remains tenant-only.  Do not modify reward canonicalization, signature verification, entitlement or existing tenant Inbox schema.

Keep `SkitCallbackSecretSanitizingFilter` before generic logging, redact path/query before downstream filters, and add exact query byte/count/value checks after safe URL setup.  Configure callback response bodies as fixed empty/short text, `Cache-Control: no-store`, no reflected parameters.  Replace the 120/min key business gate only for valid provider impressions: unknown key/IP stays Redis fail-closed; provider connection uses local bounded concurrency plus configurable account peak/burst limit and a low-cardinality overload metric.  Redis failure on a valid provider route must use the locally tested bound and alert, not reject normal capture merely because Redis is unavailable.

- [ ] **Step 4: Run unit, legacy regression and proxy-boundary tests**

Run: `mvn -pl yudao-module-skit -Dtest=SkitTakuCallbackControllerTest,SkitCallbackSecretSanitizingFilterTest,SkitProviderImpressionCapacityGuardTest,SkitCallbackIngressServiceImplTest,TakuCallbackCanonicalizerTest test`

Expected: PASS; provider capture is tenant-free while reward and legacy tenant impressions retain their previous tests.

Run: `mvn -pl yudao-module-skit -Dit.test=SkitProviderImpressionNginxTomcatIT failsafe:integration-test failsafe:verify`

Expected: PASS; Nginx+Tomcat confirms the 32 KiB boundary, invalid percent encoding, repeated keys, wrong method and malformed path; the test records which invalid request-lines are rejected before the filter.

- [ ] **Step 5: Commit public-ingress routing and protection**

```bash
git add yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/app/ad/SkitTakuCallbackController.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/web/SkitCallbackSecretSanitizingFilter.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/web/SkitCallbackLogSafetyConfiguration.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/RedisSkitCallbackRateLimiter.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionCapacityGuard.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitCallbackIngressService.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitCallbackIngressServiceImpl.java yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/controller/app/ad/SkitTakuCallbackControllerTest.java yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/framework/web/SkitCallbackSecretSanitizingFilterTest.java yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionCapacityGuardTest.java yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/integration/SkitProviderImpressionNginxTomcatIT.java
git commit -m "feat: route account-level impression ingress"
```

### Task 6: Provide super-admin lifecycle API with password reauthentication and non-secret audit

**Files:**
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/provider/SkitProviderConnectionController.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/provider/vo/SkitProviderConnectionCreateReqVO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/provider/vo/SkitProviderCallbackRouteCreateReqVO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/provider/vo/SkitProviderCallbackRouteIssueReqVO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/provider/vo/SkitProviderCallbackRouteSubmittedReqVO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/provider/vo/SkitProviderCallbackRouteAbandonReqVO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/provider/vo/SkitProviderConnectionBlockReqVO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/provider/vo/SkitProviderConnectionRespVO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/provider/vo/SkitProviderCallbackRouteIssuedRespVO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/provider/SkitCurrentAdminPasswordReauthService.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/provider/SkitCurrentAdminPasswordReauthServiceImpl.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/provider/SkitPlatformProviderCommandAuditDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/provider/SkitPlatformProviderCommandAuditMapper.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/provider/SkitPlatformProviderCommandExecutor.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/security/SkitPlatformAdminGuard.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/enums/ErrorCodeConstants.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/controller/admin/provider/SkitProviderConnectionControllerTest.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/provider/SkitCurrentAdminPasswordReauthServiceTest.java`

**Interfaces:**
- Consumes: Task 3 service; existing authenticated admin identity, password encoder and `SkitPlatformAdminGuard`.
- Produces: platform endpoints `POST /admin-api/skit/provider-connections/shared-master`, `GET /admin-api/skit/provider-connections/{id}`, `POST /admin-api/skit/provider-connections/{id}/routes`, `POST /admin-api/skit/provider-routes/{id}/issue-once`, `POST /admin-api/skit/provider-routes/{id}/abandon-never-shared`, `POST /admin-api/skit/provider-routes/{id}/mark-submitted`, `POST /admin-api/skit/provider-connections/{id}/block`; `void verifyCurrentUserPassword(char[] password)` and an append-only platform provider audit command.

- [ ] **Step 1: Write failing authorization, one-time response and redaction tests**

```java
@Test
void issueRequiresSuperAdminAndImmediatePasswordVerification() throws Exception {
    mockMvc.perform(post(issuePath()).content(json("currentPassword", "wrong")))
            .andExpect(status().is4xxClientError());
    verify(reauthService).verifyCurrentUserPassword(any(char[].class));
}

@Test
void issuedResponseIsNoStoreAndOnlyWriteResponseContainsFullUrl() throws Exception {
    mockMvc.perform(post(issuePath()).content(json("currentPassword", "correct")))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.callbackUrl").value(containsString("/acct_")));
    mockMvc.perform(get(connectionPath())).andExpect(jsonPath("$.data.callbackUrl").doesNotExist());
}

@Test
void requestResponseAuditAndToStringNeverExposePasswordKeyOrUrl() {
    assertRedacted(new SkitProviderCallbackRouteIssueReqVO("password"));
    assertRedacted(new SkitProviderCallbackRouteIssuedRespVO("https://secret"));
    assertThat(auditCommandBody()).doesNotContain("password", "acct_", "https://");
}
```

- [ ] **Step 2: Run management API tests to verify they fail**

Run: `mvn -pl yudao-module-skit -Dtest=SkitProviderConnectionControllerTest,SkitCurrentAdminPasswordReauthServiceTest test`

Expected: FAIL because the platform-only provider API and current-user password verifier do not exist.

- [ ] **Step 3: Implement the minimum complete management surface**

```java
public interface SkitCurrentAdminPasswordReauthService {
    void verifyCurrentUserPassword(char[] password);
}
```

Every endpoint invokes `SkitPlatformAdminGuard.check()` and uses `@PreAuthorize("@ss.hasRole('super_admin')")`; it does not use tenant scope switching.  The reauth implementation loads the currently authenticated system user, compares the supplied password with the existing password encoder immediately in the same request, clears the `char[]` in `finally`, and exposes neither a reusable reauth token nor the supplied password to command audit.  The controller uses `@ApiAccessLog(requestEnable = false, responseEnable = false)` on issue-once and `CacheControl.noStore()` on every lifecycle mutation.

Creation makes only SHARED_MASTER connection; DRAFT-route creation has no key and requires immutable `GATE_TEST|PRODUCTION` purpose; issue-once consumes `IssuedRoute` exactly once; abandon-never-shared requires reauthentication and a bounded explicit declaration; mark-submitted accepts ticket recipient/time/reason but rejects `GATE_TEST` routes, URL, screenshot, attachment and free text that contains `http`, `acct_` or a 43-character callback token; block records reason and returns status only.  All GET responses contain IDs, purpose, state, timestamps, canonical origin, hash-derived fingerprint and health timestamps, never callback key, full URL, raw query, ciphertext or attempt payload.  Every mutation executes with its append-only `skit_platform_provider_command_audit` row in the same transaction, recording actor/original-login-tenant/action/reason/reauth-time/safe state hashes/fingerprint/trace/result and no request/response body.

- [ ] **Step 4: Verify auth, reauthentication, headers and negative secret scans**

Run: `mvn -pl yudao-module-skit -Dtest=SkitProviderConnectionControllerTest,SkitCurrentAdminPasswordReauthServiceTest,SkitAdminRecordServiceImplTest test`

Expected: PASS; ordinary tenant_admin receives denial, wrong password denies issue, GET never returns secrets, and audit contains only permitted metadata.

Run: `rg -n 'callbackUrl|callbackKey|currentPassword|acct_' yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/provider yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/provider`

Expected: output contains only the issued response’s write-only field declaration and explicit redaction assertions; no logger, exception, `toString()` or audit serializer interpolates these values.

- [ ] **Step 5: Commit platform management API**

```bash
git add yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/provider yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/provider/SkitCurrentAdminPasswordReauthService.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/provider/SkitCurrentAdminPasswordReauthServiceImpl.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/security/SkitPlatformAdminGuard.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/enums/ErrorCodeConstants.java yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/controller/admin/provider/SkitProviderConnectionControllerTest.java yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/provider/SkitCurrentAdminPasswordReauthServiceTest.java
git commit -m "feat: manage provider callback routes securely"
```

### Task 7: Retain capture evidence safely and expose only operational health

**Files:**
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionRetentionService.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionRetentionServiceImpl.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/job/ad/SkitProviderImpressionRetentionJob.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/job/ad/SkitProviderImpressionRetentionScheduler.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/observability/SkitProviderImpressionCaptureObservation.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/provider/SkitProviderConnectionServiceImpl.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionRetentionServiceTest.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/framework/observability/SkitProviderImpressionCaptureObservationTest.java`

**Interfaces:**
- Consumes: phase-1 Inbox/Attempt and connection status; existing scheduler conventions.
- Produces: `int purgeExpiredCiphertexts(String leaseOwner, LocalDateTime now)` and health view `{firstReceivedAt,lastReceivedAt,acceptedAttempts,duplicates,conflicts,quarantined,dbFailures}` with no high-cardinality dimensions.

- [ ] **Step 1: Write failing retention and observation tests**

```java
@Test
void onlyProcessedOrAlertedDeadLetterAttemptsLoseExpiredCiphertext() {
    assertThat(service.purgeExpiredCiphertexts("node-a", NOW)).isEqualTo(1);
    assertThat(readyAttempt().getPayloadCiphertext()).isNull();
    assertThat(unprocessedAttempt().getPayloadCiphertext()).isNotNull();
}

@Test
void metricsNeverUseKeyPackagePlacementRequestIdOrRawQueryAsTagValues() {
    observation.recordAccepted(CONNECTION, "package.name", "placement", "req", "acct_secret");
    assertThat(registry.tagKeys()).containsOnly("provider", "route_type", "decision", "format");
}
```

- [ ] **Step 2: Run retention tests to verify they fail**

Run: `mvn -pl yudao-module-skit -Dtest=SkitProviderImpressionRetentionServiceTest,SkitProviderImpressionCaptureObservationTest test`

Expected: FAIL because global encrypted Attempts have no retention job or redacted telemetry.

- [ ] **Step 3: Implement capture-only processing/retention boundary**

```java
public interface SkitProviderImpressionRetentionService {
    int purgeExpiredCiphertexts(String leaseOwner, LocalDateTime now);
}
```

The phase-1 job does not decrypt for attribution and never writes observations, revenue, sessions, entitlements, reward receipts, tenant projections or report tables.  It leases only global Attempts, clears ciphertext/nonce/key id after seven days only when the associated Inbox is structurally parsed or explicitly `DEAD_LETTER` with alert timestamp, and records key-destroy/purge result with no payload data.  Configure retention at seven days with a validated maximum of 30; reject zero, negative and greater values at startup.

Record only bounded metrics: request/200/602/503, transaction duration histogram, persistence failure, duplicate/conflict/fallback/quarantine counts, capacity reject, Redis degradation and last accepted timestamp.  Use provider/type/decision/format bucket only; connection IDs, Key hash, IP, package, placement, request id, raw query and device values are never tag values.  Connection health reports counters and timestamps, not Inbox rows.

- [ ] **Step 4: Verify retention and observability**

Run: `mvn -pl yudao-module-skit -Dtest=SkitProviderImpressionRetentionServiceTest,SkitProviderImpressionCaptureObservationTest,SkitAdCallbackEvidenceRetentionServiceTest test`

Expected: PASS; existing tenant evidence retention behavior is unchanged and provider evidence purges only eligible ciphertext.

- [ ] **Step 5: Commit capture operations**

```bash
git add yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionRetentionService.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionRetentionServiceImpl.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/job/ad/SkitProviderImpressionRetentionJob.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/job/ad/SkitProviderImpressionRetentionScheduler.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/observability/SkitProviderImpressionCaptureObservation.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/provider/SkitProviderConnectionServiceImpl.java yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitProviderImpressionRetentionServiceTest.java yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/framework/observability/SkitProviderImpressionCaptureObservationTest.java
git commit -m "feat: retain provider callback evidence safely"
```

### Task 8: Make deployment topology and production gates executable

**Files:**
- Modify: `deploy/configure-public-https.sh`
- Inspect only: `deploy/docker-compose.prod.yml`
- Inspect only: `deploy/test-compose-topology.sh`
- Create: `deploy/test-provider-impression-callback-gate.sh`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/provider/SkitProviderImpressionProductionGate.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/provider/SkitSignedProviderImpressionProductionGate.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/provider/SkitProviderConnectionServiceImpl.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/provider/SkitSignedProviderImpressionProductionGateTest.java`
- Modify: `deploy/README.md`
- Modify: `.github/workflows/cicd.yml`
- Test: `deploy/test-provider-impression-callback-gate.sh`

**Interfaces:**
- Consumes: fixed route, application bounds, 250ms p99 target, Phase-1 management API and capture behavior.
- Produces: `deploy/test-provider-impression-callback-gate.sh --environment production-equivalent --draft-connection-id <id>` plus `SkitProviderImpressionProductionGate.assertProductionIssueAllowed()`; the script exits non-zero and the Java gate denies `PRODUCTION` issue while HTTPS, capture persistence, redaction, 503 failpoint, load, accepted-origin contract, HA topology or signed evidence is incomplete. `GATE_TEST` issuance remains available only for the controlled capture proof and can never be submitted.

- [ ] **Step 1: Write the failing deployment-gate tests**

```bash
./deploy/test-provider-impression-callback-gate.sh --environment production-equivalent --draft-connection-id 42
# expected: FAIL: required callback topology is single-host/single-backend; production key issuance is blocked

./deploy/test-compose-topology.sh
# expected: PASS for the currently deployed topology contract; this does not constitute the separate HA issuance evidence
```

- [ ] **Step 2: Run the deployment tests to verify they fail**

Run: `bash deploy/test-provider-impression-callback-gate.sh --environment production-equivalent --draft-connection-id 42`

Expected: FAIL because the current checked-in production topology is single host/single backend and has no provider callback gate.

- [ ] **Step 3: Implement Nginx parity and fail-closed production gating**

```bash
required_checks=(https_route inbox_attempt_200 unknown_key_602 log_redaction db_failpoint_503 load_p99 accepted_origin_contract dual_entry two_backend_instances mysql_ha redis_degradation dns_cert_backup key_custody)
for check in "${required_checks[@]}"; do require_signed_evidence "$check"; done
```

Update callback locations in `configure-public-https.sh` with `access_log off`, fixed client/body/request-line buffer limits that allow at least the application’s 32 KiB query plus path/headers, upstream read/connect/send timeouts bounded below the external two-second total budget, and no request URI/query in error logging.  Make the script assert Nginx and app limits are equal before reload.

Do not rewrite `docker-compose.prod.yml` to pretend that one host is multiple failure domains, and do not broaden this callback change into an unapproved database/Redis/ALB migration.  `test-provider-impression-callback-gate.sh` inspects the real deployment evidence and must fail while the environment remains single host.  `SkitSignedProviderImpressionProductionGate` verifies a bounded canonical manifest containing every required check, issuance purpose, environment fingerprint and expiry with a configured RSA-SHA256 operations public key; absent key/manifest, invalid signature, missing check, wrong environment or expired evidence all deny `PRODUCTION` issue.  A boolean environment flag cannot bypass it.  The gate permits only `GATE_TEST` DRAFT/ISSUED capture for the repository failpoint proof; that purpose cannot transition to SUBMITTED/ACTIVE, and the test verifies 503 plus alert before verifying the failpoint configuration is absent.

Add CI stages for Task 1–7 unit tests, Testcontainers IT, Nginx/Tomcat integration, shellcheck, topology gate in `--environment ci`, and a load-test artifact that demonstrates configured peak plus burst has p99 below 250ms with zero missing committed attempts.  The production runbook must require signed evidence for all `required_checks`, then password-reauthenticated super_admin issuance, then one-time delivery to Taku AM; it must never include a real Key or full URL in the repository.

- [ ] **Step 4: Verify CI and release gates**

Run: `bash deploy/test-compose-topology.sh`

Expected: PASS for the existing deployment contract, while `test-provider-impression-callback-gate.sh --environment production-equivalent` independently remains a deliberate issuance block until genuine cross-failure-domain evidence exists.

Run: `bash deploy/test-provider-impression-callback-gate.sh --environment ci --draft-connection-id 42`

Expected: PASS using non-secret fixture evidence and no production key.

Run: `mvn -pl yudao-module-skit test-compile failsafe:integration-test failsafe:verify`

Expected: PASS; all phase-1 and legacy reward/tenant impression tests complete.

- [ ] **Step 5: Commit operational gates**

```bash
git add deploy/configure-public-https.sh deploy/test-provider-impression-callback-gate.sh deploy/README.md .github/workflows/cicd.yml yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/provider/SkitProviderImpressionProductionGate.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/provider/SkitSignedProviderImpressionProductionGate.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/provider/SkitProviderConnectionServiceImpl.java yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/provider/SkitSignedProviderImpressionProductionGateTest.java
git commit -m "chore: gate provider callback production release"
```

## Final Verification and Production Acceptance

- [ ] Run `git diff --check` and `git status --short`; verify only phase-1 files are staged for review and no real Key, URL, query, password, IP or device identifier appears in tracked changes with `rg -n 'acct_[A-Za-z0-9_-]{38}|https://[^ ]+/app-api/skit/ad-callback/taku/' . --glob '!docs/superpowers/specs/**' --glob '!target/**'`.
- [ ] Run `mvn -pl yudao-module-skit test` followed by `mvn -pl yudao-module-skit test-compile failsafe:integration-test failsafe:verify`; expected PASS includes legacy reward signature/unlock and tenant impression suites.
- [ ] On a production-equivalent environment, use a DRAFT test connection only: prove valid key returns 200 after Inbox+Attempt commit, unknown key returns 602, an injected repository commit failure returns 503 and alert, and all application/Nginx/API logs omit key/query values.
- [ ] Attach capacity evidence for account expected peak plus burst: p50/p95/p99, database lock wait, unique-key contention, Redis-down local-bound behavior and committed-attempt count; accept only p99 below 250ms and zero missing committed attempts.
- [ ] Attach operational evidence for dual public entry/ALB, two independent backend rollouts, Nginx/host health removal, MySQL HA failover, Redis degradation, DNS/certificate renewal, backup recovery, encryption key custody and declared no-retry gap budget.  A single-machine result is a failed gate, not evidence for issuance.
- [ ] Prove accepted-origin, DNS, certificate, Nginx path, macro order and `callback_contract_fingerprint` match on the DRAFT route; a mismatch must reject activation/issuance in the production profile.
- [ ] Only after every gate has signed evidence, a super_admin reauthenticates with current password, issues one real route, receives the full URL once with no-store, records only AM ticket/recipient/time plus fingerprints, marks it SUBMITTED, and hands that one URL to Taku AM.  The route remains capture-only; no phase-1 action enables attribution, estimates, reports, revenue events, rewards or content unlocks.

## Self-Review

- Spec coverage: Tasks 1–3 cover stable connection/route, global hash-first registry, route status and tenant-key dual-write/backfill; Tasks 4–5 cover global Inbox/Attempt, bounded tolerant query capture, AEAD, Controller/Filter and capacity without tenant/reward regression; Task 6 covers super_admin, immediate password reauth, audit and no-store; Task 7 covers retention/observability; Task 8 covers canonical deployment, CI, topology and production gates.  The final acceptance section explicitly prevents real-key issuance before all gates.
- Excluded work: no task creates tenant applications, binding snapshots, impressions observations, attribution revisions, projections, revenue events, money normalization, report pulls, reconciliation, commissions or UI revenue reporting.
- Completeness scan: this document contains concrete filenames, method names, state values, commands, expected failures, expected passes and commit commands; it contains no unspecified implementation step.
- Type consistency: Tasks 2–5 consistently use `ProviderRouteResolution`, Task 4 exposes `CaptureDecision`, Task 5 maps it to HTTP, Task 6 calls Task 3 lifecycle methods, and Task 7 is capture-only.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-02-taku-account-level-impression-callback-phase1.md`. Two execution options:

1. Subagent-Driven (recommended) - I dispatch a fresh subagent per task, review between tasks, fast iteration

2. Inline Execution - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
