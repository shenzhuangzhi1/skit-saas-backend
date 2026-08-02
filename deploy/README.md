# Skit SaaS Production Deployment

This repository owns the shared production Docker Compose stack. The frontend
is a static Nginx service and has no runtime dependency on the backend
container; a frontend release must therefore never pull, recreate, or restart
the backend service.

The end-to-end tenant onboarding, shadow verification, App release, rollback,
and alert procedure is documented in [`docs/runbooks/ad-revenue-rollout.md`](../docs/runbooks/ad-revenue-rollout.md).

The backend workflow publishes the Docker image to GitHub Container Registry
and the server pulls that image during activation. SSH only uploads Compose,
activation scripts, and database initialization SQL.

The backend host port binds to `127.0.0.1`; public API traffic must enter through the frontend
Nginx proxy. Taku has confirmed that impression S2S is account-level: one account uses one unified,
long-lived callback URL, and newly added apps inherit it. It is not a per-tenant, per-app, or
per-placement URL. Taku also confirmed that this address cannot be queried, changed, or rotated
through Open API. Whether the shared callback reliably includes `package_name`, `placement_id`,
and `show_custom_ext` still requires written Taku confirmation, so capture-only Phase 1 must not
attribute tenant revenue, calculate commission, or unlock content.

Every Taku reward/impression callback URL contains a write-only routing key and must be handled as
a secret. Do not paste the URL into tickets or logs. The dedicated Nginx callback location disables
access logging while forwarding the original path and query unchanged for server-side verification.
One shared callback include applies a 64 KiB request-line and header ceiling, 250 ms connect/send
timeouts, a 1 s read timeout, no upstream retry, no request body forwarding, and fixed proxy-IP
overwrite to both Taku and Pangle routes. Callback error logs are suppressed so a request target or
query cannot enter proxy output.

The backend deliberately loads Spring profiles in the order `runtime,prod`. `runtime` contains only
the server port, MySQL pool, Redis, clustered JDBC Quartz, and INFO logging baseline needed by the
application. It does not inherit the local profile's demo credentials or third-party test
integrations. The final `prod` overlay disables mock login, demo mode, Swagger/Knife4j, the Druid
web console, the self-registering Spring Boot Admin client, and the embedded Admin server. It raises
BCrypt to cost 10 for newly encoded passwords. Production enables captcha, six-digit SMS codes,
formal mini-program links, sanitized API access logging, and only Actuator `health` without
component details. Missing, malformed, reused, or non-ASCII advertising key material aborts startup
without including key values in the error. MySQL, Redis, and the backend bind host ports to loopback;
public traffic enters through the frontend proxy.

All credential-shaped values in packaged `application*.yaml` resources are environment
placeholders with no real default. This includes the inactive local/dev profiles: database and
broker passwords, Spring Boot Admin passwords, WeChat secrets, social-login credentials, map/API
keys, and API-encryption key pairs are no longer embedded in the production JAR. The complete
variable list is maintained in `deploy/.env.example`. Local developers set only the variables for
their selected profile; routine production activation manages the five current advertising/capture
keys and the optional retained-key file.

## Server prerequisites

- Linux server with Docker Engine and Docker Compose v2.
- SSH user that can run Docker commands.
- Empty deployment directory, default: `skit-saas` under the SSH user's home directory.

## Local development

The local workflow is isolated from production. It starts only MySQL 8 and Redis 6.2
with loopback-only ports, and the Spring Boot process continues to use the existing
`local` profile.

```bash
cp deploy/local.env.example deploy/local.env
./scripts/local-stack.sh up
./scripts/run-local.sh
```

In another terminal, run the frontend from `skit-saas-frontend` with `pnpm run dev`;
its `.env.local` points to `http://localhost:48080`. The App repository has its own
verification command and only creates a local debug APK.

Install the repository-local push gate once:

```bash
./scripts/install-local-hooks.sh
./scripts/verify-local.sh
```

`./scripts/local-stack.sh down` stops the services and preserves local data. It never
removes volumes. Reset is deliberately explicit and destructive:

```bash
SKIT_CONFIRM_RESET=1 ./scripts/local-stack.sh reset
```

