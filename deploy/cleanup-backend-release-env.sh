#!/usr/bin/env bash
set -euo pipefail

: "${DEPLOY_PATH:?DEPLOY_PATH is required}"
: "${RELEASE_ID:?RELEASE_ID is required}"

if [[ ! "${RELEASE_ID}" =~ ^[0-9A-Fa-f]{40}-[0-9]+-[0-9]+$ ]]; then
  echo "RELEASE_ID must identify one commit, workflow run, and rerun attempt." >&2
  exit 1
fi

release_dir="${DEPLOY_PATH%/}/releases/backend-${RELEASE_ID}"
if [ -L "${release_dir}" ]; then
  echo "The staged backend release directory must not be a symlink." >&2
  exit 1
fi

rm -f -- "${release_dir}/server.env"
