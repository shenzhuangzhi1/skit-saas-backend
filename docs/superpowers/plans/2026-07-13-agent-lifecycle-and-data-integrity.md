# Agent Lifecycle and Data Integrity Implementation Plan

> **For Codex:** Execute continuously with test-driven development, task-scoped review, and final whole-change review. Do not stage `/Users/neo/Desktop/skit/skit-saas-frontend/src/views/skit/admin/AdminTable 2.vue`.

**Goal:** Replace the framework-shaped agent form and partial backend with a phone-bound, standard-package, archive-safe agent lifecycle whose schema upgrades automatically and whose permissions are enforced server-side.

**Architecture:** Keep `system_tenant` as the tenant/status source of truth and `skit_agent` as the global product registry. Split identity and lifecycle commands from ordinary edits, bind the tenant administrator to one globally unique mobile, use a stable package code, and record ordered idempotent schema migrations. Preserve financial/history tables and use archive/restore instead of delete.

**Tech Stack:** Java 17/Spring Boot/MyBatis-Plus/MySQL, Vue 3/TypeScript/Element Plus, JUnit 5/Mockito, pnpm/Vite, GitHub Actions.

## Global constraints

- Only an original platform-tenant `super_admin` may manage agents or global tenant packages.
- Agent administrator `system_users.username` and `system_users.mobile` must equal the agent login mobile.
- App members remain `skit_member` rows and mobile uniqueness remains tenant-scoped.
- The server selects tenant package code `SKIT_AGENT_STANDARD`; the client cannot provide `packageId`.
- Agents are archived/restored, never physically deleted; finance, member, revenue, ad, and release history remains unchanged.
- Secrets never round-trip in read responses or logs; explicit clearing is deliberate.
- `system_tenant.status` controls availability at every login, invite, callback, and update-manifest entry point.
- Existing unrelated work and the untracked `AdminTable 2.vue` are preserved.

### Task 1: Add versioned schema upgrades and stable standard package

**Files:**

- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/schema/SkitSchemaInitializer.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/framework/schema/SkitSchemaInitializerTest.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/agent/SkitAgentDO.java`
- Modify: `yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/dataobject/tenant/TenantPackageDO.java`
- Modify: `yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/dal/mysql/tenant/TenantPackageMapper.java`
- Modify: `yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/tenant/TenantPackageService.java`
- Modify: `yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/tenant/TenantPackageServiceImpl.java`
- Modify: `yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/controller/admin/tenant/TenantPackageController.java`
- Modify: `sql/mysql/skit-saas.sql`
- Modify: `sql/mysql/ruoyi-vue-pro.sql`

**Steps:**

1. Write failing tests for ordered migration execution, already-applied skipping, checksum mismatch failure, and standard package lookup by code.
2. Add `skit_schema_migration` and convert the initializer into ordered idempotent migrations with version, description, checksum, and applied timestamp.
3. Add `system_tenant_package.code`, `skit_agent.archived_time/archived_by`, and generated active identity columns plus unique indexes on active `system_users.username/mobile`.
4. Add preflight duplicate identity checks that fail with actionable row information before creating unique indexes.
5. Seed enabled package `SKIT_AGENT_STANDARD` / `代理商标准套餐` with an empty menu set and expose service lookup by code.
6. Add the same final schema to bootstrap SQL.
7. Require `super_admin` on every tenant-package endpoint, including simple-list.
8. Run schema and tenant-package tests; commit the task.

### Task 2: Rebuild agent create/update/identity/lifecycle services

**Files:**

- Replace: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/vo/SkitAgentSaveReqVO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/vo/SkitAgentCreateReqVO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/vo/SkitAgentUpdateReqVO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/vo/SkitAgentMobileUpdateReqVO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/vo/SkitAgentPasswordResetReqVO.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/SkitAgentController.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/agent/SkitAgentService.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/agent/SkitAgentServiceImpl.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/agent/SkitAgentMapper.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/vo/SkitAgentRespVO.java`
- Modify: `yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/user/AdminUserService.java`
- Modify: `yudao-module-system/src/main/java/cn/iocoder/yudao/module/system/service/user/AdminUserServiceImpl.java`
- Modify: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/agent/SkitAgentServiceImplTest.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/SkitAgentControllerTest.java`

**Steps:**

1. Write failing tests for mobile-derived identity, server-selected package, future expiry/status validation, duplicate global identity, secret preservation, update without password, mobile rebind, password reset, archive, restore, invite rotation, and platform guard.
2. Split request contracts so ordinary update cannot mutate identity or password.
3. On create, normalize mobile, set `username/mobile` to it, derive contact name, fix account count to one, and resolve the standard package by code.
4. Add transactional mobile rebind and password reset methods that update the bound administrator and revoke its tokens.
5. Add transactional archive/restore methods that update tenant availability, administrator status/token state, and archive metadata without deleting any tenant data.
6. Rotate root invitation code with collision checks.
7. Replace in-memory agent pagination with database pagination for registry rows and bounded current-page enrichment.
8. Stop reading `skit_agent.status`; derive status from `system_tenant`.
9. Run focused service/controller tests; commit the task.

### Task 3: Close tenant-business CRUD and lifecycle guards

**Files:**

- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/SkitTenantBusinessController.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/SkitAdAccountService.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/SkitAdAccountServiceImpl.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/member/SkitMemberService.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/member/SkitMemberServiceImpl.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/app/SkitAppReleaseServiceImpl.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/revenue/SkitRevenueServiceImpl.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/enums/ErrorCodeConstants.java`
- Modify: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/SkitTenantBusinessControllerTest.java`
- Modify: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/member/SkitMemberServiceImplTest.java`
- Modify: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/app/SkitAppReleaseServiceImplTest.java`
- Modify: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/revenue/SkitRevenueServiceImplTest.java`

