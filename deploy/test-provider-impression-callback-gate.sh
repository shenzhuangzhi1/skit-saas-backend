#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="${repo_root}/deploy/docker-compose.prod.yml"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

usage() {
  echo "Usage: $0 --environment <ci|production-equivalent> [--draft-connection-id <positive-integer>]" >&2
  exit 2
}

environment=""
draft_connection_id=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --environment)
      [ "$#" -ge 2 ] || usage
      [ -z "${environment}" ] || usage
      environment="$2"
      shift 2
      ;;
    --draft-connection-id)
      [ "$#" -ge 2 ] || usage
      [ -z "${draft_connection_id}" ] || usage
      draft_connection_id="$2"
      shift 2
      ;;
    *)
      usage
      ;;
  esac
done

case "${environment}" in
  ci)
    draft_connection_id="${draft_connection_id:-42}"
    ;;
  production-equivalent)
    [ -n "${draft_connection_id}" ] || usage
    ;;
  *)
    usage
    ;;
esac
[[ "${draft_connection_id}" =~ ^[1-9][0-9]*$ ]] || usage

command -v openssl >/dev/null 2>&1 || fail "OpenSSL is required to verify signed gate evidence"

required_checks=(
  https_route
  inbox_attempt_200
  unknown_key_602
  log_redaction
  db_failpoint_503
  load_p99
  accepted_origin_contract
  dual_entry
  two_backend_instances
  mysql_ha
  redis_degradation
  dns_cert_backup
  key_custody
)

field_value() {
  local manifest="$1"
  local key="$2"
  printf '%s\n' "${manifest}" | sed -n "s/^${key}=//p"
}

