# Pangle Reward Callback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a tenant-scoped Pangle rewarded-video S2S callback which verifies `SHA-256(SecurityKey:trans_id)`, records immutable attestation evidence, and requires both Pangle and Taku evidence before granting a network-firm-15 reward exactly once.

**Architecture:** The existing Taku callback key remains the secret tenant route and produces a third Pangle callback URL. Pangle's callback Security Key is stored as an encrypted, versioned reward secret under the tenant's PANGLE ad account, while each Taku reward session snapshots the PANGLE account, placement, and credential version. A Pangle callback only creates an immutable attestation; the existing Taku processor remains the single grant path and, for `network_firm_id=15`, fails closed until a matching Pangle attestation exists.

**Tech Stack:** Java 8, Spring Boot, MyBatis/MySQL 8, JUnit 5/Mockito, Vue 3/TypeScript/Vitest, Nginx.

## Global Constraints

- The content API `pangleAppSecret` remains separate from the write-only Pangle reward `Security Key`.
- The Pangle endpoint is exactly `GET /app-api/skit/ad-callback/pangle/{callbackKey}/reward`.
- Required Pangle query fields are exactly `user_id`, `trans_id`, `reward_name`, `reward_amount`, `extra`, and `sign`; unknown, duplicate, malformed, oversized, or missing fields are deterministically invalid.
- The signature input is UTF-8 `SecurityKey + ":" + trans_id`, digested with SHA-256 and compared in constant time.
- Deterministic callback results are HTTP 200 JSON `{"isValid":true}` or `{"isValid":false}` with `Cache-Control: no-store`; infrastructure failures remain 5xx so Pangle can retry.
- Callback keys, Security Keys, raw queries, session tokens, and unredacted callback URLs never enter logs or normal API responses.
- MVP supports mainland Pangle/CSJ only: `network_firm_id=15`; overseas Pangle firm 50 is not authorized.
- A firm-15 entitlement requires both valid Taku signed ILRD and a matching valid Pangle attestation, but the existing Taku session CAS remains the only grant mutation.
- Existing Taku callbacks and non-15 ad networks retain their current behavior.
- Raw credentials are never persisted outside encrypted credential storage and temporary byte arrays are zeroed after use.

---

### Task 1: Strict Pangle Protocol Boundary

**Files:**
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/PangleRewardCallback.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/PangleRewardCallbackCanonicalizer.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/PangleRewardSignatureVerifier.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/ad/callback/PangleRewardCallbackCanonicalizerTest.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/service/ad/callback/PangleRewardSignatureVerifierTest.java`

**Interfaces:**
- Produces: `PangleRewardCallback canonicalize(String rawQuery)` with normalized fields plus a 32-byte canonical payload hash.
- Produces: `boolean verify(PangleRewardCallback callback, byte[] securityKey)`.

- [ ] **Step 1: Write failing parser and verifier tests**

```java
assertEquals("tx-1", canonicalizer.canonicalize(
        "user_id=u1&trans_id=tx-1&reward_name=coin&reward_amount=1&extra=session&sign="
        + "0000000000000000000000000000000000000000000000000000000000000000").getTransactionId());
assertThrows(CallbackFormatException.class, () -> canonicalizer.canonicalize(
        "user_id=u1&user_id=u2&trans_id=tx-1&reward_name=coin&reward_amount=1"
                + "&extra=session&sign="
                + "0000000000000000000000000000000000000000000000000000000000000000"));
assertTrue(verifier.verify(callback("tx-1",
        sha256Hex("security-key:tx-1")), "security-key".getBytes(UTF_8)));
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
mvn -pl yudao-module-skit -am -DskipITs -Dtest=PangleRewardCallbackCanonicalizerTest,PangleRewardSignatureVerifierTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because the three Pangle protocol classes do not exist.

- [ ] **Step 3: Implement strict parsing and constant-time verification**

