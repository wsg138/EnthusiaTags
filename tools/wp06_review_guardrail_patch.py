#!/usr/bin/env python3
from pathlib import Path

APPROVED_SHA = "7c862b0ae545d710a33267ad6e19a4ae26d97323e97f40707c1475c9f9ba7063"


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one target, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


# Maven must verify the bytes it is about to install/use, not trust metadata alone.
path = "pom.xml"
file = Path(path)
text = file.read_text(encoding="utf-8")
needle = "    <plugins>\n"
if text.count(needle) != 1:
    raise RuntimeError("pom plugins: expected exactly one plugins block")
antrun = '''    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-antrun-plugin</artifactId>
        <version>3.1.0</version>
        <executions>
          <execution>
            <id>verify-pinned-loreitems-release</id>
            <phase>validate</phase>
            <goals>
              <goal>run</goal>
            </goals>
            <configuration>
              <target>
                <checksum file="${loreitems.release.jar}" algorithm="SHA-256"
                          property="loreitems.actual.sha256"/>
                <condition property="loreitems.release.sha256.matches">
                  <equals arg1="${loreitems.actual.sha256}" arg2="${loreitems.release.sha256}"/>
                </condition>
                <fail unless="loreitems.release.sha256.matches"
                      message="Pinned EnthusiaLoreItems release JAR checksum mismatch"/>
              </target>
            </configuration>
          </execution>
        </executions>
      </plugin>
'''
file.write_text(text.replace(needle, antrun, 1), encoding="utf-8")

# Contract test anchors the approved hash independently of the POM property.
path = "src/test/java/org/enthusia/tags/rewards/loreitems/ReleasedLoreItemsApiContractTest.java"
replace_once(path,
'''class ReleasedLoreItemsApiContractTest {
''',
'''class ReleasedLoreItemsApiContractTest {
    private static final String APPROVED_RELEASE_SHA256 = "''' + APPROVED_SHA + '''";
''', "approved release hash constant")
replace_once(path,
'''        String expectedSha = System.getProperty("loreitems.release.sha256");

        assertEquals(expectedSha, sha256(jar));
''',
'''        String configuredSha = System.getProperty("loreitems.release.sha256");

        assertEquals(APPROVED_RELEASE_SHA256, configuredSha,
            "the Maven release pin must match the approved production artifact");
        assertEquals(APPROVED_RELEASE_SHA256, sha256(jar),
            "the test artifact bytes must match the approved production artifact");
''', "hard-coded test checksum")

# Operator docs: configured ceiling and accurate verification wording.
path = "docs/loreitems-integration.md"
file = Path(path)
text = file.read_text(encoding="utf-8")
text = text.replace(
    "The test suite independently verifies the same checksum, the V1 API class entries, `API_VERSION == 1`, and the exact published status enum surface.",
    "The Maven build verifies the downloaded JAR against the pinned SHA-256 before installing/using it, and the test suite separately anchors that property and the test artifact bytes to the approved production SHA while checking the V1 API class entries, `API_VERSION == 1`, and published status enum surface.")
text = text.replace(
    "Retry delay starts at 5 seconds, doubles per attempt, and is capped at 5 minutes.",
    "Retry delay starts at 5 seconds, doubles per attempt, and is capped at 5 minutes. Automatic retries stop after `rewards.lore-items.max-auto-attempts` attempts (default 48) and move the operation to `REVIEW`; an explicit staff `loreretry` can resume it.")
file.write_text(text, encoding="utf-8")

# Durable handoff: avoid claiming an independent artifact download when Maven uses the same pinned file.
path = "docs/wp-06-loreitems-integration-handoff.md"
file = Path(path)
text = file.read_text(encoding="utf-8")
text = text.replace(
    "- Released V1 JAR checksum/API/status-enum contract.",
    "- Pinned V1 JAR checksum/API/status-enum contract, with Maven byte verification plus a hard-coded approved SHA in the contract test.")
text = text.replace(
    "- Pinned compilation/tests to the exact production LoreItems v1.0.0 JAR and checksum rather than a source checkout. Added checksum bootstrap and released-artifact V1 contract tests.",
    "- Pinned compilation/tests to the exact production LoreItems v1.0.0 JAR rather than a source checkout. The bootstrap and Maven validate phase verify its SHA-256, while the contract test hard-codes the approved production digest and API surface.")
file.write_text(text, encoding="utf-8")