The pre-push hook runs the bounded Skit build-material/tenant/security suite and MySQL 8
migration checks for source/config/SQL changes. The broader backend lifecycle matrix still
runs in GitHub Actions. Documentation-only pushes do not start the local test suite.

## Required GitHub Secrets

Set these secrets in each repository that deploys to the server:

- `SERVER_HOST`: server IP or domain.
- `SERVER_USER`: SSH user.
- `SERVER_SSH_KEY`: private key allowed to SSH into the server.
- `MYSQL_ROOT_PASSWORD`: production MySQL root password.

The backend activation script generates independent secure random values for the legacy
`SKIT_AD_ENCRYPTION_KEY`, credential-envelope `SKIT_AD_CREDENTIAL_KEY`, advertising-session
signature `SKIT_AD_SESSION_TOKEN_KEY`, provider payload
`SKIT_PROVIDER_CALLBACK_PAYLOAD_KEY`, and provider audit
`SKIT_PROVIDER_CALLBACK_AUDIT_HMAC_KEY` on the first release. It assigns the envelope key id
`SKIT_AD_CREDENTIAL_KEY_ID=primary`, assigns the signature key version
`SKIT_AD_SESSION_TOKEN_KEY_VERSION=1`, assigns provider payload key id
`SKIT_PROVIDER_CALLBACK_PAYLOAD_KEY_ID=primary`, and stores every value in the mode-`0600`
server-side `.env`; later
SaaS and App releases reuse them automatically. To use managed keys instead, inject the values
before the first backend activation. The session signature key must contain at least 32 safe
ASCII characters, its version must be a positive 32-bit integer, the audit HMAC key must contain
at least 32 random bytes, and no key may equal material used for another purpose. Never rotate a
key without first completing the corresponding
credential re-encryption or active-session compatibility procedure.

Configure each agent tenant's base64 X.509 DER RSA public key in its App release profile. The
backend validates an RSA key of at least 2048 bits, derives its fingerprint, and uses only that
tenant-scoped trust root when validating a hot-update manifest. The matching private key remains
only in that agent profile's protected App release environment. Rotating one agent's trust root
therefore cannot affect another tenant, and routine backend releases require no global App key.

Set repository variable `SKIT_AD_CALLBACK_PUBLIC_BASE_URL` to the public backend origin ending in
`/app-api`, for example `https://api.example.com/app-api`. Alternatively set
`SKIT_PUBLIC_HTTPS_DOMAIN` after the `Provision public HTTPS` workflow completes; deployments then
derive the same HTTPS callback origin. The workflow requires the `LETSENCRYPT_EMAIL` repository
secret and configures the host Nginx proxy, certificate renewal reload hook, and HTTPS health check.
Provider callback templates are built only from this value and never from `Host` or
`X-Forwarded-*` request headers. A configured deployment does not derive a callback origin from
`SERVER_HOST`: one of the explicit public URL or HTTPS domain variables is required. Activation
validates and persists the value, so routine backend releases require no callback reconfiguration.

Routine SaaS, frontend, and App releases do not rotate this material. Backend activation persists
the current key values and versions in the server-side `.env`, frontend activation remains
independent, and App publishing does not restart the backend. This keeps ordinary releases simple
while making key rotation an explicit maintenance operation.

Retained old credential-envelope keys and session-signature versions use a separate server-side
`ad-keyring.properties` file mounted read-only at `/run/secrets/skit-ad-keyring.properties`. The
activation script creates the file with mode `0600` on the first release and preserves it byte for
byte thereafter. The only accepted entries are:

```properties
skit.ad.credential-encryption.keys.<old-key-id>=<old-aes-key>
skit.ad.session-token.keys.<old-positive-version>=<old-hmac-key>
skit.ad.provider-callback-payload-encryption.keys.<old-key-id>=<old-aes-key>
```

An operator can edit that `0600` file during a controlled rotation, or seed it once by supplying a
base64-encoded properties file as `SKIT_AD_RETAINED_KEYRING_BASE64`. Supplying different encoded
contents after the file exists fails the release instead of silently rotating retained keys. Do not
put the encoded value in GitHub logs or commit the decoded file. Empty keyrings are normal, so
ordinary SaaS and App releases require no extra key-management step.
Retain an old provider payload key for at least seven days after a controlled rotation so delayed
capture rows remain decryptable; remove it only after the retention window and purge evidence are
complete.

