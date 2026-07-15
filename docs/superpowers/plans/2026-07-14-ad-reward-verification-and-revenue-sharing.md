# Verified Ad Reward, Revenue Monitoring, and Multi-Tenant Sharing Implementation Plan

> **For Codex:** Execute continuously with test-driven development, task-scoped review, and final whole-change review. Preserve the user-owned untracked files `/Users/neo/Desktop/skit/skit-saas-frontend/src/views/skit/admin/AdminTable 2.vue` and `/Users/neo/Desktop/skit/skit-saas-app/pages/user/info 2.vue` exactly as-is.

**Goal:** Replace client-trusted ad completion and editable sample revenue with server-verified Taku reward sessions, tenant-isolated content entitlements, official-report reconciliation, append-only multi-level commission accounting, and real management analytics.

**Architecture:** Every request starts from a server-owned tenant boundary. Member APIs derive `tenant_id` and `member_id` from the login token; public callbacks derive `tenant_id` from a hashed per-ad-account callback key; management APIs derive the effective tenant from the original admin login and explicit `super_admin` delegation. Taku signed reward S2S grants content entitlement, unsigned impression S2S creates only frozen estimates, and official D+1/D+2 reports create append-only settlement adjustments. A new App bridge carries a single-use server session through Taku local extra and blocks all local reward fallbacks.

**Tech Stack:** Java 8, Spring Boot 2.7.18, MyBatis-Plus, MySQL 8, Quartz/Spring Job, JUnit 5, Mockito, Testcontainers 1.19.8, Vue 3, TypeScript, Element Plus, Vitest, uni-app JavaScript, Android Gradle, Taku 6.6.22, Pangle DJX 2.9.0.9.

**Approved specification:** `docs/superpowers/specs/2026-07-14-ad-reward-verification-and-revenue-sharing-design.md`

## Non-negotiable multi-tenant and trust constraints

- Every tenant business row has `tenant_id BIGINT NOT NULL`; tenant-local unique keys start with `tenant_id`. Finance and entitlement chains additionally require `(tenant_id, id)` candidate keys plus compound foreign keys for session→account/snapshot, callback→account/session, event→session/inbox, grant→session, ledger→event/revision, and bucket/revision→account. Service checks are defense in depth, never a substitute for those database constraints.
- App clients never send an authoritative `tenantId`, ad account, provider, placement, commission plan, beneficiary chain, or unlock expansion. Those values are selected and snapshotted by the backend.
- Callback controllers ignore the normal tenant context. They hash the path `callbackKey`, resolve exactly one ad account and tenant globally, and then enter only that derived tenant context. Request headers, query parameters, bodies, and visit-tenant values cannot select a tenant.
- `tenant_admin` is permanently scoped to the tenant bound to its original login. Only `super_admin` can select another tenant, and only explicitly listed delegated commands are writable.
- Taku `SIGNED_REWARD` is the only phase-one authority for content entitlement and member/upstream commission. `UNSIGNED_PROVIDER_OBSERVATION`, client callbacks, eCPM, local storage, and bridge events never grant entitlement or available money.
- Taku reward and impression S2S are GET callbacks. Reward MD5 authenticates only the documented ordered core (`trans_id`, `placement_id`, `adsource_id`, `reward_amount`, `reward_name`, secret, and exact decoded `ilrd` when configured); top-level `user_id`, `extra_data`, `network_firm_id`, scenario, package, and platform fields are never promoted to signed facts.
- Every ad session locks the active commission plan, inviter chain, eligibility, provider account, placement, and immutable unlock scope in one transaction.
- Revenue and commission rows are append-only. No controller exposes update/delete endpoints for callbacks, reports, events, grants, entitlements, reconciliation revisions, or ledger entries.
- All money uses integer `amount_units` plus `amount_scale` and ISO currency. Cross-currency totals are not silently combined.
- Production rollout remains `OFF` until account secrets, dedicated placement, callback placeholders, real signed reward, real impression, report permission, current APK, minimum native version, and old-client revocation are all verified for that tenant.
- Phase-one unlock traffic is restricted to Taku ADX/direct/cross-promotion network firm IDs `66/67/35`. The authority gate requires a valid reward MD5 that includes exact signed ILRD, strict ILRD parsing and cross-checks, plus an enabled tenant/account capability; an unsigned top-level network ID is insufficient. A third-party ADN such as Pangle cannot enter the unlock traffic group until its own authoritative S2S callback implementation and tests exist.
- Each session stores `callback_key_version` and `reward_secret_version`. A rotated credential can finish only sessions created with that version and only through their recorded `reward_accept_until`; old credentials always reject new sessions.
- An accepted callback is durable work, not merely durable input: canonical inbox rows are claimed with a lease, retried with bounded exponential backoff, recovered on startup, and dead-lettered with an alert only after exhausting attempts.

## Execution rules

- Each task follows RED → confirm the intended assertion fails → minimal implementation → GREEN → related regression → task-scoped commit.
- MySQL locking, compound uniqueness, `LAST_INSERT_ID`, and 20-way concurrency tests must run on MySQL 8 Testcontainers, never H2 or Mockito substitutes.
- Backend focused test command:
  `mvn -pl yudao-module-skit -am -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<TestClass> test`
- Backend MySQL integration command:
  `mvn -pl yudao-module-skit -am -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='*MySqlIT' verify`
- Frontend focused command:
  `pnpm exec vitest run test/unit/skit/<file>.spec.ts`
- App static contract command:
  `node --test tests/app/*.test.mjs`
- Android focused command:
  `./.gradle-dist/gradle-8.10.2/bin/gradle --no-daemon :app:testDebugUnitTest`
- Do not push or enable a tenant until the final release task passes.

### Task 1: Add the backend MySQL integration-test foundation

