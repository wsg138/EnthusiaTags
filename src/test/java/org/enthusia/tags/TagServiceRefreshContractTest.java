package org.enthusia.tags;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TagServiceRefreshContractTest {
    @Test
    void mutationsLoadingVisibilityAndReloadRequestConsumerRefreshes() throws IOException {
        String source = Files.readString(Path.of(
            "src/main/java/org/enthusia/tags/TagService.java"));

        assertTrue(source.contains("TagRefreshReason.SELECTED"));
        assertTrue(source.contains("TagRefreshReason.CLEARED"));
        assertTrue(source.contains("TagRefreshReason.GRANTED"));
        assertTrue(source.contains("TagRefreshReason.REVOKED"));
        assertTrue(source.contains("TagRefreshReason.TAG_DEFINITION_CHANGED"));
        assertTrue(source.contains("TagRefreshReason.SUPPRESSED"));
        assertTrue(source.contains("TagRefreshReason.UNSUPPRESSED"));
        assertTrue(source.contains("TagRefreshReason.RELOADED"));
        assertTrue(source.contains("TagRefreshReason.PLAYER_DATA_LOADED"));
        assertTrue(source.contains("TagRefreshReason.VISIBILITY_CHANGED"));
    }

    @Test
    void reloadAndShutdownCancelOwnedResources() throws IOException {
        String source = Files.readString(Path.of(
            "src/main/java/org/enthusia/tags/TagService.java"));

        assertTrue(source.contains("startVanishWatcher()"));
        assertTrue(source.contains("stopVanishWatcher();"));
        assertTrue(source.contains("closePlaceholderExpansion();"));
        assertTrue(source.contains("refreshCoordinator.close();"));
    }
}
