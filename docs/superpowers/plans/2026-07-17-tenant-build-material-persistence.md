# Tenant Build Material Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist each tenant's native APK build materials as versioned, encrypted database records and expose safe SaaS management APIs/UI without implementing automated APK packaging in this phase.

**Architecture:** Reuse the existing tenant-scoped ad account, app release profile, management audit, and AES-GCM credential services. Add one versioned `skit_app_build_material` table containing non-secret build metadata plus one encrypted JSON envelope for Pangle settings and signing inputs; expose only redacted configuration status to the browser. The existing local Mac APK scripts remain unchanged and GitHub/Ubuntu build orchestration is explicitly deferred.

**Tech Stack:** Spring Boot 2.x, MyBatis-Plus, MySQL schema initializer, AES-GCM credential crypto, Element Plus/Vue 3/TypeScript, JUnit 5, Vitest.

## Global Constraints

- Database is the only source of truth for tenant build material; every table key and query is tenant-scoped.
- `skit_ad_account` remains the source for Taku client App Key/App ID/placement and Pangle account fields; do not copy those credentials into the new table.
- Sensitive material is encrypted with `SkitAdCredentialCryptoService`; API responses never contain plaintext or ciphertext.
- Secret inputs are write-only; blank secret fields preserve the active value, while the UI clears them after save.
- `super_admin` is the only role allowed to view or change signing/build material; `tenant_admin` keeps existing business permissions but cannot read signing secrets.
- Saving material creates a new immutable `material_version`; prior versions remain auditable and are never overwritten.
- Ad playback, callback verification, reconciliation, and commission allocation must not create or update build-material records.
- Automated APK packaging, GitHub OIDC, Ubuntu workers, build jobs, and artifact storage are out of scope for this plan.

---

## Task 1: Add encrypted tenant build-material schema and crypto context

**Files:**
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/crypto/SkitAdCredentialCryptoService.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/crypto/SkitAesGcmCredentialCryptoService.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/schema/SkitSchemaInitializer.java`
- Modify: `yudao-module-skit/sql/mysql/skit-saas.sql`
- Modify: `yudao-module-skit/sql/mysql/ruoyi-vue-pro.sql`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/app/SkitAppBuildMaterialDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/app/SkitAppBuildMaterialMapper.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/framework/schema/SkitTenantBuildMaterialSchemaContractTest.java`
- Modify: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/framework/schema/SkitSchemaInitializerTest.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/framework/crypto/SkitAdCredentialCryptoServiceTest.java`

**Interfaces:**
- Produces `SkitAdCredentialCryptoService.Context.appBuildMaterial(long tenantId, int materialVersion, int envelopeVersion)` for the service layer.
- Produces mapper methods `selectActive(Long tenantId)`, `selectLatestForUpdate(Long tenantId)`, `selectMaxVersion(Long tenantId)`, `insert(SkitAppBuildMaterialDO)`, and `retireActive(Long tenantId, Long id)`.

- [ ] **Step 1: Write the crypto-context test.** Verify that encrypting the same plaintext with `Context.appBuildMaterial(10, 1, 1)` decrypts successfully, while tenant `11` or material version `2` fails authentication. Also verify existing `rewardSecret` contexts still decrypt the existing fixture, proving AAD compatibility.

- [ ] **Step 2: Run the focused crypto test and confirm the new context fails before implementation.**

  Run: `mvn -pl yudao-module-skit -Dtest=SkitAdCredentialCryptoServiceTest test`

  Expected: FAIL because `appBuildMaterial` does not yet exist.

- [ ] **Step 3: Add the new purpose without changing legacy AAD bytes.** Add `APP_BUILD_MATERIAL` and a factory that binds `tenantId`, `materialVersion`, and `envelopeVersion`; keep the existing reward/publisher context constructors and AAD branches byte-for-byte compatible. In the AES-GCM implementation, encode the material version in the non-callback AAD branch only for the new purpose, and keep existing reward-secret AAD unchanged.

- [ ] **Step 4: Add the tenant-scoped DO and mapper.** The DO must extend `TenantBaseDO`, mark ciphertext/nonce with `@JsonIgnore` and `@ToString.Exclude`, and contain only non-secret fields plus the encrypted JSON envelope metadata. Mapper SQL must include `tenant_id` in every predicate and update.

- [ ] **Step 5: Add migration version `2026071701`.** Register a schema-initializer step that creates:

  ```sql
  CREATE TABLE IF NOT EXISTS `skit_app_build_material` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `tenant_id` bigint NOT NULL,
    `material_version` int NOT NULL,
    `api_base_url` varchar(512) NOT NULL DEFAULT '',
    `app_name` varchar(128) NOT NULL DEFAULT '',
    `native_version_code` bigint NOT NULL DEFAULT 1,
    `native_version_name` varchar(64) NOT NULL DEFAULT '',
    `runtime_release_no` bigint NOT NULL DEFAULT 1,
    `secret_ciphertext` mediumblob DEFAULT NULL,
    `secret_nonce` binary(12) DEFAULT NULL,
    `encryption_key_id` varchar(64) DEFAULT NULL,
    `envelope_version` smallint DEFAULT NULL,
    `active` bit(1) NOT NULL DEFAULT b'1',
    `active_tenant_id` bigint GENERATED ALWAYS AS
      (CASE WHEN `active`=b'1' AND `deleted`=b'0' THEN `tenant_id` ELSE NULL END) STORED,
    `verified_at` datetime DEFAULT NULL,
    `creator` varchar(64) DEFAULT '',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updater` varchar(64) DEFAULT '',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted` bit(1) NOT NULL DEFAULT b'0',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_skit_app_build_material_tenant_id` (`tenant_id`,`id`),
    UNIQUE KEY `uk_skit_app_build_material_version` (`tenant_id`,`material_version`),
    UNIQUE KEY `uk_skit_app_build_material_active` (`active_tenant_id`),
    CONSTRAINT `ck_skit_app_build_material_version` CHECK (`material_version` > 0),
    CONSTRAINT `ck_skit_app_build_material_version_code` CHECK
      (`native_version_code` BETWEEN 1 AND 2100000000),
    CONSTRAINT `ck_skit_app_build_material_release_no` CHECK (`runtime_release_no` > 0),
    CONSTRAINT `ck_skit_app_build_material_secret` CHECK
      ((`secret_ciphertext` IS NULL AND `secret_nonce` IS NULL AND
        `encryption_key_id` IS NULL AND `envelope_version` IS NULL) OR
       (`secret_ciphertext` IS NOT NULL AND `secret_nonce` IS NOT NULL AND
        `encryption_key_id` IS NOT NULL AND `envelope_version` > 0))
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  ```

  Register the version in the initializer's ordered migration list and add a contract assertion for the tenant/version/active uniqueness and secret envelope checks.

  Add the same `skit_app_build_material` table to both MySQL bootstrap scripts so a clean database and an upgraded database converge. Include the table in the initializer's tenant-owner validation list, and extend the migration checksum expectations with the new additive version without changing any released checksum.

