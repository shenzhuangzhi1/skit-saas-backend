# Task 4 Report: Provider Impression Capture

## Scope and binding refinement

Implemented capture-only persistence for account-level provider impression callbacks. This task
does not add MVC routing, dispatcher policy, reward/unlock authority, tenant Inbox writes, revenue
calculation, lifecycle APIs, retention workers, or production route issuance.

The canonical API accepts an already parsed, closeable `WirePayload`:

```java
CaptureDecision capture(ProviderRouteResolution route, WirePayload wirePayload,
                        ProviderIngressEvidence evidence, LocalDateTime receivedAt)
```

The raw-query overload remains a convenience seam and checks the accepting route before parsing.
Task 5 can therefore resolve the route, parse exactly once, construct safe ingress evidence, and
invoke the canonical overload. The only decisions are `ACK_200`, `REJECT_602`, and
`PERSISTENCE_FAILURE_503`.

`ProviderIngressEvidence` carries only a 16-byte server correlation id, 32-byte remote-address
HMAC, optional 32-byte user-agent HMAC, and 32-byte allowlisted-header fingerprint. It derives the
unique `pci-<lowercase correlation hex>` trace id, defensively copies inputs, redacts printing, and
is explicitly closed by capture.

## TDD evidence

All Maven commands use JDK 17 and Maven `-T1`. Maven process checks were performed before each
locally initiated run; no Task 4 Maven processes overlapped.

### Initial RED

Command:

```bash
env JAVA_HOME=/Users/neo/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home \
  PATH=/Users/neo/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin \
  mvn -T1 -pl yudao-module-skit \
  -Dtest=SkitProviderImpressionWireParserTest,SkitProviderImpressionCaptureServiceTest test
```

Result: **BUILD FAILURE**, 2 tests, 2 failures. Both failures were the expected missing parser and
capture contracts (`ClassNotFoundException`).

After adding compile-only API skeletons, the same two existence tests passed (2 tests, 0 failures),
which established that the initial RED was not an environment failure.

### Full behavior RED

Parser-only compile RED:

```bash
env JAVA_HOME=/Users/neo/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home \
  PATH=/Users/neo/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin \
  mvn -T1 -pl yudao-module-skit -Dtest=SkitProviderImpressionWireParserTest test
```

Result: **BUILD FAILURE** during test compilation because `parseBounded`, `WirePayload`,
`WireParameter`, and `WireBoundaryException` behavior contracts did not yet exist.

Full parser/provider-crypto behavior RED:

```bash
env JAVA_HOME=/Users/neo/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home \
  PATH=/Users/neo/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin \
  mvn -T1 -pl yudao-module-skit \
  -Dtest=SkitProviderImpressionWireParserTest,SkitProviderCallbackPayloadCryptoServiceTest,SkitCallbackPayloadCryptoServiceTest test
```

Result at 2026-08-03 05:08:13 +08:00: **BUILD FAILURE**, 25 tests, 0 failures,
17 expected errors from the behavior skeletons. The 7 pre-existing tenant callback/credential
compatibility tests remained green.

The completed capture behavior suite subsequently exposed two Mockito restub fixture errors and
one correlation fixture mismatch (38 pass/2 error, then 39 pass/1 failure). Both fixtures were
corrected without changing production semantics: transaction restubs now use `doAnswer`/`doThrow`,
and the default correlation is exactly `00..0f`.

### Main compile checkpoint

Command coordinated serially by the root agent:

```bash
env JAVA_HOME=/Users/neo/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home \
  PATH=/Users/neo/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin \
  mvn -T1 -pl yudao-module-skit -DskipTests compile
```

Result: **BUILD SUCCESS**; all Task 4 main sources compiled.

### Final focused unit verification

Task 4 selectors in the root-coordinated combined focused command:

