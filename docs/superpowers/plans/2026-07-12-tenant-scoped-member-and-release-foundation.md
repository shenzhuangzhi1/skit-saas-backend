# Tenant-Scoped Member Identity and App Release Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make member identity tenant-scoped with invisible agent-app context, then add the release-profile and update-manifest foundation for simple SaaS and app updates.

**Architecture:** A Redis-backed opaque context token resolves one agent tenant before member registration or login. Member mobiles are unique only in that tenant. App release profiles hold public delivery metadata; native Pangle credentials remain external to Git and ordinary business updates use a compatible hot-update bundle.

**Tech Stack:** Java 8, Spring Boot, Redis, MySQL 8, MyBatis, Vue 3/uni-app, GitHub Actions.

## Global Constraints

- Member uniqueness is `(tenant_id, mobile)`; cross-tenant duplicate phones are valid.
- The invitation tenant and verified app-context tenant must match.
- User-facing screens never show tenant name or agent code.
- Management-console user authentication stays tenant-bound without a tenant input.
- Pangle settings, Taku credentials, package signing material, and private artifact URLs never enter Git or public manifests.
- Ordinary SaaS releases update backend/frontend once; native builds run only for selected agent profiles.
- Preserve `src/views/skit/admin/AdminTable 2.vue` as untracked.

---

### Task 1: Restore tenant-scoped mobile uniqueness

**Files:**
- Modify: `sql/mysql/skit-saas.sql`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/schema/SkitSchemaInitializer.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/member/SkitMemberServiceImpl.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/member/SkitMemberServiceImplTest.java`

**Interfaces:**
- Consumes: `SkitMemberMapper.selectByMobile(String)` while `TenantUtils.execute(tenantId, ...)` is active.
- Produces: `uk_skit_member_tenant_mobile (tenant_id, mobile)`.

- [ ] **Step 1: Write the failing registration regression test**

```java
@Test
void registerAllowsMobileUsedByAnotherTenant() {
    when(memberMapper.selectListByMobile("13800000004"))
            .thenReturn(Collections.singletonList(enabledMember(99L, 43L, 0, "OTHER99")));
    when(memberMapper.selectByMobile("13800000004")).thenReturn(null);
    SkitMemberService.AuthResult result = memberService.register(commandFor("ROOT42", "13800000004"));
    assertEquals(TENANT_ID, result.getTenantId());
    verify(memberMapper).insert(any(SkitMemberDO.class));
}
```

- [ ] **Step 2: Run it red**

```bash
docker run --rm -v "$PWD:/workspace" -w /workspace -v "$HOME/.m2:/root/.m2" maven:3.9-eclipse-temurin-8 \
  mvn -pl yudao-module-skit -am -Dtest=SkitMemberServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: failure with `MEMBER_MOBILE_EXISTS` from the global check.

- [ ] **Step 3: Replace the global invariant**

Remove the global `selectListByMobile` registration check. Keep the tenant-scoped check inside `inTenant`:

```java
return inTenant(invitation.tenantId, () -> {
    if (memberMapper.selectByMobile(command.getMobile()) != null) {
        throw exception(MEMBER_MOBILE_EXISTS);
    }
    // insert member, create closure rows, then create the member token
});
```

Restore the SQL key:

```sql
UNIQUE KEY `uk_skit_member_tenant_mobile` (`tenant_id`,`mobile`)
```

Add `dropIndexIfExists("skit_member", "uk_skit_member_mobile")` before `addIndexIfMissing("skit_member", "uk_skit_member_tenant_mobile", "`tenant_id`,`mobile`", true)`. `dropIndexIfExists` must query `information_schema.STATISTICS` before executing `ALTER TABLE ... DROP INDEX`.

- [ ] **Step 4: Run it green and commit**

Run the Task 1 command; expected all member-service tests pass. Then:

```bash
git add sql/mysql/skit-saas.sql yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/schema/SkitSchemaInitializer.java yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/member/SkitMemberServiceImpl.java yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/member/SkitMemberServiceImplTest.java
git commit -m "fix: scope member mobiles to agent tenants"
```

### Task 2: Bind member auth to an opaque app-context token

**Files:**
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/member/SkitMemberAppContextService.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/member/SkitMemberAppContextServiceImpl.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/app/member/SkitMemberAuthController.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/member/SkitMemberService.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/member/SkitMemberServiceImpl.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/enums/ErrorCodeConstants.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/member/SkitMemberAppContextServiceImplTest.java`

**Interfaces:**
- Consumes: enabled `SkitAgentDO` selected by embedded agent code.
- Produces: `issue(String agentCode): AppContext` and `requireTenantId(String token): Long`.

- [ ] **Step 1: Write failing service tests**

```java
@Test
void issueAndRequireTenantIdRoundTrip() {
    when(agentMapper.selectByTenantCode("AGENT42")).thenReturn(enabledAgent());
    String token = contextService.issue("agent42").getToken();
    assertEquals(42L, contextService.requireTenantId(token));
}