- [ ] **Step 6: Run schema contract and crypto tests.**

  Run: `mvn -pl yudao-module-skit -Dtest=SkitTenantBuildMaterialSchemaContractTest,SkitAdCredentialCryptoServiceTest test`

  Expected: PASS, with no changes to the existing ad schema signature or reward-secret compatibility tests.

- [ ] **Step 7: Commit the schema/crypto unit.**

  ```bash
  git add yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/crypto \
    yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/schema \
    yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/app \
    yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/app \
    yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/framework
  git commit -m "feat(skit): add tenant build material schema"
  ```

## Task 2: Implement tenant build-material versioning and readiness service

**Files:**
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/app/SkitAppBuildMaterialService.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/app/SkitAppBuildMaterialServiceImpl.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/enums/ErrorCodeConstants.java`
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/app/SkitAppBuildMaterialServiceImplTest.java`

**Interfaces:**
- `SkitAppBuildMaterialService.getMaterial(Long tenantId)` returns a redacted `MaterialView`.
- `SkitAppBuildMaterialService.saveMaterial(MaterialCommand command)` returns the new `MaterialView`.
- `MaterialCommand` accepts `tenantId`, `apiBaseUrl`, `appName`, `nativeVersionCode`, `nativeVersionName`, `runtimeReleaseNo`, `pangleSettingsJson`, `releaseKeystoreBase64`, `storePassword`, `keyAlias`, `keyPassword`, and `reason`.
- `MaterialView` returns `tenantId`, `materialVersion`, non-secret metadata, `pangleSettingsConfigured`, `signingConfigured`, `takuAppKeyConfigured`, `takuAccountConfigured`, `appReleaseProfileConfigured`, and `verifiedAt`; it has no secret fields.

- [ ] **Step 1: Write service tests for the missing behaviors.** Cover: first save creates version `1`; second save deactivates version `1` and creates version `2`; blank write-only fields preserve the active encrypted bundle; a different tenant cannot load or retire the row; invalid HTTP URL, malformed Pangle JSON, missing signing tuple, non-monotonic runtime release, and missing Taku App Key reject; returned `MaterialView` contains only booleans for sensitive material.

