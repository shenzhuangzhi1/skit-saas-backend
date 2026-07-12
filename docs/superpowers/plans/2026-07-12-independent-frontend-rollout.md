# Independent Frontend Rollout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Release the frontend independently from backend image availability and verify the requested immutable image is running.

**Architecture:** The backend repository owns shared Compose topology. The frontend repository recreates only the `frontend` service and verifies its image identity and HTTP readiness.

**Tech Stack:** Bash, Docker Compose v2, Docker CLI, GitHub Actions, pnpm/Vite.

## Global Constraints

- Do not expose registry, SSH, database, or sudo credentials.
- A frontend rollout must not pull, create, restart, or inspect backend.
- Preserve the existing `DEPLOY_PATH`, `IMAGE_NAME`, `IMAGE_TAG`, and `FRONTEND_PORT` environment contract.
- Preserve the untracked `src/views/skit/admin/AdminTable 2.vue` file.

---

### Task 1: Add a failing frontend rollout contract test

**Files:**
- Create: `skit-saas-frontend/deploy/test-activate-frontend.sh`
- Test: `skit-saas-frontend/deploy/test-activate-frontend.sh`

**Interfaces:**
- Consumes: `skit-saas-frontend/deploy/activate-frontend.sh`.
- Produces: a mocked Docker/Curl contract test.

- [ ] **Step 1: Assert the desired Docker calls**

The test must run the activation script with fake `docker` and `curl` binaries
and assert these exact calls are recorded:

```bash
compose -f docker-compose.prod.yml --env-file .env pull frontend
compose -f docker-compose.prod.yml --env-file .env up -d --no-deps --force-recreate frontend
```

It must fail if the recorded calls contain `backend`.

- [ ] **Step 2: Prove the current script is red**

Run `bash deploy/test-activate-frontend.sh`.

Expected: failure because the current script uses `up -d frontend` and does not
validate the frontend container image.

### Task 2: Implement independent frontend activation

**Files:**
- Modify: `skit-saas-frontend/deploy/activate-frontend.sh`
- Test: `skit-saas-frontend/deploy/test-activate-frontend.sh`

**Interfaces:**
- Consumes: `IMAGE_NAME`, `IMAGE_TAG`, `FRONTEND_PORT`, `docker_cmd`, and `compose`.
- Produces: a rolling update that only operates on frontend.

- [ ] **Step 1: Recreate only frontend**

Replace the start operation with:

```bash
compose -f docker-compose.prod.yml --env-file .env up -d --no-deps --force-recreate frontend
```

- [ ] **Step 2: Verify the immutable image**

Add this check before the HTTP readiness loop:

```bash
expected_image="${IMAGE_NAME}:${IMAGE_TAG}"
actual_image="$(docker_cmd inspect --format '{{.Config.Image}}' skit-saas-frontend)"
if [ "${actual_image}" != "${expected_image}" ]; then
  echo "Frontend container image mismatch: expected ${expected_image}, got ${actual_image}"
  exit 1
fi
```

- [ ] **Step 3: Run the contract test green**

Run `bash deploy/test-activate-frontend.sh`.

Expected: exit code 0, with no `backend` calls in the mock log.

### Task 3: Correct and document the shared Compose topology

**Files:**
- Modify: `skit-saas-backend/deploy/docker-compose.prod.yml`
- Modify: `skit-saas-backend/deploy/README.md`

**Interfaces:**
- Consumes: the backend workflow that copies this Compose file to the server.
- Produces: a frontend service without a backend lifecycle dependency.

- [ ] **Step 1: Delete the frontend dependency**

Delete this block from the `frontend` service only:

```yaml
depends_on:
  - backend
```

- [ ] **Step 2: Validate the Compose model**

Run `MYSQL_ROOT_PASSWORD=test docker compose -f deploy/docker-compose.prod.yml config >/dev/null`.

Expected: exit code 0.

### Task 4: Publish and verify the release

**Files:**
- Modify: `skit-saas-backend/docs/superpowers/specs/2026-07-12-independent-frontend-rollout-design.md`
- Modify: `skit-saas-backend/docs/superpowers/plans/2026-07-12-independent-frontend-rollout.md`

**Interfaces:**
- Consumes: validated Compose and activation script changes.
- Produces: backend then frontend CI/CD releases.

- [ ] **Step 1: Run checks**

Run `pnpm ts:check`, `pnpm build:prod`, `bash deploy/test-activate-frontend.sh`, and `git diff --check` in the corresponding repositories.

- [ ] **Step 2: Deploy in topology-first order**

Commit and push backend Compose changes first. Commit and push the frontend
activation change second. Confirm the new frontend workflow succeeds and the
cache-busted login DOM no longer contains a tenant-name input.