```bash
env JAVA_HOME=/Users/neo/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home \
  PATH=/Users/neo/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin \
  mvn -T1 -pl yudao-module-skit \
  -Dtest=SkitProviderImpressionWireParserTest,SkitProviderImpressionCaptureServiceTest,SkitProviderCallbackPayloadCryptoServiceTest,SkitProviderCallbackPayloadCryptoConfigurationTest,SkitCallbackPayloadCryptoServiceTest,SkitAdCredentialCryptoServiceTest test
```

The same Maven invocation also selected 16 independent Task 6 tests. Final result:
**BUILD SUCCESS**, 56 tests, 0 failures, 0 errors, 0 skipped. The Task 4 share was
40 tests, all passing (40/40).

### Final real-MySQL verification

Clean serial command coordinated by the root agent (Task 4 and the independent Task 6 IT shared
one container startup but remained separate test classes):

```bash
env JAVA_HOME=/Users/neo/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home \
  PATH=/Users/neo/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin \
  mvn -T1 -pl yudao-module-skit \
  -Dit.test=SkitProviderImpressionCaptureMySqlIT,SkitPlatformProviderCommandSpringMySqlIT \
  test-compile failsafe:integration-test failsafe:verify
```

Task 4 result at 2026-08-03 05:52:21 +08:00: **BUILD SUCCESS**, 4 tests, 0 failures,
0 errors, 0 skipped, 30.10 seconds. The clean rerun reported zero duplicate classes.

## Implemented invariants

- Visible-ASCII servlet wire contract with exact 32 KiB, 64-segment, and 24 KiB-value bounds.
- Strict one-pass name/value decoding, wire-order preservation, official and fallback framed hashes,
  material-state framing, fixed vectors, and explicit plaintext cleanup.
- Dedicated provider AES keyring rooted at
  `skit.ad.provider-callback-payload-encryption`; no second naked credential-crypto bean and no
  tenant credential-key reuse.
- Provider AAD binds purpose, connection, 16-byte correlation, 32-byte wire hash, envelope version,
  and actual key id. Provider empty plaintext is valid; legacy reward and tenant callback empty
  plaintext remains rejected.
- Existing reward and tenant callback AAD is locked by legacy/fixed vectors. Provider fixed vector:
  `X4DKI7B8796F7VffFby+cZRrZthV2Em/EnBss4wpLOc33LmybBs9Gts=`.
- Parse/hash/evidence copies/encryption/row construction occur before a `REQUIRES_NEW`,
  `READ_COMMITTED`, one-second `TransactionTemplate` transaction.
- One atomic Inbox upsert, `FOR UPDATE` lock, one immutable Attempt per valid delivery, exact first
  canonical CAS, material-based equivalent/conflict classification, monotonic conflict transition,
  and monotonic last-received update. ACK is returned only after `execute` commits.
- Null/non-accepting routes return 602 before parser, crypto, or mapper work. Fixed parser boundary
  violations return 602. Crypto/transaction/commit failures return 503.
- Provider DOs and every mapper type/method carry both tenant bypass annotations. Capture has no
  `TenantUtils`, servlet request, tenant-table, session, reward, unlock, or revenue dependency.
- Real-MySQL coverage includes 20 simultaneous submissions (10 material A, 10 material B), one
  Inbox, 20 Attempts, 1 canonical, 9 equivalent, 10 conflict, revision 1, fallback quarantine,
  tenant-context preservation, rollback-before-injected-commit-failure, and direct CHECK/FK/trigger
  rejection.

## Operational configuration boundary

Task 4 owns the Java crypto properties/configuration bean:

- `skit.ad.provider-callback-payload-encryption.current-key-id`
- `skit.ad.provider-callback-payload-encryption.current-key`
- `skit.ad.provider-callback-payload-encryption.keys.<retained-key-id>`

Task 8 owns application/prod/deployment wiring to
`SKIT_PROVIDER_CALLBACK_PAYLOAD_KEY_ID` and `SKIT_PROVIDER_CALLBACK_PAYLOAD_KEY`, plus production
startup validation and key generation/retention. An absent runtime keyring fails closed at provider
encryption and cannot fall back to tenant credential material.