`PangleRewardCallbackCanonicalizer` must parse the untouched raw query once, allow exactly the six documented names, reject duplicate/unknown names and invalid UTF-8 percent encoding, cap the query at 8 KiB, cap each decoded value at 1 KiB, require a positive integer reward amount no greater than 100,000,000, and require a 64-character hexadecimal signature. Its canonical hash is SHA-256 over length-prefixed domain `PANGLE_REWARD` and the six normalized fields in the documented order.

`PangleRewardSignatureVerifier` must compute SHA-256 over the Security Key bytes, one ASCII colon byte, and UTF-8 transaction ID bytes, decode the submitted hex, then call `MessageDigest.isEqual`; it must not convert the Security Key to a `String`.

- [ ] **Step 4: Run tests and verify GREEN**

Run the Task 1 command and expect all Task 1 tests to pass.

- [ ] **Step 5: Commit**

```bash
git add yudao-module-skit/src/main yudao-module-skit/src/test
git commit -m "feat(skit): verify Pangle reward callbacks"
```

### Task 2: Versioned Pangle Credential and Immutable Attestation Schema

**Files:**
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/schema/SkitSchemaInitializer.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/ad/SkitAdSessionDO.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/ad/SkitAdSessionMapper.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/dataobject/ad/SkitPangleRewardAttestationDO.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/dal/mysql/ad/SkitPangleRewardAttestationMapper.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/framework/schema/SkitPangleRewardCallbackSchemaContractTest.java`
- Test: `yudao-module-skit/src/test/java/cn/iocoder/yudao/module/skit/dal/mysql/SkitPangleRewardAttestationPersistenceContractTest.java`

**Interfaces:**
- Produces nullable session snapshot fields `pangleAdAccountId`, `pangleRewardSecretVersion`, and `pangleRewardPlacementId`.
- Produces mapper operations `insert`, `selectBySession`, and `selectByTransactionId`.

- [ ] **Step 1: Write failing schema and persistence contract tests**

Tests must require migration version `2026073001`, session snapshot columns, the `skit_pangle_reward_attestation` table, tenant-safe composite foreign keys, unique `(tenant_id,pangle_ad_account_id,provider_transaction_id)`, unique `(tenant_id,ad_session_id)`, a provider check fixed to `PANGLE`, and immutable update/delete triggers.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
mvn -pl yudao-module-skit -am -DskipITs -Dtest=SkitPangleRewardCallbackSchemaContractTest,SkitPangleRewardAttestationPersistenceContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: tests fail because migration `2026073001`, the table, fields, DO, and mapper do not exist.

- [ ] **Step 3: Add an append-only migration**

Add migration `2026073001` without modifying any prior migration manifest. It adds the three nullable snapshot columns to `skit_ad_session` and creates `skit_pangle_reward_attestation` with:

```text
id, tenant_id, taku_ad_account_id, pangle_ad_account_id, ad_session_id,
callback_key_version, pangle_reward_secret_version, pangle_reward_placement_id,
provider, provider_transaction_id, provider_user_id, extra_data_hash,
reward_name, reward_amount, canonical_payload_hash, credential_fingerprint,
received_at, audit columns
```

All identifiers, hashes, version fields, placement, canonical hash, fingerprint, and `received_at` are non-null. The table has tenant-safe FKs to both ad accounts, the Taku session, the Taku callback key version, and the Pangle reward secret version. The immutable trigger permits no UPDATE and the delete trigger permits no DELETE.

- [ ] **Step 4: Add DO and exact idempotency mapper**

`insert` stores no raw query or Security Key. `selectBySession` always requires tenant plus Taku session/account identifiers; `selectByTransactionId` always requires tenant plus the PANGLE account identifier. Add the three snapshot fields to every session INSERT/select contract that enumerates session columns.

- [ ] **Step 5: Run tests and verify GREEN**

Run the Task 2 command and expect both contract tests to pass.

- [ ] **Step 6: Commit**

```bash
git add yudao-module-skit/src/main yudao-module-skit/src/test
git commit -m "feat(skit): persist Pangle reward attestations"
```

### Task 3: Tenant Configuration, Session Snapshot, and Callback URL

**Files:**
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/SkitAdAccountService.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/SkitAdAccountServiceImpl.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/SkitAdSessionServiceImpl.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/SkitCallbackPublicUrlService.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/SkitTenantBusinessController.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/SkitTenantAdCapabilityController.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/admin/tenant/vo/SkitAdCallbackKeyRotateRespVO.java`
- Test: corresponding `SkitAdAccountServiceImplTest`, `SkitAdSessionServiceImplTest`, `SkitCallbackPublicUrlServiceTest`, `SkitTenantBusinessControllerTest`, and `SkitTenantAdCapabilityControllerTest`.

