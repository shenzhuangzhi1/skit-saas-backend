#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
workflow="${repo_root}/.github/workflows/cicd.yml"
activation="${repo_root}/deploy/activate-backend.sh"
release_env_cleanup="${repo_root}/deploy/cleanup-backend-release-env.sh"
known_hosts="${repo_root}/deploy/known_hosts"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

grep -Fq "if: github.ref == 'refs/heads/master'" "${workflow}" \
  || fail "manual backend deployment is not restricted to master"
if grep -Fq 'ssh-keyscan' "${workflow}"; then
  fail "backend deployment still trusts a network-discovered SSH host key"
fi
grep -Fq 'install -m 600 deploy/known_hosts ~/.ssh/known_hosts' "${workflow}" \
  || fail "backend deployment does not install the pinned SSH host key"
[[ -s "${known_hosts}" ]] || fail "pinned backend SSH known_hosts file is missing"
grep -Fq '124.221.50.30 ssh-ed25519 ' "${known_hosts}" \
  || fail "pinned backend SSH host key does not cover the production host"

rg -q 'RELEASE_ID: \$\{\{ github\.sha \}\}-\$\{\{ github\.run_id \}\}-\$\{\{ github\.run_attempt \}\}' "${workflow}" \
  || fail "backend release staging is not bound to commit, workflow run, and rerun attempt"
grep -Fq 'releases/backend-${RELEASE_ID}' "${workflow}" \
  || fail "backend artifacts do not use the run-unique release identifier"
if grep -Fq 'releases/backend-${IMAGE_TAG}' "${workflow}"; then
  fail "backend release staging can be reused across reruns of the same commit"
fi
rg -q 'mkdir -p .*DEPLOY_PATH.*/releases.*&& mkdir -- .*RELEASE_BUNDLE_PATH' "${workflow}" \
  || fail "backend upload does not atomically reject an existing release directory"
grep -Fq 'chmod 600 .deploy/server.env' "${workflow}" \
  || fail "local backend server.env is not protected before upload"
grep -Fq 'rm -f .deploy/server.env' "${workflow}" \
  || fail "local backend server.env is not removed on every exit"
for secret_argv_binding in \
  'GHCR_TOKEN=${GHCR_TOKEN_Q}' \
  'SUDO_PASSWORD=${SUDO_PASSWORD_Q}'; do
  if grep -Fq "${secret_argv_binding}" "${workflow}"; then
    fail "backend remote SSH argv contains ${secret_argv_binding%%=*}"
  fi
done
for staged_secret in GHCR_TOKEN SUDO_PASSWORD; do
  grep -Fq "printf '${staged_secret}=%q\\n'" "${workflow}" \
    || fail "backend ${staged_secret} is not staged in the protected release environment"
done
secret_unlink_line="$(rg -n -F -m 1 'rm -f -- "${server_env_file}"' "${activation}" | cut -d: -f1 || true)"
secret_source_line="$(rg -n -F -m 1 '. "${server_env_file}"' "${activation}" | cut -d: -f1 || true)"
docker_access_line="$(rg -n -F -m 1 'prepare_docker_access' "${activation}" | tail -n 1 | cut -d: -f1 || true)"
[[ -n "${secret_source_line}" && -n "${secret_unlink_line}" && -n "${docker_access_line}" ]] \
  || fail "backend activation does not source, unlink, and then consume release secrets"
(( secret_source_line < secret_unlink_line && secret_unlink_line < docker_access_line )) \
  || fail "backend release environment is not unlinked immediately after sourcing"

[[ -x "${release_env_cleanup}" ]] \
  || fail "run-scoped backend server.env cleanup helper is missing"
bash -n "${release_env_cleanup}" \
  || fail "run-scoped backend server.env cleanup helper has invalid shell syntax"
cleanup_test_root="$(mktemp -d)"
cleanup_test_release="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-12345-1"
cleanup_other_attempt="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-12345-2"
cleanup_other_run="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-54321-1"
cleanup_test_finish() {
  rm -rf "${cleanup_test_root}"
}
trap cleanup_test_finish EXIT
mkdir -p \
  "${cleanup_test_root}/releases/backend-${cleanup_test_release}" \
  "${cleanup_test_root}/releases/backend-${cleanup_other_attempt}" \
  "${cleanup_test_root}/releases/backend-${cleanup_other_run}"
