# Platform Admin and Agent Tenant Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put agent creation under user management while enforcing platform-admin global access and agent-admin single-tenant access.

**Architecture:** The backend remains the authority for cross-tenant access through `SkitPlatformAdminGuard`; frontend roles only control affordances. `TenantVisit` is removed so no browser-side tenant override exists for agent sessions.

**Tech Stack:** Spring Boot, Spring Security, MyBatis tenant context, Vue 3, Element Plus, TypeScript.

## Global Constraints

- `super_admin` is the platform admin role; `tenant_admin` is agent-bound.
- Only `super_admin` may create or globally manage agents.
- A tenant administrator may not select, query, or mutate another tenant through `tenantId` or a visit-tenant header.
- Do not stage `src/views/skit/admin/AdminTable 2.vue`.

---

### Task 1: Remove global tenant switching and relocate agent creation

**Files:**
- Modify: `skit-saas-frontend/src/layout/components/ToolHeader.vue`
- Modify: `skit-saas-frontend/src/views/system/user/index.vue`
- Modify: `skit-saas-frontend/src/views/skit/tenant/index.vue`

**Interfaces:**
- Consumes `AgentForm.open('create')`.
- Produces a `super_admin`-only user-management entry point and no `TenantVisit` render path.

- [ ] Write a frontend source assertion that `ToolHeader.vue` no longer imports or renders `TenantVisit`.
- [ ] Remove `TenantVisit` import, permission computation, and JSX branch from `ToolHeader.vue`.
- [ ] Add a `super_admin`-guarded “新增代理商” button to `system/user/index.vue`; mount the existing `AgentForm` and call `open('create')`.
- [ ] Remove the duplicate create button from the agent tenant page while retaining the global list for `super_admin`.
- [ ] Run Prettier and `pnpm build:prod`; commit with `feat: manage agents from user administration`.

### Task 2: Enforce tenant-bound data access on the server

**Files:**
- Modify: `skit-saas-backend/yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/SkitTenantBusinessController.java`
- Modify or create test: `skit-saas-backend/yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/SkitTenantBusinessControllerTest.java`

**Interfaces:**
- Consumes original `LoginUser.tenantId` and `SkitPlatformAdminGuard.check()`.
- Produces `resolveTargetTenant(requestedTenantId)` that allows cross-tenant access only to platform administrators.

- [ ] Write a failing test in which a `tenant_admin` requests another tenant and receives `PLATFORM_ADMIN_REQUIRED` before a service call.
- [ ] Keep the original-login-tenant comparison in `resolveTargetTenant`; clear or reject visit-tenant requests for non-platform administrators before `TenantUtils.execute`.
- [ ] Test platform-admin cross-tenant access and same-tenant agent-admin access.
- [ ] Run the focused Maven test plus `deploy/test-compose-topology.sh`; commit with `fix: enforce agent tenant data boundaries`.

### Task 3: Restrict the framework tenant-visit override

**Files:**
- Modify: `yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/config/TenantProperties.java`
- Modify: `yudao-framework/yudao-spring-boot-starter-biz-tenant/src/main/java/cn/iocoder/yudao/framework/tenant/core/web/TenantVisitContextInterceptor.java`
- Test: `yudao-framework/yudao-spring-boot-starter-biz-tenant/src/test/java/cn/iocoder/yudao/framework/tenant/core/web/TenantVisitContextInterceptorTest.java`

**Interfaces:**
- Consumes the original `LoginUser.tenantId`, `TenantProperties.platformTenantId`, and `super_admin` role.
- Produces a tenant-visit override available only to the system-tenant platform administrator.

- [ ] Add a failing interceptor test for a tenant administrator whose request has `visit-tenant-id` and an old visit permission; it must receive `FORBIDDEN`.
- [ ] Add a passing test for system tenant `1` with `super_admin` role visiting tenant `42`.
- [ ] Add `platformTenantId` to `TenantProperties`, defaulting to `1`, so nonstandard deployments can override it.
- [ ] Replace the menu-permission-only check with the conjunction of original system tenant and `super_admin` role.
- [ ] Run `mvn -pl yudao-framework/yudao-spring-boot-starter-biz-tenant -am -Dtest=TenantVisitContextInterceptorTest -Dsurefire.failIfNoSpecifiedTests=false test`; commit with `fix: restrict tenant visit to platform admins`.

### Task 4: Verify roles and navigation end-to-end

**Files:**
- Modify if needed: `skit-saas-frontend/src/router/modules/remaining.ts`
- Modify if needed: `skit-saas-frontend/src/views/skit/tenant/index.vue`

**Interfaces:**
- Consumes `useUserStore().getRoles`.
- Produces visible global agent operations only to `super_admin` and tenant-bound operations to `tenant_admin`.

- [ ] Verify the route exposes the tenant workspace to both roles, but its global controls render only for `super_admin`.
- [ ] Run `pnpm ts:check` and report pre-existing errors separately from this change.
- [ ] Push both repositories after verifying clean staging and preserving the known untracked admin table file.
