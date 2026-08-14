package org.enthusia.tags.rewards.loreitems;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoreItemsArchitectureTest {
    private static final Path MAIN_JAVA = Path.of("src/main/java");

    @Test
    void onlyReleasedV1LoreItemsPackagesAreImported() throws Exception {
        for (Path source : javaSources()) {
            for (String line : Files.readAllLines(source)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("import net.enthusia.loreitems.")) {
                    assertTrue(trimmed.startsWith("import net.enthusia.loreitems.api.v1."),
                        () -> source + " imports a non-public LoreItems package: " + trimmed);
                }
            }
        }
    }

    @Test
    void repositoryContainsNoLoreItemsCommandOrDatabaseFallback() throws Exception {
        for (Path source : javaSources()) {
            String text = Files.readString(source).toLowerCase(Locale.ROOT);
            assertFalse(text.contains("/loreitems"),
                () -> source + " contains a forbidden LoreItems command fallback");
            assertFalse(text.contains("net.enthusia.loreitems.plugin"),
                () -> source + " depends on LoreItems plugin implementation classes");
            assertFalse(text.contains("net.enthusia.loreitems.application"),
                () -> source + " depends on LoreItems application implementation classes");
            assertFalse(text.contains("net.enthusia.loreitems.domain"),
                () -> source + " depends on LoreItems domain implementation classes");
        }
    }

    @Test
    void serviceAdapterDoesNotBlockOrTouchBukkitGameplayState() throws Exception {
        String source = Files.readString(MAIN_JAVA.resolve(
            "org/enthusia/tags/rewards/loreitems/BukkitLoreItemsClient.java"));

        assertFalse(source.contains(".join("), "service adapter must not synchronously join service stages");
        assertFalse(source.contains("toCompletableFuture().get("),
            "service adapter must not synchronously get service stages");
        assertFalse(source.contains("Future.get("),
            "service adapter must not synchronously wait on Future.get");
        assertFalse(source.contains("Thread.sleep"), "service adapter must not sleep while waiting for LoreItems");
        assertFalse(source.contains("Bukkit."), "service adapter must not touch Bukkit gameplay state");
        assertTrue(source.contains("whenComplete"), "service adapter should compose the returned CompletionStage");
        assertTrue(source.contains("orTimeout"), "service adapter must bound an unresolved service stage");
    }

    @Test
    void runtimeAndPublishWorkflowKeepRecoveryBounded() throws Exception {
        String runtime = Files.readString(MAIN_JAVA.resolve(
            "org/enthusia/tags/rewards/loreitems/LoreItemRewardRuntime.java"));
        String rewardService = Files.readString(MAIN_JAVA.resolve(
            "org/enthusia/tags/rewards/RewardService.java"));
        String publishWorkflow = Files.readString(Path.of(".github/workflows/publish-latest.yml"));

        assertTrue(runtime.contains("RejectedExecutionException"),
            "runtime submissions must turn executor shutdown races into failed futures");
        assertTrue(runtime.contains("awaitTermination"),
            "runtime must wait for LoreItems workers before closing SQLite");
        assertTrue(rewardService.contains("return RewardClaimResult.CLAIM_IN_PROGRESS;"),
            "recoverable LoreItems CLAIM_PENDING rows must return pending instead of rewriting the ledger");
        int bootstrap = publishWorkflow.indexOf("bash tools/bootstrap_loreitems_release.sh");
        int maven = publishWorkflow.indexOf("mvn --batch-mode --no-transfer-progress clean test package");
        assertTrue(bootstrap >= 0 && maven > bootstrap,
            "publish-latest must bootstrap the pinned LoreItems artifact before Maven");
    }

    @Test
    void rewardClaimWorkerOwnsTheOnlyLoreItemWait() throws Exception {
        String source = Files.readString(MAIN_JAVA.resolve(
            "org/enthusia/tags/rewards/RewardService.java"));
        String loreMethod = between(
            source,
            "private LoreItemDeliveryAttempt deliverLoreItem(",
            "private static String blankAuditValue");

        assertTrue(source.contains("claimExecutor.execute(() ->"),
            "reward claims must execute on the dedicated claim executor");
        assertTrue(source.contains("case LORE_ITEM ->"),
            "LORE_ITEM must be handled by the durable reward claim state machine");
        assertFalse(loreMethod.contains("callOnMain("),
            "LoreItems service waiting must not be moved onto the Paper main thread");
        assertFalse(loreMethod.contains("Bukkit."),
            "LoreItems service waiting must not access Bukkit gameplay state");
    }

    private static List<Path> javaSources() throws IOException {
        try (var paths = Files.walk(MAIN_JAVA)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0, "missing source marker: " + startMarker);
        assertTrue(end > start, "missing source marker: " + endMarker);
        return source.substring(start, end);
    }
}