**Files:**

- Modify: `yudao-module-skit/pom.xml`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/integration/SkitMySqlIntegrationTestBase.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/integration/SkitMySqlIntegrationSmokeTest.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/integration/SkitMySqlPrerequisiteFixture.java`
- Create: `yudao-module-skit/src/test/resources/application-unit-test.yaml`

**Steps:**

1. Add a failing smoke test that starts MySQL 8, obtains the JDBC URL dynamically, bootstraps the prerequisite system tenant/user tables, and verifies the server isolation level and `LAST_INSERT_ID()` behavior.
2. Add test-scoped `org.testcontainers:junit-jupiter` and `org.testcontainers:mysql` version `1.19.8`, configure Maven Failsafe for `*MySqlIT` in `integration-test/verify`, and add the existing project job starter required later by report polling.
3. Build a reusable integration base that disables H2/unit-test initialization, creates a clean schema per class, installs the prerequisite fixture, applies the Skit initializer, and exposes transaction helpers.
4. Run the smoke test and `verify`; confirm the same command that CI uses actually executes the MySQL tests.
5. Commit with `test(skit): add mysql integration foundation`.

### Task 2: Migrate the tenant-safe advertising and finance schema

**Files:**

- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/schema/SkitSchemaInitializer.java`
- Modify: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/framework/schema/SkitSchemaInitializerTest.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/integration/SkitAdSchemaMigrationMySqlIT.java`
- Modify: `sql/mysql/skit-saas.sql`
- Modify: `sql/mysql/ruoyi-vue-pro.sql`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/ad/SkitAdCallbackKeyDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/ad/SkitAdRewardSecretVersionDO.java`
- Create corresponding mappers under `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/ad`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/SkitAdCredentialVersionService.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/SkitAdCredentialVersionServiceImpl.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/ad/SkitAdCredentialVersionServiceImplTest.java`

**Steps:**

1. Write failing unit/integration tests for ordered migration, checksum protection, restart idempotency, tenant-leading unique keys, compound tenant consistency, and failure on duplicate legacy invite codes.
2. Add versioned migrations and bootstrap DDL for policy snapshots, ad sessions, client events, globally unique callback-key hashes, encrypted reward-secret versions, callback edge-attempts, tenant callback inbox/attempts, network capabilities, current entitlements/grants, report pulls, reconciliation buckets/revisions, tenant rollout capability, native player grants, and the global invite registry.
3. Extend revenue events and commission ledger with session/source/match/reconciliation fields, integer money, immutable event references, normalized revision number, tenant-leading idempotency indexes, `(tenant_id,id)` candidate keys, and all compound foreign keys listed in the global constraints.
4. Mark existing client-created estimated events `LEGACY_UNVERIFIED`; never migrate them to available balances.
5. Add preflight diagnostics that stop startup before partial application when invite collisions, invalid currencies, incompatible tenant references, or checksum changes exist.
6. Add the credential-version primitives before any session code: generate/hash globally unique callback keys; encrypt reward secrets; create immutable monotonically versioned rows; resolve active versions by tenant/account; rotate by creating a new version while preserving a bounded prior-version acceptance window. Read methods never return raw secret/key values outside the internal verifier boundary.
7. Write focused tests for tenant/account scope, global key collision, monotonic versioning, write-only response behavior, and old-version bounded lookup.
8. Run schema, credential-service, and `SkitAdSchemaMigrationMySqlIT` tests; commit with `feat(skit): migrate verified ad schema`.

### Task 3: Make invite-code ownership globally unique and transactional

**Files:**

- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/invite/SkitInviteCodeRegistryDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/invite/SkitInviteCodeRegistryMapper.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/invite/SkitInviteCodeRegistryService.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/invite/SkitInviteCodeRegistryServiceImpl.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/agent/SkitAgentServiceImpl.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/member/SkitMemberServiceImpl.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/invite/SkitInviteCodeRegistryServiceImplTest.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/integration/SkitInviteCodeRegistryMySqlIT.java`

**Steps:**

1. Write failing tests proving an agent-root code and a member code cannot claim the same value, concurrent claims yield exactly one owner, rotation disables only future use, and historical relationships never rebind.
2. Add a global registry whose owner stores `owner_type`, `tenant_id`, and `owner_id`; code uniqueness is intentionally global while member ownership is tenant-bound.
3. Move agent create/rotation and member registration/code creation into registry-backed transactions.
4. Keep closure-table writes in the same member-registration transaction and validate the inviter belongs to the resolved agent tenant.
5. Run unit and MySQL concurrency tests; commit with `feat(skit): centralize invite code ownership`.

### Task 4: Snapshot arbitrary-level commission policy with integer conservation

**Files:**

- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/commission/SkitAdPolicySnapshotDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/commission/SkitAdPolicySnapshotMapper.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/commission/SkitPolicySnapshotService.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/commission/SkitPolicySnapshotServiceImpl.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/commission/SkitMoneyAllocator.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/commission/SkitPolicySnapshotServiceImplTest.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/commission/SkitMoneyAllocatorTest.java`

**Steps:**

1. Write failing tests for level 0, master, grandmaster, arbitrary N, missing/disabled ancestors, zero income, maximum ratios, stable rounding, and old sessions retaining the old rule after a publish.
2. In one transaction read the current tenant plan, closure chain, member/tenant status, and ratios; store an immutable JSON/detail snapshot plus indexed plan/version fields.
3. Allocate every beneficiary with floor integer arithmetic and route missing eligibility, disabled ancestors, rounding residue, and unconfigured share to agent retention.
4. Assert for every currency that beneficiary totals plus agent retention exactly equal source units.
5. Run focused tests; commit with `feat(skit): snapshot commission policy`.

### Task 5: Create tenant-bound ad sessions, player grants, and server entitlements

**Files:**

- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/ad/SkitAdSessionDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/ad/SkitAdClientEventDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/member/SkitContentEntitlementDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/member/SkitEntitlementGrantDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/member/SkitNativePlayerGrantDO.java`
- Create corresponding mappers under `dal/mysql/ad` and `dal/mysql/member`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/SkitAdSessionService.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/SkitAdSessionServiceImpl.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/SkitAdSessionStateMachine.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/member/SkitContentEntitlementService.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/member/SkitContentEntitlementServiceImpl.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/app/member/SkitMemberAdSessionController.java`
- Create request/response VOs under `controller/app/member/vo/ad`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/ad/SkitAdSessionStateMachineTest.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/ad/SkitAdSessionServiceImplTest.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/integration/SkitAdSessionMySqlIT.java`