**Interfaces:**
- Consumes: existing `rotateRewardSecret` and `getActiveRewardSecretVersion` under the PANGLE account ID.
- Produces settings fields `pangleRewardSecurityKey` (write-only), `pangleRewardSecurityKeyConfigured`, and `panglePlacementId`.
- Produces `String pangleRewardCallbackUrl(String callbackKey)`.

- [ ] **Step 1: Write failing configuration, snapshot, URL, and redaction tests**

Tests must prove that Server Key and Security Key stay separate, blank Security Key preserves the active version, a submitted Security Key rotates the PANGLE-account reward credential with a 24-hour prior-acceptance window, settings never echo it, session creation snapshots only an enabled/configured same-tenant PANGLE account, and callback rotation returns the one-time URL without a macro query string.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
mvn -pl yudao-module-skit -am -DskipITs -Dtest=SkitAdAccountServiceImplTest,SkitAdSessionServiceImplTest,SkitCallbackPublicUrlServiceTest,SkitTenantBusinessControllerTest,SkitTenantAdCapabilityControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: the new fields, snapshot behavior, and URL assertion fail.

- [ ] **Step 3: Implement write-only credential rotation**

When `pangleRewardSecurityKey` is nonblank, trim neither its interior nor mutate `pangleAppSecret`; UTF-8 encode it, call `rotateRewardSecret(tenantId, pangleAccountId, bytes, Duration.ofHours(24))`, and zero the byte array in `finally`. Return only the configured boolean. Add `pangleRewardSecurityKey` to all API access-log sanitization lists and redact credential-bearing account fields from `toString`.

- [ ] **Step 4: Snapshot the Pangle credential during Taku session creation**

Load the enabled same-tenant PANGLE account. If the account, placement, or active reward credential is absent, leave all three Pangle snapshot fields null so non-Pangle networks continue; otherwise set all three together. Reject cross-tenant mapper results.

- [ ] **Step 5: Add the one-time Pangle callback URL**

`pangleRewardCallbackUrl` returns:

```text
{publicBaseUrl}/skit/ad-callback/pangle/{43-character-callbackKey}/reward
```

Add it to the callback-key rotation response, response logging sanitization, and `toString` exclusion.

- [ ] **Step 6: Run tests and verify GREEN**

Run the Task 3 command and expect all selected tests to pass.

- [ ] **Step 7: Commit**

```bash
git add yudao-module-skit/src/main yudao-module-skit/src/test
git commit -m "feat(skit): configure Pangle reward callbacks"
```

### Task 4: Pangle Ingress and Taku Dual-Evidence Gate

**Files:**
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/controller/app/ad/SkitPangleCallbackController.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitPangleCallbackIngressService.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitPangleCallbackIngressServiceImpl.java`
- Create: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitRewardPrerequisitePendingException.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitAdCallbackProcessorImpl.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/service/ad/callback/SkitAdCallbackInboxDrainServiceImpl.java`
- Modify: `yudao-module-skit/src/main/java/cn/iocoder/yudao/module/skit/framework/web/SkitCallbackSecretSanitizingFilter.java`
- Test: `SkitPangleCallbackControllerTest`, `SkitPangleCallbackIngressServiceImplTest`, `SkitAdCallbackProcessorImplTest`, `SkitAdCallbackInboxDrainServiceImplTest`, and `SkitCallbackSecretSanitizingFilterTest`.