@Test
void requireTenantIdRejectsUnknownToken() {
    assertServiceException(() -> contextService.requireTenantId("unknown"), MEMBER_APP_CONTEXT_INVALID);
}
```

- [ ] **Step 2: Implement the Redis opaque-token service**

```java
private static final String CACHE_KEY_PREFIX = "skit:member:app-context:";
private static final Duration TTL = Duration.ofMinutes(30);

public AppContext issue(String agentCode) {
    SkitAgentDO agent = requireEnabledAgent(agentCode);
    String token = IdUtil.fastSimpleUUID();
    stringRedisTemplate.opsForValue().set(CACHE_KEY_PREFIX + token, agent.getTenantId().toString(), TTL);
    return new AppContext(token, agent.getTenantId(), Instant.now().plus(TTL));
}

public Long requireTenantId(String token) {
    String tenantId = stringRedisTemplate.opsForValue().get(CACHE_KEY_PREFIX + token);
    if (StrUtil.isBlank(tenantId)) throw exception(MEMBER_APP_CONTEXT_INVALID);
    return Long.valueOf(tenantId);
}
```

`MEMBER_APP_CONTEXT_INVALID` must say only `请从代理商 App 入口进入`.

- [ ] **Step 3: Add bootstrap, registration, and login contracts**

Add public `POST /skit/member/auth/bootstrap` accepting `{agentCode}` and returning the `AppContext`. Add required `contextToken` to `RegisterReqVO` and `LoginReqVO`; the controller assigns the verified tenant to each service command:

```java
command.setTenantId(appContextService.requireTenantId(reqVO.getContextToken()));
```

Add `tenantId` to `RegisterCommand` and `LoginCommand`. Registration asserts `Objects.equals(invitation.tenantId, command.getTenantId())`; login executes only in `command.getTenantId()` and queries `memberMapper.selectByMobile(mobile)` there.

- [ ] **Step 4: Add regression tests, run, and commit**

Test context/invitation mismatch, invalid context before member lookup, and two tenant contexts logging in the same mobile as different members. Run the Task 1 Maven command plus `SkitMemberAppContextServiceImplTest`. Then:

```bash
git add yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/member
git commit -m "feat: bind member auth to agent app context"
```

### Task 3: Obtain app context invisibly in uni-app

**Files:**
- Create: `skit-saas-app/sheep/services/member-app-context.js`
- Modify: `skit-saas-app/sheep/api/member/auth.js`
- Modify: `skit-saas-app/pages/auth/index.vue`
- Modify: `skit-saas-app/sheep/store/user.js`
- Test: `skit-saas-app/sheep/services/member-app-context.test.js`

**Interfaces:**
- Consumes: `VITE_SKIT_AGENT_CODE` first, agent deep-link code second.
- Produces: `ensureMemberAppContext(agentCode): Promise<string>`.

- [ ] **Step 1: Write the failing context cache test**

```js
it('reuses a non-expired token for the same agent', async () => {
  storage.set('skit-member-app-context', { agentCode: 'AGENT42', token: 't1', expiresTime: future });
  await expect(ensureMemberAppContext('AGENT42')).resolves.toBe('t1');
  expect(request).not.toHaveBeenCalled();
});
```

- [ ] **Step 2: Implement context acquisition**

```js
export async function ensureMemberAppContext(agentCode) {
  const code = String(agentCode || '').trim().toUpperCase();
  if (!code) throw new Error('请从代理商 App 或邀请链接进入');
  const cached = safeUni.getStorageSync(CONTEXT_KEY);
  if (cached?.agentCode === code && cached.expiresTime > Date.now() + 60_000) return cached.token;
  const result = await AuthUtil.bootstrap({ agentCode: code });
  if (result?.code !== 0) throw new Error(result?.msg || '代理商入口不可用');
  safeUni.setStorageSync(CONTEXT_KEY, { agentCode: code, token: result.data.token, expiresTime: new Date(result.data.expiresTime).getTime() });
  return result.data.token;
}
```

- [ ] **Step 3: Send the token without rendering a selector**

```js
const contextToken = await ensureMemberAppContext(resolveAgentCode());
await AuthUtil.login({ mobile, password, contextToken });
```

Use the same token for registration. Keep invitation validation visible, but reject an invitation whose returned `tenantCode` differs from the bound agent code.

- [ ] **Step 4: Verify and commit**

```bash
npx prettier --check pages/auth/index.vue sheep/api/member/auth.js sheep/services/member-app-context.js
node -e "for (const f of ['package.json','manifest.json','pages.json']) JSON.parse(require('fs').readFileSync(f));"
git add pages/auth/index.vue sheep/api/member/auth.js sheep/services/member-app-context.js sheep/store/user.js
git commit -m "feat: derive member tenant from agent app context"
```

### Task 4: Add release-profile and update-manifest foundation

**Files:**
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/app/SkitAppReleaseProfileDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/app/SkitAppReleaseProfileMapper.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/app/SkitAppReleaseService.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/app/SkitAppReleaseServiceImpl.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/app/release/SkitAppReleaseController.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/schema/SkitSchemaInitializer.java`
- Modify: `sql/mysql/skit-saas.sql`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/app/SkitAppReleaseServiceImplTest.java`

**Interfaces:**
- Consumes: enabled agent and profile code.
- Produces: `GET /skit/app/release/current?profileCode={code}&nativeVersion={version}`.

- [ ] **Step 1: Write the failing compatible-manifest test**

```java
@Test
void currentManifestReturnsCompatibleStableBundle() {
    when(profileMapper.selectByProfileCode("AGENT42")).thenReturn(enabledProfile(42L, "2.3.0", "2.1.0"));
    assertEquals("2.3.0", releaseService.current("AGENT42", "2.2.0").getHotVersion());
}
```

- [ ] **Step 2: Create the tenant-bound profile schema**

Create one profile per tenant with `tenant_id`, `profile_code`, `channel`, `min_native_version`, `hot_version`, `hot_bundle_url`, `hot_bundle_sha256`, `native_version`, `native_package`, and `status`. Do not put Pangle setting files, Taku secrets, or signing material in this table's public response.

- [ ] **Step 3: Select only compatible manifests**

Return `updateAvailable=false` for a disabled profile, empty hot bundle, or native version below `min_native_version`; otherwise return only `hotVersion`, `bundleUrl`, `sha256`, and `minNativeVersion`.

- [ ] **Step 4: Test, verify, and commit**

```bash
docker run --rm -v "$PWD:/workspace" -w /workspace -v "$HOME/.m2:/root/.m2" maven:3.9-eclipse-temurin-8 \
  mvn -pl yudao-module-skit -am -Dtest=SkitAppReleaseServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test