- [ ] **Step 2: Run the service tests before implementation.**

  Run: `mvn -pl yudao-module-skit -Dtest=SkitAppBuildMaterialServiceImplTest test`

  Expected: FAIL because the service contract and implementation are absent.

- [ ] **Step 3: Implement the immutable version flow.** In one transaction, lock the latest row, compute `nextVersion = max + 1`, verify the tenant's existing `SkitAppReleaseProfileDO` and `SkitAdAccountService.Settings`, merge any blank secret command fields with the decrypted active JSON bundle, serialize the bundle with a fixed Jackson object shape, encrypt it using `Context.appBuildMaterial(tenantId, nextVersion, CURRENT_ENVELOPE_VERSION)`, retire the old active row, and insert the new row. Never log the bundle or decrypted values.

- [ ] **Step 4: Implement validation and readiness.** Require an `https://` API URL, nonblank app name, `nativeVersionCode` in `1..2100000000`, a dotted native version name, and a strictly increasing `runtimeReleaseNo`. Parse the supplied Pangle settings JSON and require `init.site_id`, `init.app_id`, and a license entry matching the existing release profile's `nativePackage`; require all signing fields together. Reuse `SkitAdAccountService.getSettings()` to report Taku/Pangle configuration and do not copy either App Key or server secret into the new table.

- [ ] **Step 5: Add the domain error code and redacted canonical helpers.** Use a dedicated `APP_BUILD_MATERIAL_INVALID` error code. The service's audit canonical string must contain version, metadata, and configured flags only; secret values and ciphertext are excluded.

- [ ] **Step 6: Run the focused service tests.**

  Run: `mvn -pl yudao-module-skit -Dtest=SkitAppBuildMaterialServiceImplTest test`

  Expected: PASS with tenant isolation, versioning, preservation, and redaction assertions.

- [ ] **Step 7: Commit the service unit.**

  ```bash
  git add yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/app \
    yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/enums/ErrorCodeConstants.java \
    yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/app/SkitAppBuildMaterialServiceImplTest.java
  git commit -m "feat(skit): persist tenant build material versions"
  ```

## Task 3: Expose redacted tenant-scoped management APIs

**Files:**
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/vo/SkitAppBuildMaterialRespVO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/vo/SkitAppBuildMaterialSaveReqVO.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/SkitTenantBusinessController.java`
- Modify: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/SkitTenantBusinessControllerTest.java`

**Interfaces:**
- `GET /skit/tenant/app-build/material?tenantId={id}` returns `SkitAppBuildMaterialRespVO`.
- `PUT /skit/tenant/app-build/material` accepts `SkitAppBuildMaterialSaveReqVO` and returns the redacted response.

- [ ] **Step 1: Add controller contract tests.** Assert that super admin can read/write a selected tenant through `adminTenantScopeGuard`, cross-tenant targets are rejected, tenant admin receives 403 for signing-material endpoints, and serialized responses do not contain `pangleSettingsJson`, keystore, passwords, ciphertext, nonce, or key id.

- [ ] **Step 2: Run the controller tests before adding endpoints.**

  Run: `mvn -pl yudao-module-skit -Dtest=SkitTenantBusinessControllerTest test`

  Expected: FAIL for the missing endpoint contract.

- [ ] **Step 3: Add validated request/response VOs.** Mark secret request fields write-only and exclude them from `toString`; require a 10–500 character reason, `tenantId`, HTTPS URL, positive versions, and bounded lengths. Response fields contain only configuration booleans and `materialVersion`.

- [ ] **Step 4: Add guarded endpoints beside the existing App Release endpoints.** Use `@PreAuthorize("@ss.hasRole('super_admin')")`, `adminTenantScopeGuard.readTenant/writeTenant`, and `managementCommandExecutor` with command type `APP_BUILD_MATERIAL_UPDATE`. Sanitize secret field names in `@ApiAccessLog`; pass only the service canonical before/after values to audit.

- [ ] **Step 5: Run the controller tests and the existing app-release tests.**

  Run: `mvn -pl yudao-module-skit -Dtest=SkitTenantBusinessControllerTest,SkitAppReleaseServiceImplTest test`

  Expected: PASS, with existing hot-update behavior unchanged.

- [ ] **Step 6: Commit the API unit.**

  ```bash
  git add yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/tenant \
    yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/SkitTenantBusinessControllerTest.java
  git commit -m "feat(skit): expose tenant build material api"
  ```

## Task 4: Add the SaaS build-material editor without a fake build button