**Steps:**

1. Write failing tests for login-derived tenant/member, server-selected account/placement, inactive tenant/member/account rejection, existing entitlement, same-scope reuse, twenty concurrent same-scope creates, expiry, CAS state transitions, token hashing, and cross-tenant IDs.
2. Generate global `sessionId`, 128-bit base64url `customData`, pseudonymous SDK user ID, five-minute load expiry, and twenty-minute reward window; store only token hashes.
3. Snapshot the commission policy, normalized unlock scope, `callback_key_version`, and `reward_secret_version` in the creation transaction. Store a deterministic `active_scope_hash` only while the session is active and enforce `UNIQUE(tenant_id, member_id, active_scope_hash)`; clear it on terminal expiry/consumption so MySQL NULL semantics permit later sessions. On duplicate insert, lock and return the existing active session. Prove twenty concurrent creates yield one session.
4. Implement strict versioned client event ingestion that updates only client lifecycle/telemetry and never grants entitlement or money.
5. Implement current per-episode entitlements plus append-only grants; implement short-lived player grant scoped to one tenant/member/drama and content access checks.
6. Run unit and MySQL tests; commit with `feat(skit): add server ad sessions and entitlements`.

### Task 6: Parse and verify Taku callbacks deterministically

**Files:**

- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/TakuCallbackCanonicalizer.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/TakuRewardSignatureVerifier.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/TakuRewardCallback.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/TakuImpressionCallback.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/ad/callback/TakuCallbackCanonicalizerTest.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/ad/callback/TakuRewardSignatureVerifierTest.java`

**Steps:**

1. Write official-vector and tamper tests for raw GET query decoding, documented field order, missing/duplicate parameters, invalid encoding, overlong values, test placeholders, and constant-time signature comparison.
2. Parse the untouched raw query from a fixed allow-list into immutable lexical values; reject ambiguous duplicate security fields rather than taking first/last, and preserve the exact decoded `ilrd` string used by the signature.
3. Implement Taku MD5 over exactly `trans_id + placement_id + adsource_id + reward_amount + reward_name + secret [+ ilrd]` in that order and compare decoded bytes in constant time. Prove that changing an unsigned top-level field does not change the MD5 input and never grants that field signed provenance.
4. Strictly parse signed ILRD as network-classification evidence, cross-check network/adsource/ad-unit/show identifiers when present, and keep ILRD out of reward/revenue amount authority. Classify callbacks as `SIGNED_REWARD`, `UNSIGNED_PROVIDER_OBSERVATION`, or health-test probe; a probe cannot enter business processing.
5. Run focused tests; commit with `feat(skit): verify taku callbacks`.

### Task 7: Route callbacks to exactly one tenant and persist a concurrent inbox

**Files:**

- Reuse: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/ad/SkitAdCallbackKeyDO.java`
- Reuse: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/ad/SkitAdRewardSecretVersionDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/ad/SkitAdCallbackEdgeAttemptDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/ad/SkitAdCallbackInboxDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/ad/SkitAdCallbackAttemptDO.java`
- Reuse credential mappers from Task 2; create callback edge/inbox/attempt mappers under `dal/mysql/ad`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitCallbackRoutingService.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitCallbackIngressService.java`
- Reuse: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/SkitAdCredentialVersionService.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/web/SkitCallbackSecretSanitizingFilter.java`
- Create: `yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/apilog/core/ApiRequestUrlResolver.java`
- Modify: `yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/apilog/core/interceptor/ApiAccessLogInterceptor.java`
- Modify: `yudao-framework/yudao-spring-boot-starter-web/src/main/java/cn/iocoder/yudao/framework/apilog/core/filter/ApiAccessLogFilter.java`
- Create: `yudao-framework/yudao-spring-boot-starter-web/src/test/java/cn/iocoder/yudao/framework/apilog/core/CallbackSecretRedactionTest.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/app/ad/SkitTakuCallbackController.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/security/SkitWebSecurityConfiguration.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/controller/app/ad/SkitTakuCallbackControllerTest.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/integration/SkitAdCallbackInboxMySqlIT.java`

**Steps:**

1. Write failing tests that inject false tenant headers/body/query values, unknown/rotated keys, duplicate key generation, old-key/new-session use, same ID across tenants, same key/different payload, over-size/method/rate abuse, secret leakage to logs/errors/MDC, and twenty simultaneous identical deliveries.
2. Mark the public controller tenant-ignored; hash the route key, resolve exactly one account/tenant/key-version through global `UNIQUE(callback_key_hash)`, and enter only `TenantUtils.execute(derivedTenantId)` for business work. Also enforce `(tenant_id, ad_account_id, key_version)` uniqueness. Store each encrypted reward secret in an immutable version row with `UNIQUE(tenant_id, ad_account_id, secret_version)`, `active`, `revoked_at`, and bounded `accept_until`; rotation creates a row instead of overwriting the prior secret.
3. Unknown keys write only a platform edge-attempt containing a one-way hash and minimal request metadata; this non-business security telemetry may have nullable tenant/account. Once routing succeeds, persist a tenant-bound attempt append-only. Acquire the canonical row with `INSERT ... ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)`, then `SELECT ... FOR UPDATE` and constant-time payload-hash comparison.
4. Use `(tenant_id, ad_account_id, callback_type, idempotency_key)` uniqueness. Reward idempotency is `trans_id`; impression idempotency is normalized `req_id + adsource_id`.
5. Return 200 only after durable success/idempotency, 601 for invalid signature, 602 for deterministic rejection, and propagate transient infrastructure failure for platform retry.
6. Enforce GET-only and pass the untouched raw query string to Task 6; reject POST and every other method. Apply small request/parameter limits and per-IP plus hashed-key rate limits. The framework exposes a generic sanitized-request-URL request attribute/resolver without depending on Skit; the Skit filter sets only that attribute while leaving the original URI and query untouched for Spring routing and signature verification. `ApiAccessLogInterceptor`, `ApiAccessLogFilter`, errors, and MDC read the sanitized value first and suppress callback parameters. Prove the raw key/query never appears in captured logs or the API-access-log record and routing still resolves the original path.
7. Add deployment verification that proxy/container access logs never record the callback-key path; document provider URLs as secrets.
8. Run controller, raw-query, log-safety, and MySQL concurrency tests; commit with `feat(skit): add tenant-routed callback inbox`. Lease/retry/dead-letter and 90/180-day cleanup belong to Task 8 so delivery attempts never increment processor attempts.

### Task 8: Process reward and impression callbacks into entitlement and frozen revenue

**Files:**

- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitAdCallbackProcessor.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitAdCallbackProcessorImpl.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitAdCallbackInboxDrainService.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/job/SkitAdCallbackInboxDrainJob.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/job/SkitCallbackRetentionJob.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/revenue/SkitAdRevenueEventDO.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/revenue/SkitCommissionLedgerDO.java`
- Modify corresponding revenue mappers/services
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitAdCallbackProcessorTest.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/integration/SkitRewardEntitlementMySqlIT.java`

**Steps:**

1. Write failing tests for reward-before-impression, impression-before-reward, client-close-before-S2S, duplicated and conflicting IDs, `trans_id != providerShowId`, wrong user/token/placement/account, forged inbox tenant/account mismatch, two-tenant concurrent drain, expired sessions, archived tenants, old credential/new session, crashed worker recovery, lease expiry, retry/dead-letter, and unsupported network firms.
2. Allow signed reward only when callback key/secret versions equal the session snapshot and the callback is inside that session's reward window. A pre-archive session may then atomically mark reward verified, bind transaction/show IDs, append one grant, and upsert current entitlements.
3. Treat impression as unsigned observation: match only `show_custom_ext == sessionId`, convert documented `adsource_price` eCPM to one-impression estimate by dividing by 1000 with integer-safe precision, create one frozen estimate, and never grant content.
4. At reward-window expiry route matched nonrewarded impression income 100% to agent and create no member/upstream entries.
5. Hard-allow only network firm IDs `66/67/35` for phase-one unlock, and derive that classification only from verified signed ILRD (or a separately trusted server-side signed adsource mapping) plus enabled tenant/account capability. Preserve unsigned top-level network evidence for comparison, but never use it as authority; reject other networks until an independent third-party S2S implementation is shipped.
6. In global tenant-ignore context claim only immutable inbox routing metadata with `SKIP LOCKED`/lease ownership. Revalidate the compound `(tenant_id, ad_account_id)` relationship, then process each item inside `TenantUtils.execute(derivedTenantId)`; job parameters can never select a tenant. Use bounded exponential backoff, startup recovery, and dead-letter alerts. Processing is end-to-end idempotent so an ACK followed by process death still converges exactly once and two tenants draining concurrently cannot cross-read.
7. Keep reward, entitlement, revenue, and client lifecycle statuses orthogonal and converge under any callback order with CAS/row locks.
8. Add the retention job here: erase encrypted callback payloads after 90 days while preserving immutable hashes/status, retain delivery attempts for 180 days, and keep financial/entitlement facts long-term. Run unit and MySQL crash/concurrency/cleanup tests; commit with `feat(skit): process verified ad rewards`.

### Task 9: Permanently downgrade the legacy client revenue endpoint

**Files:**

- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/app/member/SkitMemberAdRevenueController.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/revenue/SkitRevenueService.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/revenue/SkitRevenueServiceImpl.java`
- Modify: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/revenue/SkitRevenueServiceImplTest.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/controller/app/member/SkitMemberAdRevenueControllerTest.java`

**Steps:**

1. Write failing tests proving forged completion/eCPM, foreign tenant/member/session, and repeated calls cannot create a revenue event, entitlement, estimate, or ledger entry.
2. Preserve the old route for compatibility as an authenticated, rate-limited acknowledgement only. Return `deprecated: true`, `status: LEGACY_UNVERIFIED`, `financialEffect: false`, and the new session-flow hint, but write no ad session, client event, entitlement, estimate, revenue event, or ledger row. Observe migration only through sanitized access logs and low-cardinality metrics; the legacy payload has no authoritative session identity and must never weaken the tenant compound foreign keys or trigger a synthetic session.
3. Delete every service branch that accepts client amount or completed status as financial truth.
4. Run focused and callback regressions; commit with `fix(skit): retire client trusted ad revenue`.

### Task 10: Add report polling, deterministic reconciliation, and append-only ledger projection

**Files:**

- Add a new Task 10 migration for versioned AEAD Taku reporting credentials, account report timezone/currency/scale and cross-node pull lease/rate state; never edit the released Task 2 checksum.
- Create report/reconciliation DOs and mappers under `dal/dataobject/reconciliation` and `dal/mysql/reconciliation`
- Create a write-only reporting-credential DO/mapper/service and `skit_ad_reconciliation_allocation` for per-revision event/beneficiary cumulative targets.
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/reconciliation/TakuReportingClient.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/reconciliation/SkitAdReportPullService.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/reconciliation/SkitReconciliationAllocator.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/reconciliation/SkitLedgerProjectionService.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/job/SkitAdReportPullJob.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/reconciliation/SkitReconciliationAllocatorTest.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/reconciliation/SkitLedgerProjectionServiceTest.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/integration/SkitReconciliationMySqlIT.java`