**Interfaces:**
- Produces: `boolean receiveReward(String callbackKey, String rawQuery, String clientIp)`.
- Consumes: exact immutable attestation mapper and existing Taku callback inbox/session grant CAS.

- [ ] **Step 1: Write failing ingress, controller, filter, idempotency, and ordering tests**

Cover valid callback, wrong signature, wrong route/session/user, expired window, absent session credential snapshot, duplicate exact transaction, transaction payload conflict, two transactions for one session, unknown callback key, malformed path, and callback-key/query suppression. Cover Taku-first and Pangle-first ordering: firm 15 grants only after both facts exist, and repeat/concurrent delivery still produces one grant. Cover non-15 Taku callbacks remaining unchanged.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
mvn -pl yudao-module-skit -am -DskipITs -Dtest=SkitPangleCallbackControllerTest,SkitPangleCallbackIngressServiceImplTest,SkitAdCallbackProcessorImplTest,SkitAdCallbackInboxDrainServiceImplTest,SkitCallbackSecretSanitizingFilterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation or behavioral failures for the missing Pangle ingress and firm-15 gate.

- [ ] **Step 3: Implement deterministic Pangle ingress**

Rate-limit with callback type `REWARD`, resolve the callback key, canonicalize the raw query, hash `extra` through the existing session-token path, lock the Taku session under the routed account, and validate tenant, account, callback version, user, reward window, and all-or-none Pangle snapshot fields. Resolve the session-pinned PANGLE reward secret, verify the signature, compute a non-secret credential-metadata fingerprint, then insert the immutable attestation.

An exact replay by transaction and canonical hash returns true. A transaction collision, second attestation for one session, signature/semantic error, or expired request returns false. Database/Redis/crypto infrastructure exceptions are not converted to false.

- [ ] **Step 4: Implement the controller and secret-sanitizing filter**

The controller writes only `{"isValid":true}` or `{"isValid":false}`. Extend the early filter to recognize both provider prefixes, redact the callback key, suppress all parameters, and return Pangle JSON false for malformed Pangle paths while preserving Taku's numeric 602 behavior.

- [ ] **Step 5: Gate firm-15 Taku processing**

Immediately before the existing grant CAS, if the verified Taku inbox has `networkFirmId == 15`, require one attestation whose tenant, Taku account, session, callback version, Pangle account/version, user, extra hash, and Pangle placement snapshot match. If absent, throw `SkitRewardPrerequisitePendingException`.

The inbox drain catches that exception and keeps the exact firm-15 callback in
`PANGLE_ATTESTATION_PENDING` while the session reward window is live. A matching attestation
wakes it immediately; once the session is no longer eligible, the ordinary bounded terminal policy
applies. Generic exceptions keep existing behavior. This path never creates a second entitlement or
changes the existing reward receipt.

- [ ] **Step 6: Run tests and verify GREEN**

Run the Task 4 command and expect all selected tests to pass.

- [ ] **Step 7: Commit**

```bash
git add yudao-module-skit/src/main yudao-module-skit/src/test
git commit -m "feat(skit): require Pangle reward attestation"
```

### Task 5: Tenant UI and Edge Log Safety

**Files:**
- Modify: `src/api/skit/tenant/index.ts`
- Modify: `src/views/skit/tenant/workspaceModel.ts`
- Modify: `src/views/skit/tenant/AdAccessEditor.vue`
- Modify: `deploy/nginx.conf`
- Modify: `deploy/test-callback-log-safety.sh`
- Test: `test/unit/skit/tenant/display-ad-placement-config.spec.ts`
- Test: `test/unit/skit/tenant/workspace-model.spec.ts`
- Test: `test/unit/skit/tenant/components.spec.ts`
- Test: `test/unit/skit/tenant/advanced-api-contract.spec.ts`

**Interfaces:**
- Consumes: settings fields and `pangleRewardCallbackUrl` from Tasks 3 and 4.
- Produces: a write-only Security Key input, explicit content Server Key/Reward Security Key labels, Pangle placement input, configured badge, and one-time copyable Pangle URL.

- [ ] **Step 1: Write failing frontend and Nginx safety tests**

