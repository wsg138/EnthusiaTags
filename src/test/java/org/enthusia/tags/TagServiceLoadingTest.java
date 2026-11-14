package org.enthusia.tags;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the real service at its persisted ownership boundary without a live server. */
class TagServiceLoadingTest {
    private final UUID playerId = UUID.randomUUID();

    @Test
    void readingBeforePreloadMustNotReplaceSavedOwnershipWithAnEmptyBaseline() throws Exception {
        TagStorage storage = mock(TagStorage.class);
        when(storage.loadNow(playerId)).thenReturn(saved("baltop_elite"));
        when(storage.loadAsync(playerId)).thenReturn(new CompletableFuture<>());
        TagService service = service(storage);

        service.getPlayerData(playerId); // A menu reads before storage has supplied a snapshot.
        service.preloadPlayerBlocking(playerId);

        verify(storage).loadNow(playerId);
        assertEquals(Set.of("baltop_elite"), service.getPlayerData(playerId).getOwnedTags());
        assertEquals("baltop_elite", service.getPlayerData(playerId).getSelectedTag());
    }

    @Test
    void aFailedLoadAndAnEmptyMenuReadMustNotSuppressTheNextSuccessfulLoad() throws Exception {
        TagStorage storage = mock(TagStorage.class);
        when(storage.loadNow(playerId)).thenThrow(new SQLException("temporary read failure"))
            .thenReturn(saved("baltop_elite"));
        when(storage.loadAsync(playerId)).thenReturn(new CompletableFuture<>());
        TagService service = service(storage);

        service.preloadPlayerBlocking(playerId);
        service.getPlayerData(playerId);
        service.preloadPlayerBlocking(playerId);

        verify(storage, times(2)).loadNow(playerId);
        assertEquals(Set.of("baltop_elite"), service.getPlayerData(playerId).getOwnedTags());
    }

    @Test
    void anOldSessionLoadCannotReplaceTheCurrentSessionsCompletedSnapshot() throws Exception {
        TagStorage storage = mock(TagStorage.class);
        CompletableFuture<TagStorage.StoredTagData> oldRead = new CompletableFuture<>();
        CompletableFuture<TagStorage.StoredTagData> currentRead = new CompletableFuture<>();
        when(storage.loadAsync(playerId)).thenReturn(oldRead, currentRead);
        TagService service = service(storage);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);

        service.preloadPlayer(playerId);
        service.unloadPlayer(player);
        service.preloadPlayer(playerId);
        currentRead.complete(saved("baltop_elite"));
        oldRead.complete(saved("old_snapshot"));

