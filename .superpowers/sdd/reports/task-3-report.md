# Task 3: provider callback route lifecycle

## Delivered

- Global provider connection and callback-route persistence, explicit global mapper guards, and lifecycle service.
- Lifecycle is restricted to `CONFIGURING/DRAFT -> ISSUED -> ABANDONED|SUBMITTED|BLOCKED`; block tombstones every accepting provider registry owner in the same transaction.
- Provider registration is `MANDATORY` in the caller transaction and inserts registry ownership before route CAS.
- Callback keys use a 29-byte random buffer and encode the first 228 consecutive bits as 38 Base64URL symbols after `acct_`; no Base64 string truncation or modulo reduction is used.
- Issued URLs are mutable, one-consumption values. Account references and temporary random/hash/URL buffers are cleared in `finally` blocks; a transaction completion hook destroys an unconsumed URL unless the surrounding transaction commits.
- Production issuance is deny-by-default. The Spring IT uses a fixture-only allowing gate to prove the submit audit fields, while the production bean remains denying.
- Callback URL construction, path/template versions, ordered macro template, and framed contract fingerprint are centralized in `SkitCallbackPublicUrlService`.
- `ProviderRouteResolution` exposes only getters, and Task 5 can pass a registry-proven `RouteLookup` to the no-second-lookup resolver seam.

## Evidence

RED baseline: provider lifecycle gate classes were initially absent, producing 2 compile failures. The first real Spring/MySQL lifecycle pass exposed primary-slot contamination between ordered scenarios; tests now abandon/block prior accepting routes and isolate race cases in tenant-owned connections.

GREEN on the final source set (single-threaded, after `mvn clean` removed concurrent Maven's duplicate `* N.class` target pollution):

- `mvn -T1 -pl yudao-module-skit -Dtest=SkitProviderConnectionServiceTest,SkitCallbackPublicUrlServiceTest test` — 12 tests, 0 failures/errors.
- `mvn -T1 -pl yudao-module-skit -Dit.test=SkitProviderConnectionLifecycleMySqlIT,SkitProviderConnectionLifecycleSpringMySqlIT failsafe:integration-test failsafe:verify` — 13 tests, 0 failures/errors (3 direct MySQL constraint/trigger tests and 10 Spring/MyBatis lifecycle tests).

The Spring/MyBatis proof includes the actual scanned proxied service, real MySQL mapper calls and triggers, shared-master and issue races, registry collisions, gates, callback resolver accept/reject states, and tenant-context restoration.
