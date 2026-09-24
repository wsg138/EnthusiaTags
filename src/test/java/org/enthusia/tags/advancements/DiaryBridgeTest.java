package org.enthusia.tags.advancements;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DiaryBridgeTest {
    @TempDir Path directory;
    private final UUID player = UUID.randomUUID();

    private void save(Path file, int edits, int destruction) throws Exception {
        Files.writeString(file,
            "players:\n  " + player + ":\n" +
            "    id: diary-id\n" +
            "    issuedAt: 123\n" +
            "    advancements:\n" +
            "      received: true\n" +
            "      edits: " + edits + "\n" +
            "      destructionAttempts: " + destruction + "\n" +
            "      voidReturns: 0\n" +
            "      containerAttempts: 0\n" +
            "      groundPickups: 0\n");
    }
    @Test void historicalProgressIsSilentAndLiveCrossingCelebrates() throws Exception {
        Path file = directory.resolve("diaries.yml");
        save(file, 24, 9);

        var bridge = new DiaryAdvancementBridge(file);
        bridge.beginSession(player);
        bridge.refresh();
        var historical = bridge.observe(player);
        assertEquals(1000, historical.progress().get("diary/dear_diary"));
        assertTrue(historical.celebrate().isEmpty());

        save(file, 25, 10);
        bridge.refresh();
        var live = bridge.observe(player);
        assertTrue(live.celebrate().contains("diary/prolific_writer"));
        assertTrue(live.celebrate().contains("diary/stubborn"));

        Files.writeString(file, "players: [");
        assertThrows(Exception.class, bridge::refresh);
        assertEquals(live.progress(), bridge.observe(player).progress());
    }
    @Test void malformedHistoryDoesNotSeedAFalseBaseline() throws Exception {
        Path file = directory.resolve("shape.yml");
        Files.writeString(file, "players: []\n");
        var bridge = new DiaryAdvancementBridge(file);
        bridge.beginSession(player);
        assertThrows(IllegalArgumentException.class, bridge::refresh);
        assertTrue(bridge.observe(player).progress().isEmpty());
        save(file, 25, 10);
        bridge.refresh();
        var restored = bridge.observe(player);
        assertEquals(1000, restored.progress().get("diary/prolific_writer"));
        assertTrue(restored.celebrate().isEmpty());
        Files.writeString(file, "players:\n  " + player + ":\n    issuedAt: broken\n");
        assertThrows(IllegalArgumentException.class, bridge::refresh);
        assertEquals(restored.progress(), bridge.observe(player).progress());
    }

    @Test void nodesUseTheDiaryBranchesAndHaveNoRewards() {
        var nodes = DiaryAdvancementBridge.nodes(49, 815002);
        assertEquals(8, nodes.size());
        var byKey = nodes.stream().collect(
            java.util.stream.Collectors.toMap(node -> node.key(), node -> node));

        assertEquals("Dear Diary...", byKey.get("diary/dear_diary").title());
        assertEquals(org.bukkit.Material.PAPER, byKey.get("diary/dear_diary").icon());
        assertEquals(815002, byKey.get("diary/dear_diary").customModelData());
        assertEquals("diary/dear_diary", byKey.get("diary/first_entry").parentKey());
        assertEquals("diary/first_entry", byKey.get("diary/prolific_writer").parentKey());
        assertEquals("diary/indestructible", byKey.get("diary/stubborn").parentKey());
        assertEquals("diary/dear_diary", byKey.get("diary/void_walker").parentKey());
        assertEquals("diary/dear_diary", byKey.get("diary/nice_try").parentKey());
        assertEquals("diary/dear_diary", byKey.get("diary/finders_keepers").parentKey());

        for (var node : nodes) {
            assertTrue(node.description().stream().anyMatch(s -> s.contains("Requirements:")));
            assertTrue(node.description().stream().anyMatch(s -> s.contains("Rewards: None")));
        }
    }
    @Test void customDiaryIconAssetsMatchConfiguredPaperMapping() throws Exception {
        Path root = Path.of("resourcepack/diary-icon");
        String nexo = Files.readString(root.resolve("enthusia_diary_advancement_icon.yml"));
        String model = Files.readString(root.resolve(
            "external_pack/assets/enthusia/models/item/journal_quill.json"));
        byte[] texture = Files.readAllBytes(root.resolve(
            "external_pack/assets/enthusia/textures/item/journal_quill.png"));
        String directPaperMapping = Files.readString(Path.of(
            "resource-pack/assets/minecraft/items/paper.json"));

        assertTrue(nexo.contains("material: PAPER"));
        assertTrue(nexo.contains("custom_model_data: 815002"));
        assertTrue(nexo.contains("model: enthusia:item/journal_quill"));
        assertTrue(model.contains("\"layer0\": \"enthusia:item/journal_quill\""));
        assertTrue(directPaperMapping.contains(
            "\"threshold\": 815002, \"model\": { \"type\": \"minecraft:model\", \"model\": \"enthusia:item/journal_quill\" }"));
        assertFalse(directPaperMapping.contains(
            "\"threshold\": 815002, \"model\": { \"type\": \"minecraft:model\", \"model\": \"minecraft:item/paper\" }"));
        assertTrue(texture.length > 8);
        assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G'}, java.util.Arrays.copyOf(texture, 4));
    }

    @Test void nativeWiringIsOptionalAsyncAndSoftDependent() throws Exception {
        String config = Files.readString(Path.of("src/main/resources/config.yml"));
        String plugin = Files.readString(Path.of("src/main/resources/plugin.yml"));
        String source = Files.readString(Path.of(
            "src/main/java/org/enthusia/tags/advancements/NativeAdvancementController.java"));

        assertTrue(config.contains("diary-enabled: true"));
        assertTrue(config.contains("diary-icon-custom-model-data: 815002"));
        assertTrue(plugin.contains("- DiaryKeeper"));
        assertTrue(source.contains("getPlugin(\"DiaryKeeper\")"));
        assertTrue(source.contains("DiaryAdvancementBridge"));
        assertTrue(source.contains("diary.refresh()"));
        assertTrue(source.contains("diary.observe(player.getUniqueId())"));
        assertTrue(source.contains("diary.forget(id)"));
        assertTrue(source.contains("diaryTask.cancel()"));
    }
}