git add sql/mysql/skit-saas.sql yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/app
git commit -m "feat: add agent app release manifests"
```

### Task 5: Consume the hot-update manifest and automate release metadata

**Files:**
- Create: `skit-saas-app/sheep/services/app-update.js`
- Modify: `skit-saas-app/App.vue`
- Modify: `skit-saas-app/.github/workflows/cicd.yml`
- Create: `skit-saas-app/script/validate-release-profile.mjs`
- Create: `skit-saas-app/docs/release-profiles.md`

**Interfaces:**
- Consumes: current release manifest, bound profile code, and external CI secret references.
- Produces: `checkAndInstallUpdate()` and workflow inputs `profile_code`, `release_kind`, `channel`.

- [ ] **Step 1: Write a failing bundle verification test**

```js
it('rejects a downloaded bundle with a mismatched sha256', async () => {
  await expect(verifyBundle(downloadedPath, 'expected-hash')).rejects.toThrow('更新包校验失败');
});
```

- [ ] **Step 2: Implement safe update behavior**

On native App only, fetch the manifest, download to a staging path, verify SHA-256, call `plus.runtime.install`, and restart only in its success callback. H5 and incompatible runtimes return `{ skipped: true }`; failures leave the current bundle active.

- [ ] **Step 3: Validate CI release metadata**

Reject metadata containing `pangleSettingFile`, `appSecret`, `privateKey`, `takuSecret`, or a private artifact URL. Accept only profile code, package name, channel, public artifact URL, SHA-256, and minimum native version.

- [ ] **Step 4: Add workflow dispatch and document native exception**

The `hot` path publishes a shared hashed bundle. The `native` path must require external builder configuration and fail explicitly if it is missing; it must not claim to build an APK. Document that Pangle package/license updates rebuild only selected profiles.

- [ ] **Step 5: Run checks and commit**

```bash
npx prettier --check App.vue sheep/services/app-update.js sheep/api
node script/validate-release-profile.mjs --fixture script/fixtures/release-profile-valid.json
git add App.vue sheep/services/app-update.js .github/workflows/cicd.yml script docs
git commit -m "feat: prepare profile-driven app updates"
```

### Task 6: End-to-end verification and deployment

**Files:** none unless verification exposes a defect.

- [ ] **Step 1: Run backend checks**

```bash
docker run --rm -v "$PWD:/workspace" -w /workspace -v "$HOME/.m2:/root/.m2" maven:3.9-eclipse-temurin-8 \
  mvn -pl yudao-module-skit,yudao-module-system -am test
docker run --rm -v "$PWD:/workspace" -w /workspace -v "$HOME/.m2:/root/.m2" maven:3.9-eclipse-temurin-8 \
  mvn -pl yudao-server -am -DskipTests package
```

- [ ] **Step 2: Run frontend and app checks**

```bash
cd /Users/neo/Desktop/skit/skit-saas-frontend && pnpm build:prod && bash deploy/test-activate-frontend.sh
cd /Users/neo/Desktop/skit/skit-saas-app && npx prettier --check App.vue pages/auth/index.vue sheep/api sheep/services
```

- [ ] **Step 3: Push and verify**

Push backend, then management frontend, then app. Verify one mobile can register in two tenants through two valid app contexts, a missing context fails, no management login tenant input exists, and no generated SDK settings or `AdminTable 2.vue` are staged.
