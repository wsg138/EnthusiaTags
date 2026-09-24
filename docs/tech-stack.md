# Advancement pilot: technical baseline

Java 25 build/runtime for the 26.2 pilot (Java 21 source/target), Maven, Paper API 26.2.build.124-stable, SQLite JDBC 3.53.2.0, JUnit Jupiter 5.11.4. Preserve the existing pinned EnthusiaLoreItems 1.0.0 release checksum. PlaceholderAPI and UnlimitedNameTags remain provided dependencies. The standalone Maven `../EnthusiaAdvancements/pilot` module compiles against the same Paper API and UltimateAdvancementAPI 2.8.1. Install that module into the workspace Maven cache before building Tags. RoseChat retains its fork's existing Paper 1.21.11 compile baseline; actual 26.2 integration requires staging.

EnthusiaTags owns challenge definitions, progress, claims and cosmetic selections. PlayTimePlugin is a read-only measurement provider. EnthusiaAdvancements is the intended native advancement presentation integration. Enthusia-RoseChat owns presence delivery and audience filtering.

SPEAR helpers are reused from the previously established ItemSignature workflow. Work stays local: no commits, pushes, PRs, releases, deployments or production database access. This overrides SPEAR's commit step. Java/JUnit architecture checks replace the Kotlin-only Konsist template to avoid introducing a second language solely for validation.

## Evidence

- `pom.xml`: actual dependency coordinates and pinned release verification.
- `src/main/java/org/enthusia/tags/rewards/RewardService.java`: claim and unlock lifecycle.
- `src/main/java/org/enthusia/tags/rewards/RewardStorage.java`: durable claims and component ledger.
- `src/main/java/org/enthusia/tags/cosmetics/CosmeticsListener.java`: current Bukkit message handling.
- Sibling `EnthusiaAdvancements/build.gradle.kts`: UltimateAdvancementAPI integration (verify integration surface before coding).
- Sibling `Enthusia-RoseChat/src/main/java/dev/rosewood/rosechat/listener/PlayerListener.java`: per-viewer presence delivery.