**Steps:**

1. Write failing tests for `N=K`, `0<K<N`, `K>N`, missing N, `E=0/A>0`, mixed placement, zero income, multi-currency, duplicate revision, D+2 down-adjustment, and partial task retry.
2. Treat the Taku Publisher Key as a separate reporting credential from SDK App Key and reward secret. Store immutable versions with the Task 2 AEAD seam, expose only configured/version metadata, and use POST `/v2/fullreport` with bounded date queries; never reuse `EncryptTypeHandler` or return/log the Publisher Key.
3. In global tenant-ignore context enumerate every account with unsettled pre-archive events, including archived tenants; then process one account at a time in stable serial order inside `TenantUtils.execute(derivedTenantId)` with account timezone, cross-node lease/rate limits, response hashes, and no secret logging. Never use `@TenantJob` or a job-supplied tenant selector.
4. Define the authoritative report query/bucket dimensions as tenant/account/App/report-date/timezone/dedicated placement/ad format/network/adsource/currency. Use report `revenue` as actual amount and `impression_api` as N; reject mixed or insufficient dimensions to suspense.
5. Attribute `A*K/N` with integer floor; allocate matched events proportionally to estimate with stable-ID remainder distribution. `K-R` goes 100% to agent; unmatched residual stays `SUSPENSE`.
6. Append exactly one `ESTIMATE_RELEASE`, then `SETTLEMENT`; later report revisions append `target_current - target_previous` as `ADJUSTMENT`. Persist per-revision event/beneficiary cumulative targets so partial retry is deterministic. Reserve `REVERSAL` for independent fraud/platform withdrawal.
7. Enforce tenant-leading ledger/allocation uniqueness including normalized `revision_no=0` so MySQL NULL semantics cannot duplicate entries.
8. Test that an archived tenant continues D+1/D+2 reconciliation only for facts created before archival, without calling active-tenant validation or reading another tenant's credentials/facts.
9. Run unit and MySQL integration tests; commit with `feat(skit): reconcile official ad revenue`.