Tests must reject a malicious response attempting to inject a raw Security Key, preserve a configured key when the input is blank, send it only when newly typed, distinguish the two key labels, expose Pangle placement configuration, show/copy the Pangle URL once, and assert a callback-log-suppressed Pangle Nginx location appears before the generic API location.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
pnpm vitest run test/unit/skit/tenant/display-ad-placement-config.spec.ts test/unit/skit/tenant/workspace-model.spec.ts test/unit/skit/tenant/components.spec.ts test/unit/skit/tenant/advanced-api-contract.spec.ts
```

Expected: assertions for the new fields, labels, URL, and log-safety route fail.

- [ ] **Step 3: Implement the UI and API allow-lists**

Initialize `pangleRewardSecurityKey` to an empty string after every load, ignore any response value for it, include it in save payloads only when nonblank, and clear it after save. Show the Pangle placement field and configured badge. Add the third callback URL to the one-time dialog and copy-all bundle without persisting it to workspace state after dialog close.

- [ ] **Step 4: Add the edge route and validate log safety**

Add a Pangle callback Nginx location before generic `/app-api/` proxying with access/error logging disabled and the raw request URI excluded from diagnostic output. Extend the shell contract to test both Taku and Pangle callback paths.

- [ ] **Step 5: Run tests and verify GREEN**

Run the Task 5 command plus:

```bash
bash deploy/test-callback-log-safety.sh
pnpm ts:check
```

Expected: all commands pass.

- [ ] **Step 6: Commit**

```bash
git add src test deploy
git commit -m "feat(skit): manage Pangle reward callbacks"
```

### Task 6: Integrated Verification and Release Evidence

**Files:**
- Modify if needed: only files directly implicated by a failing verification.
- Preserve: `skit-saas-app/docs/taku-pangle-callback-responsibility.md`.

- [ ] **Step 1: Run backend targeted and module-wide tests**

```bash
mvn -pl yudao-module-skit -DskipITs test
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Run frontend unit, type, and build gates**

```bash
pnpm vitest run
pnpm ts:check
pnpm build:prod
bash deploy/test-callback-log-safety.sh
```

Expected: every command succeeds.

- [ ] **Step 3: Audit the final diff for credential leakage and duplicate grants**

```bash
git diff --check
rg -n "pangleRewardSecurityKey|securityKey" yudao-module-skit/src/main yudao-module-skit/src/test
```

Every raw-secret field must be write-only/sanitized and no logger call may include callback objects, raw queries, callback URLs, or secret values. Firm 15 must have one and only one entitlement mutation path.

- [ ] **Step 4: Record deployment boundary**

The implementation is code-complete only after tests pass. Production is not declared live until the migration is deployed, each tenant configures its Pangle placement and Security Key, the callback URL is entered in Pangle, and a real device/ad run proves Pangle attestation plus Taku signed ILRD plus the single grant CAS.

## Execution Record (2026-07-30)

- Backend protocol, persistence, credential rotation, tenant capability, rate-limit, replay,
  waiting/wake, and single-grant unit suites passed: 927 tests, 0 failures, 0 errors.
- MySQL 8.0.36 integration tests passed with Docker API 1.44:
  `SkitAdBootstrapSchemaMySqlIT`, `SkitPangleRewardCallbackMySqlIT`, and
  `SkitAdCredentialVersionMySqlIT`; 17 tests, 0 failures, 0 errors, 0 skipped.
- Frontend verification passed: 41 test files and 265 tests, TypeScript check, production
  build, targeted ESLint, Stylelint, and Prettier checks.
- Public HTTPS and callback-log-safety shell contracts passed, including the Pangle callback
  route and secret-safe logging behavior.
- Independent final review found no unresolved P0/P1/P2 commit blocker. Callback limiter
  configurability and hash-only failure metrics/alerts remain non-blocking follow-up work.
- No deployment, callback-key rotation, or live platform configuration was performed as part
  of this implementation. Production still requires tenant configuration and a real-device
  dual-evidence reward verification.
