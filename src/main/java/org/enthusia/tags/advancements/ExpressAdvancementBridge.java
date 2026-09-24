package org.enthusia.tags.advancements;

import io.github.badgersmc.advancements.pilot.ProjectionService;
import org.bukkit.Material;
import org.enthusia.tags.advancements.domain.ExpressMilestoneProgress;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

final class ExpressAdvancementBridge {
    private final Path database;
    private record Snapshot(
        long readStarted,
        Map<UUID, ExpressMilestoneProgress.Stats> players
    ) {}

    private volatile Snapshot latest;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final Map<UUID, ExpressMilestoneProgress> sessions = new HashMap<>();
    private final Map<UUID, Long> sessionStarted = new HashMap<>();
    private final Set<UUID> trackedPlayers = ConcurrentHashMap.newKeySet();

    ExpressAdvancementBridge(Path database) {
        this.database = database;
    }

    void beginSession(UUID player) {
        sessions.remove(player);
        sessionStarted.put(player, System.nanoTime());
        trackedPlayers.add(player);
    }
    void refresh() throws Exception {
        if (!refreshing.compareAndSet(false, true)) return;
        long started = System.nanoTime();
        try {
            latest = new Snapshot(started, ExpressStatsReader.read(database, trackedPlayers));
        } catch (Exception failure) {
            latest = null;
            throw failure;
        } finally {
            refreshing.set(false);
        }
    }

    ExpressMilestoneProgress.Update observe(UUID player) {
        Snapshot snapshot = latest;
        Long joined = sessionStarted.get(player);
        ExpressMilestoneProgress.Stats stats =
            snapshot == null || (joined != null && snapshot.readStarted() - joined < 0)
                ? null
                : snapshot.players().getOrDefault(player, emptyStats());
        return sessions.computeIfAbsent(
            player, ignored -> new ExpressMilestoneProgress()).observe(stats);
    }

    void forget(UUID player) {
        sessions.remove(player);
        sessionStarted.remove(player);
        trackedPlayers.remove(player);
    }

    private static ExpressMilestoneProgress.Stats emptyStats() {
        return new ExpressMilestoneProgress.Stats(0, 0, 0, 0, 0, 0);
    }
    static List<ProjectionService.Node> nodes(int baseY) {
        return List.of(
            node("first_class", null, "First Class",
                "Send your first EnthusiaExpress package.",
                Material.CHEST, "TASK", 1, baseY),
            node("care_package", "first_class", "Care Package",
                "Send a package containing at least 64 packed items.",
                Material.SHULKER_BOX, "GOAL", 2, baseY - 1),
            node("frequent_shipper", "first_class", "Frequent Shipper",
                "Send 10 packages.",
                Material.MINECART, "GOAL", 2, baseY),
            node("postal_legend", "frequent_shipper", "Postal Legend",
                "Send 50 packages.",
                Material.CHEST_MINECART, "CHALLENGE", 3, baseY),
            node("youve_got_mail", null, "You've Got Mail",
                "Claim your first delivered package.",
                Material.BUNDLE, "TASK", 1, baseY + 1),
            node("parcel_collector", "youve_got_mail", "Parcel Collector",
                "Claim 10 delivered packages.",
                Material.ENDER_CHEST, "GOAL", 2, baseY + 1),
            node("return_to_sender", "youve_got_mail", "Return to Sender",
                "Collect one of your returned packages.",
                Material.COMPASS, "GOAL", 2, baseY + 2),
            node("pen_pal", null, "Pen Pal",
                "Send your first signed-book letter.",
                Material.WRITTEN_BOOK, "TASK", 1, baseY + 4),
            node("correspondent", "pen_pal", "Correspondent",
                "Send 10 letters.",
                Material.BOOK, "GOAL", 2, baseY + 4),
            node("read_all_about_it", null, "Read All About It",
                "Read your first received letter.",
                Material.PAPER, "TASK", 1, baseY + 5),
            node("avid_reader", "read_all_about_it", "Avid Reader",
                "Read 10 received letters.",
                Material.BOOKSHELF, "GOAL", 2, baseY + 5)
        );
    }

    private static ProjectionService.Node node(
        String id, String parent, String title, String requirement,
        Material icon, String frame, int x, int y
    ) {
        return new ProjectionService.Node(
            "express/" + id,
            parent == null ? null : "express/" + parent,
            title,
            List.of("§7EnthusiaExpress", "§7Requirements:", "§f" + requirement,
                "§7Progress is read from mail history.",
                "§7Rewards: None (advancement only)."),
            icon, frame, x, y);
    }
}