### Task 11: Expose tenant-scoped management and audit APIs

**Files:**

- Create controllers: `SkitAdAnalyticsController.java`, `SkitAdEventController.java`, `SkitReconciliationController.java`, `SkitCommissionPlanController.java`, `SkitMemberTreeController.java` under `controller/admin/tenant`
- Create corresponding VOs under `controller/admin/tenant/vo`
- Create services under `service/analytics`, `service/reconciliation`, `service/commission`, and `service/member`
- Create durable tenant-bound management-command audit and asynchronous export-task DOs/mappers/services; exports capture the requester's immutable tenant/scope and expire downloads after 24 hours.
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/SkitTenantBusinessController.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/enums/ErrorCodeConstants.java`
- Create focused controller tests for each controller under `src/test/java/.../controller/admin/tenant`

**Steps:**

1. Write failing tests for tenant-admin scope locking, super-admin explicit target selection, cross-tenant row IDs, immutable-resource method denial, stable pagination, currency grouping, and optimistic commission publish.
2. Add overview/timeseries, event detail, reconciliation difference, current/history/preview plan, ledger, children/ancestors/subtree, and readiness endpoints.
3. Derive tenant-admin scope exclusively from original-login tenant. Permit only an original system-tenant `super_admin` to select a target through one shared guard. Execute delegated writes from an explicit command whitelist with a required reason and append a durable audit fact in the same transaction; asynchronous API/operation logs are not the authority.
4. Keep callback/report/event/entitlement/ledger endpoints read-only; expose only bounded task retry and explicit audited security revocation to super-admin.
5. Return stable enums, fixed `asOf`, timezone, ISO currency, decimal strings, and keyset server-side filters/pagination. Large exports use durable tasks and short-lived downloads, never a synchronous current-page export.
6. Run all controller/security tests; commit with `feat(skit): expose tenant ad operations`.

### Task 12: Add tenant readiness and atomic rollout gates

**Files:**

- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/ad/SkitTenantAdCapabilityDO.java`
- Create corresponding mapper/service/controller VOs
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/SkitAdAccountServiceImpl.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/app/SkitAppReleaseServiceImpl.java`
- Modify ad-session/player-grant/content-access services from Task 5
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/ad/SkitTenantAdCapabilityServiceImplTest.java`

**Steps:**

1. Write failing tests for every readiness prerequisite and atomic `OFF → SHADOW_TEST_USERS → ENFORCED` transition, including old App version, stale report, missing real callbacks, and archived tenant.
2. Store rollout state per tenant; secrets remain encrypted/write-only and callback keys are hashed/versioned with bounded grace.
3. Allow shadow sessions only for configured test members. Enforced mode requires current native version and simultaneously rejects old player grants, ad sessions, and protected content.
4. Prevent readiness from passing when any unlock network lacks authoritative S2S capability or when the placement is shared with non-unlock traffic.
5. Run focused app/ad tests; commit with `feat(skit): gate tenant ad rollout`.

### Task 13: Add App API contracts and remove local authorization truth

**Files:**

