# Skit SaaS Production Deployment

This repository owns the shared production Docker Compose stack. The frontend
is a static Nginx service and has no runtime dependency on the backend
container; a frontend release must therefore never pull, recreate, or restart
the backend service.

The backend workflow publishes the Docker image to GitHub Container Registry
and the server pulls that image during activation. SSH only uploads Compose,
activation scripts, and database initialization SQL.

## Server prerequisites

- Linux server with Docker Engine and Docker Compose v2.
- SSH user that can run Docker commands.
- Empty deployment directory, default: `skit-saas` under the SSH user's home directory.

## Required GitHub Secrets

Set these secrets in each repository that deploys to the server:

- `SERVER_HOST`: server IP or domain.
- `SERVER_USER`: SSH user.
- `SERVER_SSH_KEY`: private key allowed to SSH into the server.
- `MYSQL_ROOT_PASSWORD`: production MySQL root password.

The backend activation script generates independent secure random values for the legacy
`SKIT_AD_ENCRYPTION_KEY`, credential-envelope `SKIT_AD_CREDENTIAL_KEY`, and advertising-session
signature `SKIT_AD_SESSION_TOKEN_KEY` on the first release. It assigns the envelope key id
`SKIT_AD_CREDENTIAL_KEY_ID=primary`, assigns the signature key version
`SKIT_AD_SESSION_TOKEN_KEY_VERSION=1`, and stores every value in the server-side `.env`; later
SaaS and App releases reuse them automatically. To use managed keys instead, inject the values
before the first backend activation. The session signature key must contain at least 32 safe
ASCII characters, its version must be a positive 32-bit integer, and it must not equal either
advertising encryption key. Never rotate a key without first completing the corresponding
credential re-encryption or active-session compatibility procedure.

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

## Deployment order

1. Run the backend workflow whenever the shared Compose topology, database init SQL, or backend image changes. It uploads the canonical `docker-compose.prod.yml` and activates the backend stack.
2. Run the frontend workflow for frontend-only releases. It pulls and recreates only the Nginx frontend container, verifies the requested immutable image tag is running, and does not operate the backend container.
3. Run the app workflow if you want a server-side copy of the mobile source bundle.

The MySQL init SQL only runs when the MySQL volume is created for the first time. If the database already exists, import `sql/mysql/skit-saas.sql` manually.
