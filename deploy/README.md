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

The backend activation script generates `SKIT_AD_ENCRYPTION_KEY` with a secure random
value on the first release and stores it in the server-side `.env`; later SaaS and App
releases reuse it automatically. To use a managed key instead, inject
`SKIT_AD_ENCRYPTION_KEY` before the first backend activation. Never rotate it without
first re-encrypting or clearing saved advertising credentials.

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