- Create: `/Users/neo/Desktop/skit/skit-saas-app/sheep/api/member/ad-session.js`
- Create: `/Users/neo/Desktop/skit/skit-saas-app/sheep/api/member/entitlement.js`
- Modify: `/Users/neo/Desktop/skit/skit-saas-app/sheep/api/member/ad-revenue.js`
- Modify: `/Users/neo/Desktop/skit/skit-saas-app/pages/drama/data.js`
- Modify: `/Users/neo/Desktop/skit/skit-saas-app/pages/drama/play.vue`
- Create: `/Users/neo/Desktop/skit/skit-saas-app/pages/drama/services/ad-session-orchestrator.js`
- Create: `/Users/neo/Desktop/skit/skit-saas-app/tests/app/ad-session-orchestrator.test.mjs`

**Steps:**

1. Write failing Node tests for strict protocol version/session/provider/placement validation, tenant/member cache separation, pending-session recovery, exponential status polling, and local storage tampering.
2. Request player grants/ad sessions from authenticated APIs and pass only the server protocol object to native code.
3. Refresh server entitlements on login, drama entry, tenant/account change, and foreground resume. Treat local storage only as UI cache keyed by tenant and member.
4. Poll session status after close with `0.5s/1s/2s/3s/3s`; persist pending `sessionId` under the current identity and recover after interruption.
5. Remove all code paths that unlock episodes directly from native/client reward or client eCPM.
6. Run App Node tests and the existing identity boundary check; commit with `feat(app): use server verified ad sessions`.

### Task 14: Replace the JavaScript reward adapters with one strict Taku bridge

**Files:**

- Modify: `/Users/neo/Desktop/skit/skit-saas-app/pages/drama/services/native-bridge.js`
- Modify: `/Users/neo/Desktop/skit/skit-saas-app/pages/drama/services/taku-reward-ad.js`
- Modify: `/Users/neo/Desktop/skit/skit-saas-app/pages/drama/services/reward-ad.js`
- Delete: `/Users/neo/Desktop/skit/skit-saas-app/pages/drama/services/gromore-reward-ad.js`
- Create: `/Users/neo/Desktop/skit/skit-saas-app/tests/app/reward-bridge-contract.test.mjs`

**Steps:**

1. Write failing tests that reject permissive aliases, missing session/show IDs, wrong placement/provider/protocol, nonmonotonic callback sequence, readiness from another session, and any fallback success.
2. Make Taku the only phase-one reward orchestrator; Pangle remains an ADN inside Taku and DJX remains the content provider.
3. Accept only the versioned server/native protocol and strict native states; bridge telemetry can update the backend but never unlock locally.
4. Delete GroMore/local synthetic reward routes and add a repository static assertion that they cannot return success.
5. Run App tests; commit with `fix(app): remove local reward fallbacks`.

### Task 15: Implement per-session Taku native loading and authoritative show IDs

**Files:**

- Modify: `/Users/neo/Desktop/skit/skit-saas-app/android-djx-runtime/app/src/main/java/top/neoshen/xingheyingguan/TakuRewardedAdController.java`
- Modify: `/Users/neo/Desktop/skit/skit-saas-app/android-djx-runtime/app/src/main/java/top/neoshen/xingheyingguan/SkitTakuAdBridge.java`
- Modify: `/Users/neo/Desktop/skit/skit-saas-app/android-djx-runtime/app/src/main/java/top/neoshen/xingheyingguan/DramaPlayerActivity.java`
- Modify: `/Users/neo/Desktop/skit/skit-saas-app/android-djx-runtime/app/build.gradle`
- Create pure Java protocol/state classes under `android-djx-runtime/app/src/main/java/top/neoshen/xingheyingguan/ad`
- Create tests under `android-djx-runtime/app/src/test/java/top/neoshen/xingheyingguan/ad`

**Steps:**

1. Write failing unit/static tests for one ad instance per session, local-extra-before-load, `showCustomExt(sessionId)`, `getShowId()`, strict state transitions, duplicate/out-of-order callbacks, and absence of preload/auto-preload.
2. For each session create a new Taku ad instance, set `ATAdConst.KEY.USER_ID` and `ATAdConst.KEY.USER_CUSTOM_DATA` before `load()`, and expose `UNINITIALIZED/INITIALIZING/LOADING/LOADED/SHOWING/ERROR` tied to that session/placement.
3. Show with `ATShowConfig.Builder.showCustomExt(sessionId)` and report `ATAdInfo.getShowId()` as `providerShowId` on every related callback.
4. Remove anonymous preload, post-close preload, global loaded state, and cross-session object reuse.
5. Run Android unit tests and assemble debug; commit with `feat(app): bind taku ads to server sessions`.

### Task 16: Remove native fallback rewards and restrict WebView bridge origin

**Files:**

- Modify: `/Users/neo/Desktop/skit/skit-saas-app/nativeplugins/SkitPangleDrama/android/src/main/java/com/skit/nativeplugins/pangle/SkitPangleDramaActivity.java`
- Modify: `/Users/neo/Desktop/skit/skit-saas-app/android-djx-runtime/app/src/main/java/top/neoshen/xingheyingguan/MainActivity.java`
- Modify: `/Users/neo/Desktop/skit/skit-saas-app/android-djx-runtime/app/src/main/java/top/neoshen/xingheyingguan/SkitPangleDramaBridge.java`
- Create: `/Users/neo/Desktop/skit/skit-saas-app/tests/app/native-security-regression.test.mjs`

**Steps:**