validate_manifest_structure() {
  local manifest="$1"
  local expected_purpose="$2"
  local expected_environment_fingerprint="$3"
  local expected_connection_id="$4"
  local manifest_bytes
  local line_count
  local line_number
  local expected_key
  local actual_key
  local value
  local check
  local index
  local expected_keys=(
    manifest_version
    algorithm
    environment_fingerprint
    purpose
    provider_connection_id
    provider_route_id
    accepted_origin
    callback_path_version
    callback_template_version
    callback_contract_fingerprint
    issued_at
    expires_at
    evidence_id
  )

  for check in "${required_checks[@]}"; do
    expected_keys+=("check.${check}")
  done

  manifest_bytes="$(printf '%s\n' "${manifest}" | LC_ALL=C wc -c | tr -d '[:space:]')"
  [ "${manifest_bytes}" -le 8192 ] || return 1
  case "${manifest}" in
    *$'\r'*|*$'\t'*) return 1 ;;
  esac

  line_count="$(printf '%s\n' "${manifest}" | wc -l | tr -d '[:space:]')"
  [ "${line_count}" -eq "${#expected_keys[@]}" ] || return 1
  index=0
  while [ "${index}" -lt "${#expected_keys[@]}" ]; do
    line_number=$((index + 1))
    expected_key="${expected_keys[${index}]}"
    actual_key="$(printf '%s\n' "${manifest}" | sed -n "${line_number}p" | cut -d= -f1)"
    [ "${actual_key}" = "${expected_key}" ] || return 1
    value="$(field_value "${manifest}" "${expected_key}")"
    [ -n "${value}" ] || return 1
    [ "${#value}" -le 256 ] || return 1
    index=$((index + 1))
  done

  [ "$(field_value "${manifest}" manifest_version)" = "1" ] || return 1
  [ "$(field_value "${manifest}" algorithm)" = "RSA-SHA256" ] || return 1
  [ "$(field_value "${manifest}" environment_fingerprint)" = "${expected_environment_fingerprint}" ] \
    || return 1
  [ "$(field_value "${manifest}" purpose)" = "${expected_purpose}" ] || return 1
  [ "$(field_value "${manifest}" provider_connection_id)" = "${expected_connection_id}" ] \
    || return 1
  [[ "$(field_value "${manifest}" provider_route_id)" =~ ^[1-9][0-9]*$ ]] || return 1
  [[ "$(field_value "${manifest}" accepted_origin)" =~ ^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?/app-api$ ]] \
    || return 1
  [ "$(field_value "${manifest}" callback_path_version)" = "1" ] || return 1
  [ "$(field_value "${manifest}" callback_template_version)" = "1" ] || return 1
  [[ "$(field_value "${manifest}" callback_contract_fingerprint)" =~ ^[0-9a-f]{64}$ ]] \
    || return 1
  [[ "$(field_value "${manifest}" issued_at)" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] \
    || return 1
  [[ "$(field_value "${manifest}" expires_at)" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] \
    || return 1
  [[ "$(field_value "${manifest}" evidence_id)" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{7,127}$ ]] \
    || return 1
  for check in "${required_checks[@]}"; do
    [[ "$(field_value "${manifest}" "check.${check}")" =~ ^[0-9a-f]{64}$ ]] || return 1
  done
}

verify_signed_manifest() {
  local manifest="$1"
  local signature="$2"
  local public_key="$3"
  local expected_purpose="$4"
  local expected_environment_fingerprint="$5"
  local expected_connection_id="$6"
  local verification_root
  local manifest_file
  local signature_file
  local public_key_file
  local canonical_signature
  local verification_status=1

  validate_manifest_structure "${manifest}" "${expected_purpose}" \
    "${expected_environment_fingerprint}" "${expected_connection_id}" || return 1
  [[ "${signature}" =~ ^[A-Za-z0-9+/]+={0,2}$ ]] || return 1
  [ $(( ${#signature} % 4 )) -eq 0 ] || return 1

  verification_root="$(mktemp -d "${TMPDIR:-/tmp}/skit-provider-gate.XXXXXX")"
  manifest_file="${verification_root}/manifest"
  signature_file="${verification_root}/signature"
  public_key_file="${verification_root}/public.pem"
  printf '%s\n' "${manifest}" > "${manifest_file}"
  printf '%s\n' "${public_key}" > "${public_key_file}"
  if printf '%s' "${signature}" | openssl base64 -d -A > "${signature_file}" 2>/dev/null; then
    canonical_signature="$(openssl base64 -A -in "${signature_file}")"
    if [ "${canonical_signature}" = "${signature}" ] \
        && openssl dgst -sha256 -verify "${public_key_file}" \
          -signature "${signature_file}" "${manifest_file}" >/dev/null 2>&1; then
      verification_status=0
    fi
  fi
  rm -f "${manifest_file}" "${signature_file}" "${public_key_file}"
  rmdir "${verification_root}"
  return "${verification_status}"
}

ci_environment_fingerprint="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
ci_public_key="$(printf '%s\n' \
  '-----BEGIN PUBLIC KEY-----' \
  'MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAy+RP87RXn6QxjM8MEM/Z' \
  'U3kXtwwaMrfigDMBHmlsCCXbpGvHumqX3FIlCTYPLmaimeQ4gmIDLjMLxZhiyRoG' \
  '59vszhxdUyXQSegFi22WzO3+u/9Bde1qH7ylkZUaOO9irJtqYg7S5J1jvdGYOMMt' \
  'aHfvOXAn1csO3dtcr0wZ0cgulOFNnlxkI7+6JyxWP0c+ccwRDbB4N5HEheTfScRI' \
  'u8oHtVU6H8WjH+1qCmpE2kcEprG99vaKaGMLPjY5bUGHIwnebL4Y0rsToL2RKE5x' \
  'wnf1XiyjE+z1b61ZnX7/rJ8WL9d81+FmW68jpguoTcZnqg15tvCAFT3GU5ydeog6' \
  'TwIDAQAB' \
  '-----END PUBLIC KEY-----')"
ci_manifest="$(printf '%s\n' \
  'manifest_version=1' \
  'algorithm=RSA-SHA256' \
  "environment_fingerprint=${ci_environment_fingerprint}" \
  'purpose=GATE_TEST' \
  'provider_connection_id=42' \
  'provider_route_id=4242' \
  'accepted_origin=https://ci.invalid/app-api' \
  'callback_path_version=1' \
  'callback_template_version=1' \
  'callback_contract_fingerprint=fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210' \
  'issued_at=2026-08-03T00:00:00Z' \
  'expires_at=2026-08-03T00:10:00Z' \
  'evidence_id=ci-fixture-00000001' \
  'check.https_route=1111111111111111111111111111111111111111111111111111111111111111' \
  'check.inbox_attempt_200=2222222222222222222222222222222222222222222222222222222222222222' \
  'check.unknown_key_602=3333333333333333333333333333333333333333333333333333333333333333' \
  'check.log_redaction=4444444444444444444444444444444444444444444444444444444444444444' \
  'check.db_failpoint_503=5555555555555555555555555555555555555555555555555555555555555555' \
  'check.load_p99=6666666666666666666666666666666666666666666666666666666666666666' \
  'check.accepted_origin_contract=7777777777777777777777777777777777777777777777777777777777777777' \
  'check.dual_entry=8888888888888888888888888888888888888888888888888888888888888888' \
  'check.two_backend_instances=9999999999999999999999999999999999999999999999999999999999999999' \
  'check.mysql_ha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
  'check.redis_degradation=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' \
  'check.dns_cert_backup=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc' \
  'check.key_custody=dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd')"
ci_signature='eiQ6tfIOvnkib8okIe2XY1jZ91vg6uuD6xrM3NsjBs5inMiQOynW0TIod5xeXCawtj46povQgqIX7FJpRRxeviJLbNESst4Z3aimj4rds3mqoxespB9hFUtHEPc/8AWn4A7zcT50ZdWWPG968WtSdcgv1IamcE0jAJqo9D7nQUqLPkWDWrPlvCU3aD4+JwA/XCtgl5K30hE6FiIY/wQzK0laNMJlh160rpCeL468JBSPO5QkVOVZ3Xa6XWbHuNJ780Sua+ltDcIgy07YWIXP+PEKiVZm8SV3MDsFJwcVznSzN2agfcoCxuqAKoKfLQA3zUlZvQVTVFh5BSsqzww+Rg=='

if [ "${environment}" = "ci" ]; then
  [ "${draft_connection_id}" = "42" ] \
    || fail "CI fixture is bound to draft connection 42"
  verify_signed_manifest "${ci_manifest}" "${ci_signature}" "${ci_public_key}" \
    GATE_TEST "${ci_environment_fingerprint}" "${draft_connection_id}" \
    || fail "repository-only signed CI fixture is invalid"
  tampered_manifest="${ci_manifest/check.load_p99=6666666666666666666666666666666666666666666666666666666666666666/check.load_p99=eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee}"
  if verify_signed_manifest "${tampered_manifest}" "${ci_signature}" "${ci_public_key}" \
      GATE_TEST "${ci_environment_fingerprint}" "${draft_connection_id}"; then
    fail "tampered CI evidence was accepted"
  fi
  echo "PASS: signed repository fixture and invalid-fixture rejection verified; no route or key was issued"
  exit 0
fi

if grep -Fxq '  backend:' "${compose_file}" \
    && grep -Fxq '  mysql:' "${compose_file}" \
    && grep -Fxq '  redis:' "${compose_file}" \
    && grep -Fq 'container_name: skit-saas-backend' "${compose_file}" \
    && ! grep -Eq '^[[:space:]]+replicas:[[:space:]]*[2-9]' "${compose_file}"; then
  fail "required callback topology is single-host/single-backend; production key issuance is blocked"
fi

fail "production-equivalent signed evidence inspection is unavailable for an unrecognized topology"
