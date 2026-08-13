#!/usr/bin/env bash
set -euo pipefail

on_exit() {
  local status=$?
  if [[ ${status} -ne 0 ]]; then
    printf '::error title=LoreItems release bootstrap failed::line=%s command=%s\n' "${BASH_LINENO[0]:-unknown}" "${BASH_COMMAND:-unknown}"
  fi
  exit "${status}"
}
trap on_exit EXIT

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

RELEASE_VERSION="$(sed -n 's:.*<loreitems.release.version>\(.*\)</loreitems.release.version>.*:\1:p' pom.xml | head -n 1)"
RELEASE_URL="$(sed -n 's:.*<loreitems.release.url>\(.*\)</loreitems.release.url>.*:\1:p' pom.xml | head -n 1)"
RELEASE_SHA="$(sed -n 's:.*<loreitems.release.sha256>\(.*\)</loreitems.release.sha256>.*:\1:p' pom.xml | head -n 1)"
DESTINATION="${ROOT_DIR}/.wp06-deps/EnthusiaLoreItems.jar"

if [[ -z "${RELEASE_VERSION}" || -z "${RELEASE_URL}" || -z "${RELEASE_SHA}" ]]; then
  echo "LoreItems release pin is incomplete in pom.xml" >&2
  exit 1
fi

mkdir -p "$(dirname "${DESTINATION}")"
rm -f "${DESTINATION}"

echo "Downloading pinned EnthusiaLoreItems v${RELEASE_VERSION} production release..."
curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 --max-time 120 \
  --user-agent 'enthusiatags-wp06-ci' \
  "${RELEASE_URL}" -o "${DESTINATION}"

ACTUAL_BYTES="$(wc -c < "${DESTINATION}" | tr -d ' ')"
echo "Downloaded ${ACTUAL_BYTES} bytes."
if [[ "${ACTUAL_BYTES}" -lt 1000000 ]]; then
  echo "Downloaded release asset is unexpectedly small; refusing to treat it as the production plugin JAR." >&2
  file "${DESTINATION}" >&2 || true
  head -c 512 "${DESTINATION}" >&2 || true
  echo >&2
  exit 1
fi

echo "Verifying pinned production SHA-256..."
ACTUAL_SHA="$(sha256sum "${DESTINATION}" | awk '{print $1}')"
if [[ "${ACTUAL_SHA}" != "${RELEASE_SHA}" ]]; then
  echo "Release checksum mismatch: expected=${RELEASE_SHA} actual=${ACTUAL_SHA} bytes=${ACTUAL_BYTES}" >&2
  printf '::error title=LoreItems actual SHA %s bytes %s::expected %s\n' \
    "${ACTUAL_SHA}" "${ACTUAL_BYTES}" "${RELEASE_SHA}"
  exit 1
fi
echo "${DESTINATION}: OK"

BOOTSTRAP_DIR="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/enthusiatags-loreitems-bootstrap"
mkdir -p "${BOOTSTRAP_DIR}"
cat > "${BOOTSTRAP_DIR}/pom.xml" <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.enthusia.bootstrap</groupId>
  <artifactId>loreitems-release-bootstrap</artifactId>
  <version>1</version>
</project>
POM

echo "Installing checksum-verified release into the runner-local Maven repository..."
mvn --batch-mode --no-transfer-progress -f "${BOOTSTRAP_DIR}/pom.xml" \
  org.apache.maven.plugins:maven-install-plugin:3.1.3:install-file \
  -Dfile="${DESTINATION}" \
  -DgroupId=net.enthusia \
  -DartifactId=EnthusiaLoreItems-released \
  -Dversion="${RELEASE_VERSION}" \
  -Dpackaging=jar \
  -DgeneratePom=true

echo "Installed checksum-verified EnthusiaLoreItems v${RELEASE_VERSION} release into the local Maven repository."