1. Write failing static tests that detect `onRewardVerify(true)`, local-unlock sentinel IDs, external URL bridge exposure, permissive `shouldOverrideUrlLoading`, and server secret strings in source/build config.
2. Remove the DJX fallback that calls reward success without authoritative platform proof; native content must wait for backend entitlement.
3. Attach `JavascriptInterface` only for the exact local asset/loopback origin and re-check the current top-level URL on every bridge call.
4. Open every external HTTP/HTTPS top-level navigation in the system browser and keep bridges unavailable there.
5. Run static tests and Android assemble; commit with `fix(app): close native reward and bridge bypasses`.

### Task 17: Sign hot-update manifests and enforce anti-rollback

**Files:**

- Modify: `/Users/neo/Desktop/skit/skit-saas-app/android-djx-runtime/app/src/main/java/top/neoshen/xingheyingguan/SkitRuntimeUpdateBridge.java`
- Modify: `/Users/neo/Desktop/skit/skit-saas-app/android-djx-runtime/app/build.gradle`
- Modify: `/Users/neo/Desktop/skit/skit-saas-app/android-djx-runtime/build-djx-apk.sh`
- Modify: `/Users/neo/Desktop/skit/skit-saas-app/android-djx-runtime/verify-djx-apk.sh`
- Create updater verification classes/tests under `android-djx-runtime/app/src/{main,test}/java/top/neoshen/xingheyingguan/update`

**Steps:**

1. Write failing tests for invalid signature, wrong tenant/application ID, wrong bundle hash, incompatible protocol, downgrade, replay, and missing embedded public key.
2. Verify a signed manifest with an embedded public key before activating a bundle; bind signature input to tenant, application ID, bundle hash, protocol version, and monotonic release number.
3. Persist the highest accepted version in protected app storage and reject rollback even when the URL/hash is otherwise valid.
4. Update build/verification scripts to fail production packaging when tenant build values, release signing, public key, or protocol metadata are missing.
5. Run updater tests and production-style APK verification; commit with `feat(app): verify signed runtime updates`.

### Task 18: Add the management frontend test foundation and tenant scope primitives

**Files:**

- Modify: `/Users/neo/Desktop/skit/skit-saas-frontend/package.json`
- Modify: `/Users/neo/Desktop/skit/skit-saas-frontend/pnpm-lock.yaml`
- Create: `/Users/neo/Desktop/skit/skit-saas-frontend/vitest.config.ts`
- Create: `/Users/neo/Desktop/skit/skit-saas-frontend/test/setup.ts`
- Create shared files under `src/views/skit/shared`: `tenantScope.ts`, `useTenantScope.ts`, `TenantScopeBar.vue`, `MoneyText.vue`, `AsyncState.vue`
- Create: `/Users/neo/Desktop/skit/skit-saas-frontend/test/unit/skit/tenant-scope.spec.ts`

**Steps:**

1. Write failing tests proving tenant-admin target tenant is immutable, super-admin can explicitly select one/all, requests omit unauthorized tenant values, multi-currency is grouped, and errors never become fake zero-success data.
2. Add Vitest, Vue Test Utils, and jsdom plus `test:unit`; configure aliases consistently with Vite.
3. Build a single tenant-scope primitive used by Home, monitoring, agent detail, ledger, and tree pages.
4. Build currency-aware money and explicit loading/empty/error states.
5. Run focused tests; commit with `test(frontend): add tenant scope foundation`.

### Task 19: Replace sample ad records with real monitoring and reconciliation pages

**Files:**

- Create typed API clients under `/Users/neo/Desktop/skit/skit-saas-frontend/src/api/skit/{analytics,adEvent,reconciliation}/index.ts`
- Create components under `/Users/neo/Desktop/skit/skit-saas-frontend/src/views/skit/ad-monitor/`: `index.vue`, `OverviewCards.vue`, `FunnelPanel.vue`, `EventTable.vue`, `EventDetailDrawer.vue`, `ReconciliationTable.vue`
- Modify: `/Users/neo/Desktop/skit/skit-saas-frontend/src/router/modules/remaining.ts`
- Modify: `/Users/neo/Desktop/skit/skit-saas-frontend/src/router/productMenu.ts`
- Modify: `/Users/neo/Desktop/skit/skit-saas-frontend/src/views/skit/admin/pageConfig.ts`
- Create focused tests under `test/unit/skit/ad-monitor`

**Steps:**

1. Write failing tests for tenant scope, server pagination/filters, reward funnel distinctions, callback authentication labels, frozen/reconciled/suspense totals, multi-currency, detail traces, and API failure.
2. Replace the editable generic `ad-record` screen with a dedicated read-only monitoring route under “短剧 SaaS”.
3. Show request/display/client reward/signed reward/skip/failure funnel, platform health, events, callback attempts, reconciliation differences, and freshness time from real APIs.
4. Make all filters server-side and expose no edit/delete controls for immutable data.
5. Run focused tests, product-menu regression, and type check; commit with `feat(frontend): add real ad monitoring`.

### Task 20: Complete agent advertising, commission, ledger, and member-tree workspaces

**Files:**

- Modify: `/Users/neo/Desktop/skit/skit-saas-frontend/src/api/skit/tenant/index.ts`
- Create API clients under `src/api/skit/{commission,ledger,memberTree}/index.ts`
- Modify: `/Users/neo/Desktop/skit/skit-saas-frontend/src/views/skit/tenant/index.vue`
- Modify: `/Users/neo/Desktop/skit/skit-saas-frontend/src/views/skit/tenant/CommissionRuleEditor.vue`
- Modify: `/Users/neo/Desktop/skit/skit-saas-frontend/src/views/skit/tenant/CommissionLedger.vue`
- Modify: `/Users/neo/Desktop/skit/skit-saas-frontend/src/views/skit/tenant/MemberList.vue`
- Create tenant components: `AdAccessEditor.vue`, `AdReadinessChecklist.vue`, `CommissionHistoryDrawer.vue`, `CommissionPreview.vue`, `MembersWorkspace.vue`, `MemberTree.vue`, `MemberContributionDrawer.vue`, `LedgerSummary.vue`
- Create focused tests under `test/unit/skit/tenant`