The checked-in Compose stack is intentionally one host with one backend, one MySQL, and one Redis.
It may receive proven capture-only or `GATE_TEST` traffic, but it is not production route-issuance
evidence. `deploy/test-provider-impression-callback-gate.sh --environment ci` verifies only the
non-secret repository fixture. The `production-equivalent` mode must keep failing with the stable
single-host reason until operations supplies genuine, short-lived RSA-SHA256 evidence for every
cross-failure-domain check. Missing gate configuration is startup-safe and issue/submit
fail-closed; no boolean or profile bypass exists.

When a future HA environment has all 13 required evidence checks, its short-lived canonical
manifest is signed offline with RSA-SHA256. The operations private key must never reach this
repository, GitHub Actions, an image, the server, a database, a log, or an API. The deployment run
accepts only these four protected values:

- `SKIT_PROVIDER_IMPRESSION_GATE_ENVIRONMENT_FINGERPRINT`
- `SKIT_PROVIDER_IMPRESSION_GATE_OPERATIONS_PUBLIC_KEY` (canonical Base64 X.509 DER)
- `SKIT_PROVIDER_IMPRESSION_GATE_MANIFEST_BASE64`
- `SKIT_PROVIDER_IMPRESSION_GATE_SIGNATURE`

They are staged in the run-scoped mode-`0600` `server.env`, consumed and unlinked before any child
process, then written to a mode-`0600` Spring properties file under the mode-`0700`
`runtime-secrets` directory. Compose mounts that directory read-only. Activation removes the file
after the backend is healthy (and on every failure), and explicitly removes any legacy gate entries
from the persistent `.env`. A later container restart therefore remains capture-startup-safe while
production issue/submit returns the stable fail-closed denial until fresh evidence is injected.
Every issue and submit invocation rechecks the signature, TTL, route, HTTPS origin, path/template
versions, environment fingerprint, and deployment contract fingerprint.

Activation never infers from a missing local marker that database credentials are
legacy ciphertext, so ordinary releases and disaster recovery cannot silently clear
valid credentials. If an operator has confirmed that a database still contains values
encrypted with the old repository key, run one activation with
`SKIT_CLEAR_LEGACY_AD_CREDENTIALS=1`. That explicit maintenance action stops the
backend, clears only encrypted `app_key`/`secret` values, and disables those providers;
public advertising account metadata, members, revenue events, commission rules, and
ledgers remain intact. Re-enter provider credentials after that action, then omit the
switch from all normal releases.

Optional secrets:

- `SERVER_PORT`: SSH port, default `22`.
- `DEPLOY_PATH`: deployment directory, default `skit-saas` under the SSH user's home directory.
- `MYSQL_DATABASE`: database name, default `skit_saas`.
- `MYSQL_PORT`: host MySQL port, default `3306`.
- `REDIS_PORT`: host Redis port, default `6379`.
- `BACKEND_PORT`: host backend port, default `48080`.
- `BACKEND_HEALTH_PATH`: backend HTTP path used by deployment health checks, default `/actuator/health`.
- `FRONTEND_PORT`: host frontend port, default `80`.
- `SKIT_AD_RETAINED_KEYRING_BASE64`: optional one-time base64 seed for the retained-key properties file.

## Deployment order

1. Run the backend workflow whenever the shared Compose topology, database init SQL, or backend image changes. It uploads the canonical `docker-compose.prod.yml` and activates the backend stack.
2. Run the frontend workflow for frontend-only releases. It pulls and recreates only the Nginx frontend container, verifies the requested immutable image tag is running, and does not operate the backend container.
3. Run the app workflow if you want a server-side copy of the mobile source bundle.

The MySQL init SQL only runs when the MySQL volume is created for the first time. An existing
database is upgraded by the checksum-protected Skit application migrations during backend startup;
do not import the full bootstrap SQL into a populated database. If startup rejects an incompatible
schema, repair the reported drift and restart the same release instead of bypassing validation or
clearing the database.
