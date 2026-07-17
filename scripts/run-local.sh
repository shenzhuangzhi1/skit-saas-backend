#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${repo_root}/deploy/local.env"

"${repo_root}/scripts/local-stack.sh" up
source "${env_file}"

java_home="${JAVA_HOME:-}"
if [[ -x /usr/libexec/java_home ]]; then
  java_home="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)"
fi
[[ -n "${java_home}" ]] || { echo "Set JAVA_HOME to a supported JDK before starting Spring Boot." >&2; exit 1; }
export JAVA_HOME="${java_home}"
export LOCAL_MYSQL_MASTER_PASSWORD
export LOCAL_MYSQL_SLAVE_PASSWORD
export SKIT_AD_ENCRYPTION_KEY
export SKIT_AD_CREDENTIAL_KEY
export SKIT_AD_SESSION_TOKEN_KEY

cd "${repo_root}"
mvn -B -pl yudao-server -am -DskipTests compile

dependency_plugin_version="${MAVEN_DEPENDENCY_PLUGIN_VERSION:-3.7.0}"
runtime_classpath_file="$(mktemp "${TMPDIR:-/tmp}/skit-runtime-classpath.XXXXXX")"
cleanup() {
  rm -f "${runtime_classpath_file}"
}
trap cleanup EXIT INT TERM

if ! mvn -B -o -pl yudao-server -DincludeScope=runtime \
    -Dmdep.outputFile="${runtime_classpath_file}" \
    "org.apache.maven.plugins:maven-dependency-plugin:${dependency_plugin_version}:build-classpath" \
    >/dev/null 2>&1; then
  mvn -B -pl yudao-server -DincludeScope=runtime \
    -Dmdep.outputFile="${runtime_classpath_file}" \
    "org.apache.maven.plugins:maven-dependency-plugin:${dependency_plugin_version}:build-classpath"
fi

runtime_classpath_dirs="$(find yudao-framework yudao-module-system yudao-module-infra yudao-module-skit yudao-server \
  -type d -path '*/target/classes' -print | sort | paste -sd: -)"
while IFS= read -r classes_dir; do
  # A few interrupted local Maven copies leave duplicate class files such as Foo 2.class.
  # They are ignored by compilation but make Spring's classpath scanner needlessly slow.
  find "${classes_dir}" -type f -name '* [0-9].class' -delete
done < <(find yudao-framework yudao-module-system yudao-module-infra yudao-module-skit yudao-server \
  -type d -path '*/target/classes' -print | sort)

runtime_classpath="${runtime_classpath_dirs}:$(cat "${runtime_classpath_file}")"
java -Dfile.encoding=UTF-8 -cp "${runtime_classpath}" \
  cn.iocoder.yudao.server.YudaoServerApplication --spring.profiles.active=local
