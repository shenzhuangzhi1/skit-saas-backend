# Independent Frontend Rollout Design

## Problem

Frontend CI/CD run #25 built and pushed the frontend image, but its server
activation attempted to pull the backend image. The backend pull was denied,
leaving the old frontend container running.

## Design

The backend repository remains the only owner of the shared Compose topology.
A static frontend does not need a backend process to start, so the frontend
service has no `depends_on` relationship to backend.

The frontend repository owns the rolling update operation. It pulls and
recreates only `frontend` using `--no-deps --force-recreate`, validates that the
container is configured with `${IMAGE_NAME}:${IMAGE_TAG}`, then checks the HTTP
endpoint.

## Tenant identity contract

- Management users log in by a globally unique username. The backend resolves
  the bound tenant and never accepts a tenant selection from the login page.
- Short-drama members register through an invitation code, which is the only
  input that determines the target agent tenant. Their mobile number is a
  globally unique login identity; it maps to exactly one tenant at login.
- The member app and API no longer expose or accept an agent/tenant code during
  login. Existing duplicate mobile bindings are rejected rather than resolved
  by asking the user to choose a tenant.
- The schema initializer and initial MySQL schema both enforce the global
  member-mobile unique index.

## Constraints

- Do not print registry, SSH, database, or sudo credentials.
- A frontend rollout must not pull, create, restart, or inspect backend.
- Retain `DEPLOY_PATH`, `IMAGE_NAME`, `IMAGE_TAG`, and `FRONTEND_PORT`.
- An image-identity or HTTP failure must fail GitHub Actions.

## Acceptance Criteria

1. The activation command is `up -d --no-deps --force-recreate frontend`.
2. Shared Compose declares no frontend-to-backend lifecycle dependency.
3. A deployment succeeds only when the running frontend image equals the
   requested immutable tag and the frontend endpoint responds.
4. An executable contract test catches a backend invocation, stale image, or
   unavailable frontend endpoint.
