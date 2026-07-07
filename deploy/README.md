# Skit SaaS Production Deployment

This repository owns the shared production Docker Compose stack.

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

1. Run the backend workflow first. It uploads `docker-compose.prod.yml`, database init SQL, and the backend image.
2. Run the frontend workflow. It uploads and starts the Nginx frontend image in the same Compose project.
3. Run the app workflow if you want a server-side copy of the mobile source bundle.

The MySQL init SQL only runs when the MySQL volume is created for the first time. If the database already exists, import `sql/mysql/skit-saas.sql` manually.
