package org.enthusia.tags.advancements;

import io.github.badgersmc.advancements.pilot.ProjectionService;
import org.bukkit.Material;
import org.enthusia.tags.advancements.domain.DiaryMilestoneProgress;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class DiaryAdvancementBridge {
    private final Path file;
    private record Snapshot(
        long readStarted,
        Map<UUID, DiaryMilestoneProgress.Stats> players
    ) {}

    private volatile Snapshot latest;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final Map<UUID, DiaryMilestoneProgress> sessions = new HashMap<>();
    private final Map<UUID, Long> sessionStarted = new HashMap<>();

    DiaryAdvancementBridge(Path file) {
        this.file = file;
    }

    void beginSession(UUID player) {
        sessions.remove(player);
        sessionStarted.put(player, System.nanoTime());
    }
    void refresh() throws Exception {
        if (!refreshing.compareAndSet(false, true)) return;
        long started = System.nanoTime();
        try {
            latest = new Snapshot(started, DiaryStatsReader.read(file));
        } catch (Exception failure) {
            latest = null;
            throw failure;
        } finally {
            refreshing.set(false);
        }
    }

    DiaryMilestoneProgress.Update observe(UUID player) {
        Snapshot snapshot = latest;
        Long joined = sessionStarted.get(player);
        DiaryMilestoneProgress.Stats stats =
            snapshot == null || (joined != null && snapshot.readStarted() - joined < 0)
                ? null
                : snapshot.players().getOrDefault(player, emptyStats());
        return sessions.computeIfAbsent(
            player, ignored -> new DiaryMilestoneProgress()).observe(stats);
    }

    void forget(UUID player) {
        sessions.remove(player);
        sessionStarted.remove(player);
    }

    private static DiaryMilestoneProgress.Stats emptyStats() {
        return new DiaryMilestoneProgress.Stats(
            false, 0, 0, 0, 0, 0);
    }
    static List<ProjectionService.Node> nodes(int baseY, int diaryIconCustomModelData) {
        return List.of(
            node("dear_diary", null, "Dear Diary...",
                "Receive your personal diary for the first time.",
                Material.PAPER, diaryIconCustomModelData, "TASK", 1, baseY),
            node("first_entry", "dear_diary", "First Entry",
                "Write or edit your diary once.",
                Material.INK_SAC, "TASK", 2, baseY - 1),
            node("prolific_writer", "first_entry", "Prolific Writer",
                "Write or edit your diary 25 times.",
                Material.FEATHER, "GOAL", 3, baseY - 1),
            node("finders_keepers", "dear_diary", "Finders Keepers",
                "Pick up a diary from the ground.",
                Material.SPYGLASS, "TASK", 2, baseY),
            node("indestructible", "dear_diary", "Indestructible",
                "Have your diary survive a destruction attempt.",
                Material.NETHERITE_INGOT, "TASK", 2, baseY + 1),
            node("stubborn", "indestructible", "Stubborn",
                "Have your diary survive 10 destruction attempts.",
                Material.OBSIDIAN, "CHALLENGE", 3, baseY + 1),
            node("void_walker", "dear_diary", "Void Walker",
                "Have your diary safely returned after falling into the void.",
                Material.ENDER_PEARL, "GOAL", 2, baseY + 2),
            node("nice_try", "dear_diary", "Nice Try",
                "Attempt to stash your diary in a restricted container.",
                Material.CHEST, "TASK", 2, baseY + 3)
        );
    }

    private static ProjectionService.Node node(
        String id, String parent, String title, String requirement,
        Material icon, String frame, int x, int y
    ) {
        return new ProjectionService.Node(
            "diary/" + id,
            parent == null ? null : "diary/" + parent,
            title,
            List.of("§7DiaryKeeper", "§7Requirements:", "§f" + requirement,
                "§7Progress is read from DiaryKeeper.",
                "§7Rewards: None (advancement only)."),
            icon, frame, x, y);
    }

    private static ProjectionService.Node node(
        String id, String parent, String title, String requirement,
        Material icon, Integer customModelData, String frame, int x, int y
    ) {
        return new ProjectionService.Node(
            "diary/" + id,
            parent == null ? null : "diary/" + parent,
            title,
            List.of("§7DiaryKeeper", "§7Requirements:", "§f" + requirement,
                "§7Progress is read from DiaryKeeper.",
                "§7Rewards: None (advancement only)."),
            icon, customModelData, frame, x, y);
    }
}
