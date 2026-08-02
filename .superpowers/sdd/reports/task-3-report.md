# Task 3: provider callback route lifecycle

## Delivered

- Global provider connection and callback-route persistence, explicit global mapper guards, and lifecycle service.
- Lifecycle is restricted to `CONFIGURING/DRAFT -> ISSUED -> ABANDONED|SUBMITTED|BLOCKED`; block tombstones every accepting provider registry owner in the same transaction.
- Provider registration is `MANDATORY` in the caller transaction and inserts registry ownership before route CAS.
- Callback keys use a 29-byte random buffer and encode the first 228 consecutive bits as 38 Base64URL symbols after `acct_`; no Base64 string truncation or modulo reduction is used.
- Issued URLs are mutable, one-consumption values that remain inaccessible until the surrounding transaction's `afterCommit`. Account references and temporary random/hash/URL buffers are cleared in `finally` blocks; every non-committed completion destroys and zeroes the retained URL buffer, while a committed route can be consumed exactly once.
- Production issuance is deny-by-default. The Spring IT uses a fixture-only allowing gate to prove the submit audit fields, while the production bean remains denying.
- Callback URL construction, path/template versions, ordered macro template, and framed contract fingerprint are centralized in `SkitCallbackPublicUrlService`.
- `ProviderRouteResolution` exposes only getters, and Task 5 can pass a registry-proven `RouteLookup` to the no-second-lookup resolver seam.

## Evidence

RED baseline: provider lifecycle gate classes were initially absent, producing 2 compile failures. The first real Spring/MySQL lifecycle pass exposed primary-slot contamination between ordered scenarios; tests now abandon/block prior accepting routes and isolate race cases in tenant-owned connections.

GREEN on the final source set (JDK 17, Maven `-T1`, no other Maven process, and the shared diff hash remained `d1aa31f5a99f6e9c2d4a1832e749bf0b3c21218bc1aba98efdaa442c5106ea43` before, during, and after both commands):

- `mvn -T1 -pl yudao-module-skit -Dtest=SkitProviderConnectionServiceTest,SkitCallbackPublicUrlServiceTest test` — 13 tests, 0 failures/errors/skips. The regression proof rejects pre-commit consumption, verifies rollback zeroes the retained backing buffer without leaking a URL, and permits exactly one consumption only after commit.
- `mvn -T1 -pl yudao-module-skit -Dit.test=SkitProviderConnectionLifecycleMySqlIT,SkitProviderConnectionLifecycleSpringMySqlIT failsafe:integration-test failsafe:verify` — 13 tests, 0 failures/errors (3 direct MySQL constraint/trigger tests and 10 Spring/MyBatis lifecycle tests).

The Spring/MyBatis proof includes the actual scanned proxied service, real MySQL mapper calls and triggers, shared-master and issue races, registry collisions, gates, callback resolver accept/reject states, and tenant-context restoration.
