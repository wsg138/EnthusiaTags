package org.enthusia.tags.advancements;

import io.github.badgersmc.advancements.pilot.ProjectionService;
import org.bukkit.Material;
import org.enthusia.tags.advancements.domain.ReputationMilestoneProgress;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class CommendAdvancementBridge {
    private final Path file;
    private record Snapshot(
        long readStarted,
        Map<UUID, ReputationMilestoneProgress.Stats> players
    ) {}

    private volatile Snapshot latest;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final Map<UUID, ReputationMilestoneProgress> sessions = new HashMap<>();
    private final Map<UUID, Long> sessionStarted = new HashMap<>();

    CommendAdvancementBridge(Path file) {
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
            latest = new Snapshot(started,
                CommendStatsReader.parse(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            latest = null;
            throw failure;
        } finally {
            refreshing.set(false);
        }
    }

    ReputationMilestoneProgress.Update observe(UUID player) {
        Snapshot snapshot = latest;
        Long joined = sessionStarted.get(player);
        ReputationMilestoneProgress.Stats stats =
            snapshot == null || (joined != null && snapshot.readStarted() - joined < 0)
                ? null
                : snapshot.players().getOrDefault(player, emptyStats());
        return sessions.computeIfAbsent(
            player, ignored -> new ReputationMilestoneProgress()).observe(stats);
    }

    void forget(UUID player) {
        sessions.remove(player);
        sessionStarted.remove(player);
    }

    private static ReputationMilestoneProgress.Stats emptyStats() {
        return new ReputationMilestoneProgress.Stats(
            false, 0, 0, false, 0, 0, 0, 0);
    }
    static List<ProjectionService.Node> nodes(int baseY) {
        return List.of(
            node("a_good_word", null, "A Good Word",
                "Receive your first positive commendation.",
                Material.PINK_TULIP, "TASK", 1, baseY),
            node("well_regarded", "a_good_word", "Well Regarded",
                "Reach +10 overall reputation at any point.",
                Material.GOLD_INGOT, "GOAL", 2, baseY - 1),
            node("pillar_of_the_community", "well_regarded", "Pillar of the Community",
                "Reach +20 overall reputation at any point.",
                Material.BEACON, "CHALLENGE", 3, baseY - 1),
            node("kind_soul", "a_good_word", "Kind Soul",
                "Reach +5 in the Was Kind reputation category.",
                Material.PINK_TULIP, "GOAL", 2, baseY),
            node("generous_spirit", "a_good_word", "Generous Spirit",
                "Reach +5 in the Gave Items/Money reputation category.",
                Material.EMERALD, "GOAL", 3, baseY),
            node("trusted_name", "a_good_word", "Trusted Name",
                "Reach +5 in the Trustworthy reputation category.",
                Material.SHIELD, "GOAL", 2, baseY + 1),
            node("merchant_of_merit", "a_good_word", "Merchant of Merit",
                "Reach +5 in the Good Stall reputation category.",
                Material.CHEST, "GOAL", 3, baseY + 1),
            node("bad_reputation", null, "Bad Reputation",
                "Reach -10 overall reputation at any point.",
                Material.RED_DYE, "TASK", 1, baseY + 3),
            node("public_enemy", "bad_reputation", "Public Enemy",
                "Reach -25 overall reputation at any point.",
                Material.WITHER_SKELETON_SKULL, "CHALLENGE", 2, baseY + 3),
            node("redemption_arc", "bad_reputation", "Redemption Arc",
                "Recover from -12 or lower overall reputation back to 0 or higher.",
                Material.TOTEM_OF_UNDYING, "CHALLENGE", 2, baseY + 4)
        );
    }
    private static ProjectionService.Node node(
        String id,
        String parent,
        String title,
        String requirement,
        Material icon,
        String frame,
        int x,
        int y
    ) {
        return new ProjectionService.Node(
            "reputation/" + id,
            parent == null ? null : "reputation/" + parent,
            title,
            List.of(
                "§7Enthusia Reputation",
                "§7Requirements:",
                "§f" + requirement,
                "§7Progress is read from EnthusiaCommend.",
                "§7Rewards: None (advancement only)."
            ),
            icon,
            frame,
            x,
            y
        );
    }
}
