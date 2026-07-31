package org.enthusia.tags.rewards;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NaturalBlockStorageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void placedBlocksAreConsumedOnceAndDoNotCountAsNatural() throws Exception {
        NaturalBlockStorage.BlockKey key = new NaturalBlockStorage.BlockKey(
            UUID.randomUUID(), 10, 20, 30);
        try (NaturalBlockStorage storage = new NaturalBlockStorage(
            temporaryDirectory.resolve("natural.db").toFile())) {
            storage.markPlaced(key, Material.DIAMOND_ORE).get();
            assertTrue(storage.consumePlaced(key, Material.DIAMOND_ORE).get());
            assertFalse(storage.consumePlaced(key, Material.DIAMOND_ORE).get());
        }
    }

    @Test
    void pistonMovesCarryThePlacementMarker() throws Exception {
        UUID world = UUID.randomUUID();
        NaturalBlockStorage.BlockKey from = new NaturalBlockStorage.BlockKey(world, 1, 2, 3);
        NaturalBlockStorage.BlockKey to = new NaturalBlockStorage.BlockKey(world, 2, 2, 3);
        try (NaturalBlockStorage storage = new NaturalBlockStorage(
            temporaryDirectory.resolve("piston.db").toFile())) {
            storage.markPlaced(from, Material.ANCIENT_DEBRIS).get();
            storage.movePlaced(List.of(new NaturalBlockStorage.BlockMove(
                from, to, Material.ANCIENT_DEBRIS))).get();
            assertFalse(storage.isMarkedNow(from, Material.ANCIENT_DEBRIS));
            assertTrue(storage.isMarkedNow(to, Material.ANCIENT_DEBRIS));
        }
    }
}