**Steps:**

1. Write failing tests for write-only secrets, copied tenant-specific callback URLs, readiness gates, arbitrary-level rules, expected-version conflict, explicit agent remainder, ledger buckets, lazy child cursor, ancestor breadcrumb, and tenant scope.
2. Add advertising access/readiness tabs available to a tenant admin only for its own tenant and to super-admin for the selected tenant.
3. Make commission plan the only editable ratio source; include 100-unit preview, history, publish timestamp, optimistic version, and explicit agent retention.
4. Make ledger read-only with frozen/settled/adjustment/reversal/suspense grouping and currency-aware filters.
5. Add lazy-loaded mentor tree and contribution drawer; never load the complete tenant tree in one request.
6. Run focused tests and type check; commit with `feat(frontend): complete tenant revenue workspace`.

### Task 21: Replace Home simulation and remove duplicate commission functionality

**Files:**

- Modify: `/Users/neo/Desktop/skit/skit-saas-frontend/src/views/Home/Index.vue`
- Create: `/Users/neo/Desktop/skit/skit-saas-frontend/src/views/Home/useHomeDashboard.ts`
- Modify: `/Users/neo/Desktop/skit/skit-saas-frontend/src/api/skit/adminRecord/index.ts`
- Modify: `/Users/neo/Desktop/skit/skit-saas-frontend/src/views/skit/admin/AdminTable.vue`
- Modify: `/Users/neo/Desktop/skit/skit-saas-frontend/src/views/skit/admin/pageConfig.ts`
- Modify: `/Users/neo/Desktop/skit/skit-saas-frontend/test/product-menu.test.mjs`
- Create: `/Users/neo/Desktop/skit/skit-saas-frontend/test/unit/skit/home-dashboard.spec.ts`

**Steps:**

1. Write failing tests proving Home uses real APIs, keeps Home plus “短剧 SaaS” top-level menus, omits profit/ROAS without cost data, never fabricates fallback numbers, and does not expose duplicate commission fields/routes.
2. Show real tenant-aware request/reward funnel, frozen estimate, reconciled income, member share, agent retention, currency groups, and `asOf`.
3. Render API failure as an error and genuine empty data as zero with freshness metadata.
4. Delete legacy generic system/Douyin/sample commission fields and routes whose source of truth is now the commission plan.
5. Run unit/menu/type/lint/build checks; commit with `refactor(frontend): remove simulated revenue features`.

### Task 22: Whole-system verification, CI, push, deployment, and controlled rollout

**Files:**

- Modify as needed: backend and frontend `.github/workflows/*.yml`
- Modify as needed: deployment environment examples and operational runbooks
- Create: `docs/runbooks/ad-revenue-rollout.md`

**Steps:**

1. Add CI gates for backend unit plus Failsafe MySQL integration tests, frontend Vitest/menu/type/lint/build tests, App static contract tests, Android unit tests, APK assembly, and secret scans.
2. Run backend `mvn -pl yudao-module-skit -am clean verify` and prove `*MySqlIT` executed; frontend `pnpm test:unit && node --test test/product-menu.test.mjs && pnpm ts:check && pnpm lint && pnpm build:prod`; App `node --test tests/app/*.test.mjs && pnpm check:identity`; Android unit/assemble/verification scripts.
3. Run `git diff --check`, staged secret scans, tenant-boundary searches, fallback-reward searches, and verify the two protected untracked files are untouched and unstaged.
4. Request whole-change security/code review, fix every Critical/Important issue, and re-run all covering tests.
5. Commit and push backend first; monitor Backend CI/CD to success and verify schema/readiness endpoints with all tenants still `OFF`.
6. Push and deploy frontend; verify Home, agent selection, tenant-admin isolation, ad monitoring, rules, ledger, and mentor tree against real APIs.
7. Build one new base APK per tenant profile, verify no server secret/fallback in the APK, distribute only to shadow testers, and keep production enforcement disabled.
8. Configure each tenant's Taku dedicated unlock placement, signed reward/impression callbacks, report access, network capabilities, and revocation of old Taku/Pangle/DJX credentials. These external-console actions are release prerequisites, not code-completion claims.
9. Execute shadow E2E: create session → real/simulated signed callback → entitlement → frozen estimate → report revision → settlement; also test skip, replay, cross-tenant attack, network loss, late callback, old APK, external WebView, and downgrade.
10. Only after real provider checks pass, atomically raise `minNativeVersion` and switch that tenant to `ENFORCED`; otherwise leave it `OFF`/`SHADOW_TEST_USERS` and report the exact external blocker.

## Completion criteria

- A client-only completion or eCPM can never grant content, revenue, or commission.
- One valid signed reward creates at most one grant/current entitlement and one immutable revenue chain within exactly one tenant.
- Every official amount is conserved across viewer, arbitrary ancestors, agent retention, and suspense in integer units.
- Tenant admin cannot read/write another tenant even with forged IDs/headers; super admin can audit all tenants only through explicit guarded scope.
- The App cannot reward locally, reuse an ad across sessions, expose bridges to external pages, accept unsigned hot updates, or let an old client access protected content after enforcement.
- Home and “短剧 SaaS” are the only top-level product menus and display only real, freshness-labelled data.
- Code, CI, deployment, and shadow verification can be completed without provider credentials, but production `ENFORCED` is never claimed until the tenant's Taku signed reward/impression callbacks and reports, new APK, Pangle/DJX content capability, and old Taku/Pangle/DJX credential revocation are verified. Pangle reward S2S is not a phase-one prerequisite because Pangle and every other third-party ADN remain excluded from the unlock traffic group.