        assertEquals(Set.of("baltop_elite"), service.getPlayerData(playerId).getOwnedTags());
        assertEquals("baltop_elite", service.getPlayerData(playerId).getSelectedTag());
    }

    @Test
    void readingAfterAFailedLoadStartsARetryWithoutCachingAFalseEmptySnapshot() throws Exception {
        TagStorage storage = mock(TagStorage.class);
        when(storage.loadNow(playerId)).thenThrow(new SQLException("temporary read failure"));
        CompletableFuture<TagStorage.StoredTagData> retry = new CompletableFuture<>();
        when(storage.loadAsync(playerId)).thenReturn(retry);
        TagService service = service(storage);
        service.preloadPlayerBlocking(playerId);

        assertTrue(service.getPlayerData(playerId).getOwnedTags().isEmpty());
        verify(storage).loadAsync(playerId);
        retry.complete(saved("baltop_elite"));
        assertEquals(Set.of("baltop_elite"), service.getPlayerData(playerId).getOwnedTags());
        verify(storage, never()).grantTagAsync(any(), any());
        verify(storage, never()).revokeTagAsync(any(), any());
        verify(storage, never()).setSelectedTagAsync(any(), any());
    }

    @Test
    void anImmediateLoadCompletionPublishesDataAndRemovesItsOwnPendingEntry() throws Exception {
        TagStorage storage = mock(TagStorage.class);
        when(storage.loadAsync(playerId)).thenReturn(CompletableFuture.completedFuture(saved("baltop_elite")));
        TagService service = service(storage);

        assertDoesNotThrow(() -> service.preloadPlayer(playerId));
        assertEquals(Set.of("baltop_elite"), service.getPlayerData(playerId).getOwnedTags());
        assertTrue(pending(service).isEmpty(), "A synchronously completed future must not strand pending work");
    }

    @Test
    void genuinelyEmptySuccessfulOwnershipIsLoadedOnceAndIsNotRetriedAsAFailure() throws Exception {
        TagStorage storage = mock(TagStorage.class);
        CompletableFuture<TagStorage.StoredTagData> read = new CompletableFuture<>();
        when(storage.loadAsync(playerId)).thenReturn(read);
        TagService service = service(storage);
        service.preloadPlayer(playerId);
        service.preloadPlayer(playerId);
        read.complete(new TagStorage.StoredTagData(Set.of(), null));

        service.getPlayerData(playerId);
        service.preloadPlayer(playerId);
        verify(storage, times(1)).loadAsync(playerId);
        assertTrue(service.getPlayerData(playerId).getOwnedTags().isEmpty());
        assertNull(service.getPlayerData(playerId).getSelectedTag());
    }

    @Test
    void aStaleCompletionCannotRemoveTheNextSessionsPendingLoad() throws Exception {
        TagStorage storage = mock(TagStorage.class);
        CompletableFuture<TagStorage.StoredTagData> oldRead = new CompletableFuture<>();
        CompletableFuture<TagStorage.StoredTagData> newRead = new CompletableFuture<>();
        when(storage.loadAsync(playerId)).thenReturn(oldRead, newRead);
        TagService service = service(storage);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        service.preloadPlayer(playerId);
        service.unloadPlayer(player);
        service.preloadPlayer(playerId);
        Object currentPending = pending(service).get(playerId);

        oldRead.complete(saved("old_snapshot"));
        assertSame(currentPending, pending(service).get(playerId));
        assertTrue(service.getPlayerData(playerId).getOwnedTags().isEmpty());
        newRead.complete(saved("baltop_elite"));
        assertEquals(Set.of("baltop_elite"), service.getPlayerData(playerId).getOwnedTags());
    }

    @Test
    void grantingDuringLoadPreservesPreviouslyPersistedTagsAndSelection() throws Exception {
        TagStorage storage = mock(TagStorage.class);
        CompletableFuture<TagStorage.StoredTagData> read = new CompletableFuture<>();
        when(storage.loadAsync(playerId)).thenReturn(read);
        when(storage.grantTagAsync(playerId, "new_tag")).thenReturn(CompletableFuture.completedFuture(null));
        TagService service = service(storage);
        service.getRegistry().register(new TagDefinition("new_tag", "New", "New", org.bukkit.Material.PAPER, java.util.List.of()));

        CompletableFuture<Boolean> granted = service.grantTagPersisted(playerId, "new_tag");
        assertFalse(granted.isDone());
        verify(storage, never()).grantTagAsync(any(), any());
        read.complete(saved("baltop_elite"));

        assertTrue(granted.join());
        assertEquals(Set.of("baltop_elite", "new_tag"), service.getPlayerData(playerId).getOwnedTags());
        assertEquals("baltop_elite", service.getPlayerData(playerId).getSelectedTag());
    }

    @Test
    void aFailedGrantDoesNotDiscardPreviouslyPersistedOwnership() throws Exception {
        TagStorage storage = mock(TagStorage.class);
        when(storage.loadAsync(playerId)).thenReturn(CompletableFuture.completedFuture(saved("baltop_elite")));
        when(storage.grantTagAsync(playerId, "new_tag")).thenReturn(
            CompletableFuture.failedFuture(new SQLException("grant failed")));
        TagService service = service(storage);
        service.getRegistry().register(new TagDefinition("new_tag", "New", "New", org.bukkit.Material.PAPER, java.util.List.of()));

        assertFalse(service.grantTagPersisted(playerId, "new_tag").join());
        assertEquals(Set.of("baltop_elite"), service.getPlayerData(playerId).getOwnedTags());
        assertEquals("baltop_elite", service.getPlayerData(playerId).getSelectedTag());
    }

    @Test
    void revocationWaitsForOwnershipAndPreservesUnrelatedTags() throws Exception {
        TagStorage storage = mock(TagStorage.class);
        CompletableFuture<TagStorage.StoredTagData> read = new CompletableFuture<>();
        when(storage.loadAsync(playerId)).thenReturn(read);
        when(storage.revokeTagAsync(playerId, "old_tag")).thenReturn(CompletableFuture.completedFuture(null));
        when(storage.setSelectedTagAsync(playerId, null)).thenReturn(CompletableFuture.completedFuture(null));
        TagService service = service(storage);

        service.revokeTag(playerId, "old_tag");
        verify(storage, never()).revokeTagAsync(any(), any());
        read.complete(new TagStorage.StoredTagData(Set.of("baltop_elite", "old_tag"), "old_tag"));

        assertEquals(Set.of("baltop_elite"), service.getPlayerData(playerId).getOwnedTags());
        assertNull(service.getPlayerData(playerId).getSelectedTag());
        verify(storage).revokeTagAsync(playerId, "old_tag");
    }

    @Test
    void selectionCannotWriteAnUnloadedPlaceholderToStorage() throws Exception {
        TagStorage storage = mock(TagStorage.class);
        when(storage.loadAsync(playerId)).thenReturn(new CompletableFuture<>());
        TagService service = service(storage);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);

        assertFalse(service.setSelectedTag(player, null));
        verify(storage, never()).setSelectedTagAsync(any(), any());
        verify(storage).loadAsync(playerId);
    }

    private TagService service(TagStorage storage) throws Exception {
        EnthusiaTagsPlugin plugin = mock(EnthusiaTagsPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        // Leave plugin disabled in this storage test so scheduleRefresh performs no Bukkit IO.
        TagService service = new TagService(plugin, mock(Messages.class), mock(PerformanceMonitor.class));
        Field field = TagService.class.getDeclaredField("storage");
        field.setAccessible(true);
        field.set(service, storage);
        return service;
    }

    private TagStorage.StoredTagData saved(String tag) {
        return new TagStorage.StoredTagData(Set.of(tag), tag);
    }

    private java.util.Map<?, ?> pending(TagService service) throws Exception {
        Field field = TagService.class.getDeclaredField("pendingLoads");
        field.setAccessible(true);
        return (java.util.Map<?, ?>) field.get(service);
    }
}