**Steps:**

1. Write failing tests proving archived/expired tenants cannot read invitations, authenticate/refresh, submit revenue callbacks, or obtain App update manifests.
2. Make all target-tenant controller operations validate the tenant before entering its context; retain the original-login-tenant authorization rule.
3. Add server-side conditional validation for Pangle/Taku enabled configurations, maximum lengths, and explicit credential clearing.
4. Add member detail, status update, and password reset endpoints; keep member creation registration-only and keep deletion unavailable.
5. Ensure member disable and password reset revoke member tokens.
6. Remove all remaining availability checks based only on `skit_agent.status`.
7. Run focused controller/member/app/revenue tests; commit the task.

### Task 4: Replace the agent management frontend

**Files:**

- Modify: `/Users/neo/Desktop/skit/skit-saas-frontend/src/api/skit/tenant/index.ts`
- Modify: `/Users/neo/Desktop/skit/skit-saas-frontend/src/views/skit/tenant/AgentForm.vue`
- Modify: `/Users/neo/Desktop/skit/skit-saas-frontend/src/views/skit/tenant/index.vue`
- Modify: `/Users/neo/Desktop/skit/skit-saas-frontend/src/views/skit/tenant/MemberList.vue`
- Create: `/Users/neo/Desktop/skit/skit-saas-frontend/src/views/skit/tenant/AgentMobileForm.vue`
- Create: `/Users/neo/Desktop/skit/skit-saas-frontend/src/views/skit/tenant/AgentPasswordForm.vue`

**Steps:**

1. Replace the API types with exact create, update, identity, password, lifecycle, invitation, ad, and member contracts.
2. Remove package/contact/username/account-count fields and package API loading.
3. Build core create/edit fields, future-date handling, explicit provider enable switches, and conditional validation; never populate secret inputs from responses.
4. Add dedicated mobile rebind and password reset dialogs.
5. Add archive/restore and root-invite rotation actions with destructive confirmations and automatic list/detail refresh.
6. Add member detail, enable/disable, and reset-password actions without create/delete buttons.
7. Run formatter/lint on changed files, production build, and ensure the known untracked duplicate is untouched; commit the task.

### Task 5: Security configuration, integration verification, and release

**Files:**

- Modify only if needed: `yudao-server/src/main/resources/application.yaml`
- Modify only if needed: `deploy/.env.example`
- Modify only if needed: `.github/workflows/*.yml`

**Steps:**

1. Remove any usable default advertising encryption key and require environment injection in production while preserving a clearly non-production local/test value.
2. Run staged secret-pattern scans and `git diff --check` in both repositories.
3. Run all available backend focused tests/build and frontend changed-file lint/production build; document any unrelated baseline failures separately.
4. Request whole-change code review, fix every Critical/Important finding, and re-run covering verification.
5. Push backend first, monitor Backend CI/CD to success, then push frontend and monitor Frontend CI/CD.
6. Verify deployed login, agent create/update/mobile reset/password reset/archive/restore, tenant isolation, invite registration, credential non-disclosure, and archived tenant denial through browser/API.