printf 'target\n' > "${cleanup_test_root}/releases/backend-${cleanup_test_release}/server.env"
printf 'other-attempt\n' > "${cleanup_test_root}/releases/backend-${cleanup_other_attempt}/server.env"
printf 'other-run\n' > "${cleanup_test_root}/releases/backend-${cleanup_other_run}/server.env"
printf 'current\n' > "${cleanup_test_root}/server.env"
DEPLOY_PATH="${cleanup_test_root}" RELEASE_ID="${cleanup_test_release}" \
  "${release_env_cleanup}"
[[ ! -e "${cleanup_test_root}/releases/backend-${cleanup_test_release}/server.env" ]] \
  || fail "run-scoped cleanup retained this run attempt's server.env"
[[ -e "${cleanup_test_root}/releases/backend-${cleanup_other_attempt}/server.env" ]] \
  || fail "run-scoped cleanup removed another rerun attempt's server.env"
[[ -e "${cleanup_test_root}/releases/backend-${cleanup_other_run}/server.env" ]] \
  || fail "run-scoped cleanup removed another workflow run's server.env"
[[ -e "${cleanup_test_root}/server.env" ]] \
  || fail "run-scoped cleanup removed the current deployment server.env"

grep -Fq '.deploy/cleanup-backend-release-env.sh' "${workflow}" \
  || fail "backend release does not stage the run-scoped cleanup helper"
grep -Fq 'cleanup_remote_server_env() {' "${workflow}" \
  || fail "backend remote activation has no early server.env cleanup wrapper"
remote_preflight_line="$(rg -n -m 1 'chmod 600 -- "\$\{remote_server_env_file\}"' "${workflow}" | cut -d: -f1 || true)"
[[ -n "${remote_preflight_line}" ]] \
  || fail "backend remote activation has no protected server.env preflight"
for remote_trap in \
  "trap 'cleanup_remote_server_env \$?' EXIT" \
  "trap 'cleanup_remote_server_env 129' HUP" \
  "trap 'cleanup_remote_server_env 130' INT" \
  "trap 'cleanup_remote_server_env 143' TERM"; do
  remote_trap_line="$(rg -n -F -m 1 "${remote_trap}" "${workflow}" | cut -d: -f1 || true)"
  [[ -n "${remote_trap_line}" ]] \
    || fail "backend remote activation does not install ${remote_trap}"
  (( remote_trap_line < remote_preflight_line )) \
    || fail "backend remote activation installs ${remote_trap} after preflight"
done
grep -Fq "if: always() && steps.deploy_config.outputs.enabled == 'true'" "${workflow}" \
  || fail "backend has no independent always-run remote secret cleanup"
grep -Fq 'DEPLOY_PATH=${DEPLOY_PATH_Q} RELEASE_ID=${RELEASE_ID_Q} bash ${REMOTE_CLEANUP_SCRIPT_Q}' "${workflow}" \
  || fail "independent backend cleanup is not scoped to this release ID"

grep -Fq '.deploy.lock' "${activation}" \
  || fail "backend activation does not share the deployment lock"
grep -Fq 'flock -w' "${activation}" \
  || fail "backend activation does not wait for the deployment lock"
grep -Fq 'rm -f "${server_env_file}"' "${activation}" \
  || fail "backend activation does not destroy the uploaded server.env"
global_runtime_key_name='SKIT_RUNTIME_UPDATE_'"PUBLIC_KEY"
if rg --hidden -q "${global_runtime_key_name}" "${repo_root}" \
    --glob '!**/.git/**' --glob '!**/target/**'; then
  fail "the repository still documents or configures a global runtime-update trust root"
fi

grep -Fq 'yudao-module-skit,yudao-module-system,yudao-module-infra' "${workflow}" \
  || fail "focused CI does not include infrastructure security tests"
for selector in 'Taku*Test' 'SpringUtilsTest' 'AdminServerConfigurationTest' \
  'ApiAccessLogInterceptorTest' '*SecretRedactionTest'; do
  grep -Fq "${selector}" "${workflow}" \
    || fail "focused CI misses ${selector}"
done
grep -Fq './deploy/test-release-security.sh' "${workflow}" \
  || fail "backend CI does not run the release-security contract"

echo "PASS: backend release security contracts are enforced"
