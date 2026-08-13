#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

RELEASE_VERSION="$(sed -n 's:.*<loreitems.release.version>\(.*\)</loreitems.release.version>.*:\1:p' pom.xml)"
RELEASE_ASSET_API_URL="$(sed -n 's:.*<loreitems.release.url>\(.*\)</loreitems.release.url>.*:\1:p' pom.xml)"
RELEASE_SHA="$(sed -n 's:.*<loreitems.release.sha256>\(.*\)</loreitems.release.sha256>.*:\1:p' pom.xml)"
DESTINATION="${ROOT_DIR}/.wp06-deps/EnthusiaLoreItems.jar"

if [[ -z "${RELEASE_VERSION}" || -z "${RELEASE_ASSET_API_URL}" || -z "${RELEASE_SHA}" ]]; then
  echo "LoreItems release pin is incomplete in pom.xml" >&2
  exit 1
fi

mkdir -p "$(dirname "${DESTINATION}")"
rm -f "${DESTINATION}"

echo "Downloading pinned EnthusiaLoreItems v${RELEASE_VERSION} release asset..."
curl --fail-with-body --silent --show-error --location --retry 3 --retry-delay 2 \
  --proto '=https' --tlsv1.2 \
  --header 'Accept: application/octet-stream' \
  --header 'X-GitHub-Api-Version: 2022-11-28' \
  --user-agent 'enthusiatags-wp06-ci' \
  "${RELEASE_ASSET_API_URL}" -o "${DESTINATION}"

ACTUAL_BYTES="$(wc -c < "${DESTINATION}" | tr -d ' ')"
echo "Downloaded ${ACTUAL_BYTES} bytes."
if [[ "${ACTUAL_BYTES}" -lt 1000000 ]]; then
  echo "Downloaded release asset is unexpectedly small; refusing to treat it as the production plugin JAR." >&2
  file "${DESTINATION}" >&2 || true
  head -c 512 "${DESTINATION}" >&2 || true
  echo >&2
  exit 1
fi

printf '%s  %s\n' "${RELEASE_SHA}" "${DESTINATION}" | sha256sum --check --strict

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

mvn --batch-mode --no-transfer-progress -f "${BOOTSTRAP_DIR}/pom.xml" \
  org.apache.maven.plugins:maven-install-plugin:3.1.3:install-file \
  -Dfile="${DESTINATION}" \
  -DgroupId=net.enthusia \
  -DartifactId=EnthusiaLoreItems-released \
  -Dversion="${RELEASE_VERSION}" \
  -Dpackaging=jar \
  -DgeneratePom=true

echo "Installed checksum-verified EnthusiaLoreItems v${RELEASE_VERSION} release into the local Maven repository."
