#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ -d "${repo_root}/.git" ]] || { echo "Run this script from a Git checkout." >&2; exit 1; }
[[ -x "${repo_root}/.githooks/pre-push" ]] || { echo "The pre-push hook is missing or not executable." >&2; exit 1; }

git -C "${repo_root}" config core.hooksPath .githooks
echo "Configured $(git -C "${repo_root}" config --get core.hooksPath) for ${repo_root}."
