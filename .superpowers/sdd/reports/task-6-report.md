# Task 6: secure platform provider lifecycle administration

## Delivered

- Exactly seven super-admin endpoints for the phase-1 provider connection lifecycle: create the
  unique `SHARED_MASTER`, read its safe state, create a `GATE_TEST|PRODUCTION` draft route, issue
  once, abandon with the exact never-shared declaration, mark a production route submitted, and
  block the connection.
- Every endpoint has the closed `super_admin` method guard and calls `SkitPlatformAdminGuard`.
  Every mutation disables request and response access logging, returns `Cache-Control: no-store`,
  and transfers its current-password buffer exactly once before clearing it on every controller and
  executor exit path.
- Immediate reauthentication uses the immutable original `LoginUser` identity,
  `AdminUserService.getUserIgnoreTenant`, and `PasswordEncoder.matches(CharBuffer, hash)`. It
  creates no reusable reauthentication artifact and clears wrong, blank, oversized, and successful
  password buffers.
- The issue response is hand-written and one-shot. Its custom Jackson serializer writes the private
  callback `char[]` directly with `JsonGenerator.writeString(char[], offset, length)`, clears the
  retained array in `finally`, has no URL getter, and rejects a second serialization.
- `SkitPlatformProviderCommandExecutor` owns the outer REQUIRED transaction. Task 3 lifecycle
  calls join it, and the executor appends exactly one allowlisted SUCCEEDED audit row with immutable
  actor/original-login-tenant evidence, domain-separated SHA-256 fingerprints, and a server trace.
  No failed-command audit is claimed to be atomic.
- The global audit mapper exposes only parameterized insert and is protected by type- and
  method-level tenant bypass annotations. Task 1 database triggers remain the authority preventing
  audit update/delete.
- GET uses a dedicated MyBatis projection with an explicit column list. Credential hashes, full
  registry hashes, submission free text, callback material, owner metadata, and actor fields are not
  selected into memory.
- All accepted free text is bounded and rejects case-insensitive `http`, `acct_`, standalone
  43-character callback-token shapes, ISO controls, and Unicode line/paragraph separators before
  it can reach lifecycle or audit persistence.

## Transaction evidence

`SkitPlatformProviderCommandSpringMySqlIT` starts the real Spring transaction proxy, scanned
MyBatis mappers, production schema, and MySQL 8. It proves both sides of the Task 3 commit gate:

- successful outer create/draft/issue commits lifecycle plus one audit row; only after the outer
  commit can the returned URL be consumed, and only once;
- a duplicate server trace makes the audit insert fail after Task 3 issued the route; the outer
  transaction rolls back route and registry writes, Task 3 transaction synchronization destroys the
  retained URL, and no URL can be consumed;
- caller tenant id/ignore state is restored while audit keeps the original login tenant; and
- direct audit `UPDATE` and `DELETE` are rejected by the real Task 1 MySQL triggers.

## Verification

RED started with the Task 6 controller, request/response types, reauthentication service, command
executor, audit mapper, and error codes absent. The initial focused compile therefore failed on
those missing contracts before production implementation was added.

Initial GREEN evidence before the shared target-directory clean:

- `mvn -T1 -pl yudao-module-skit -Dtest=SkitProviderConnectionControllerTest,SkitCurrentAdminPasswordReauthServiceTest,SkitPlatformProviderCommandExecutorTest test`
  — 16 tests, 0 failures/errors/skips.
- `mvn -T1 -pl yudao-module-skit -Dit.test=SkitPlatformProviderCommandSpringMySqlIT failsafe:integration-test failsafe:verify`
  — 1 real Spring/MySQL test, 0 failures/errors/skips. The log contains the expected duplicate
  `task6_duplicate_trace` unique-key failure used to prove rollback.

After deleting the shared module target and compiling once, the root's serial integration rerun
reported `SkitPlatformProviderCommandSpringMySqlIT` 1/1 passing, 0 failures/errors, in 38.21 s;
the clean target contained zero duplicate classes. Java formatting used google-java-format 1.24.0
on the explicit Task 6 file list under Corretto JDK 17; unrelated Task 4/5/8 files were excluded.