**Files:**
- Modify: `skit-saas-frontend/src/api/skit/tenant/index.ts`
- Create: `skit-saas-frontend/src/views/skit/tenant/AppBuildMaterialEditor.vue`
- Modify: `skit-saas-frontend/src/views/skit/tenant/index.vue`
- Create: `skit-saas-frontend/test/unit/skit/tenant/app-build-material.spec.ts`

**Interfaces:**
- API types `TenantAppBuildMaterialVO` and `TenantAppBuildMaterialUpdateReqVO` mirror the backend redacted response/write-only request.
- `getTenantAppBuildMaterial(tenantId)` calls `GET /skit/tenant/app-build/material`.
- `updateTenantAppBuildMaterial(data)` calls `PUT /skit/tenant/app-build/material`.

- [ ] **Step 1: Write frontend tests for redaction and user flow.** Assert that the editor renders “已配置” flags without rendering secret values, clears secret inputs after a successful save, requires a reason of 10–500 characters, and displays the notice “APK 暂由本地 Mac 构建，本页面只保存租户构建资料”。

- [ ] **Step 2: Run the new frontend test before implementation.**

  Run: `pnpm exec vitest run test/unit/skit/tenant/app-build-material.spec.ts`

  Expected: FAIL because the editor and API types are absent.

- [ ] **Step 3: Add API types and calls.** Keep request secret fields as write-only strings; response types contain only `materialVersion`, metadata, configured flags, and `verifiedAt`. Do not add an endpoint that returns the encrypted bundle or decrypted Taku App Key.

- [ ] **Step 4: Implement the editor.** Add metadata fields for API URL, app name, native version code/name, and runtime release number; add file/text inputs for Pangle settings JSON and keystore Base64 plus password inputs. Display existing status using flags, never bind an existing secret into an input. Submit normalized values and reason, then reset every secret input after success.

- [ ] **Step 5: Mount the editor in the existing super-admin “App 发布” tab.** Keep `AppReleaseEditor.vue` for hot-update metadata; place `AppBuildMaterialEditor` above it and do not add a “构建 APK” button in this phase. Preserve the existing archived-tenant disable behavior.

- [ ] **Step 6: Run the frontend tests and type check.**

  Run: `pnpm exec vitest run test/unit/skit/tenant/app-build-material.spec.ts && pnpm run ts:check`

  Expected: PASS with no TypeScript errors.

- [ ] **Step 7: Commit the frontend unit.**

  ```bash
  git add src/api/skit/tenant/index.ts src/views/skit/tenant/index.vue \
    src/views/skit/tenant/AppBuildMaterialEditor.vue \
    test/unit/skit/tenant/app-build-material.spec.ts
  git commit -m "feat(skit): add tenant build material editor"
  ```

## Task 5: Verify persistence, isolation, and non-interference

**Files:**
- Create: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/integration/SkitAppBuildMaterialMySqlIT.java`
- Modify: `docs/runbooks/ad-revenue-rollout.md` (add the database-only material storage and local Mac handoff)

- [ ] **Step 1: Add MySQL integration coverage.** Start with two tenants, save material for each, assert exactly one active row per tenant, assert old versions remain inactive, assert tenant A cannot select/decrypt tenant B's envelope, and assert malformed schema/envelope rows are rejected by the service.

- [ ] **Step 2: Add the non-interference assertion.** Run the existing reconciliation fixture after saving a build-material version and assert no build-material row/version changes; run a second ad-account save and assert it does not create a build-material version.

- [ ] **Step 3: Run backend module verification.**

  Run: `mvn -pl yudao-module-skit -DskipITs=false test`

  Expected: PASS for unit, schema contract, and MySQL integration tests when the project MySQL test profile is available; otherwise the existing integration profile must report its prerequisite rather than silently skip the contract tests.

- [ ] **Step 4: Run frontend verification.**

  Run: `pnpm exec vitest run test/unit/skit/tenant/app-build-material.spec.ts test/unit/skit/tenant/app-release-trust-root.spec.ts && pnpm run ts:check`

  Expected: PASS.

- [ ] **Step 5: Review the diff for secret leakage.** Run `rg -n "pangleSettingsJson|releaseKeystoreBase64|storePassword|keyPassword|ciphertext|nonce|encryptionKeyId"` over controller response types, audit canonical helpers, logs, and frontend templates; only write-only request fields and internal encrypted DO fields may match.

- [ ] **Step 6: Commit the verification/runbook unit.**

  ```bash
  git add yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/integration \
    docs/runbooks/ad-revenue-rollout.md
  git commit -m "test(skit): verify tenant build material isolation"
  ```
