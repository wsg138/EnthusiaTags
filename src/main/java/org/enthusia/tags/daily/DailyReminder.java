package org.enthusia.tags.daily;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Main-thread presentation adapter; eligibility reads never reserve or pay rewards. */
final class DailyReminder {
    private static final String PERMISSION = "enthusia.tags.daily";
    private static final String ENABLED = "daily.discovery.enabled";
    private final JavaPlugin plugin;
    private final Function<Player, CompletableFuture<Optional<Offer>>> load;
    private final Predicate<Player> ready;
    private final Supplier<LocalDate> today;
    // Join/claim/quit events and scheduled callbacks access this only on the Paper main thread.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private final Map<UUID, Session> sessions = new HashMap<>();

    DailyReminder(JavaPlugin plugin, Function<Player, CompletableFuture<Optional<Offer>>> load,
                  Predicate<Player> ready, Supplier<LocalDate> today) {
        this.plugin = plugin;
        this.load = load;
        this.ready = ready;
        this.today = today;
    }

    void join(Player player) {
        remove(player.getUniqueId());
        if (!eligible(player)) return;
        String key = player.hasPlayedBefore() ? "returning-delay-seconds" : "new-player-delay-seconds";
        long fallback = player.hasPlayedBefore() ? 20L : 120L;
        long seconds = Math.max(1L, Math.min(3600L,
            plugin.getConfig().getLong("daily.discovery." + key, fallback)));
        Session session = new Session(player);
        sessions.put(player.getUniqueId(), session);
        session.task = plugin.getServer().getScheduler().runTaskLater(plugin,
            () -> begin(session), seconds * 20L);
    }

    private void begin(Session session) {
        if (!current(session)) return;
        load.apply(session.player).whenComplete((offer, failure) -> {
            if (!plugin.isEnabled()) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!current(session)) return;
                remove(session.player.getUniqueId());
                if (failure == null && offer != null) {
                    offer.filter(value -> value.date().equals(today.get()))
                        .ifPresent(value -> session.player.sendMessage(message(value)));
                }
            });
        });
    }

    private Component message(Offer offer) {
        return Component.text("Today's daily reward: " + offer.reward()
            + ". Use /daily to open the reward menu.", NamedTextColor.GOLD)
            .clickEvent(ClickEvent.runCommand("/daily"));
    }

    private boolean current(Session session) {
        return sessions.get(session.player.getUniqueId()) == session && eligible(session.player);
    }

    private boolean eligible(Player player) {
        return plugin.getConfig().getBoolean(ENABLED, false) && player.isOnline()
            && player.hasPermission(PERMISSION) && ready.test(player);
    }

    void remove(UUID id) {
        Session session = sessions.remove(id);
        if (session != null && session.task != null) session.task.cancel();
    }

    void clear() {
        for (Session session : sessions.values()) {
            if (session.task != null) session.task.cancel();
        }
        sessions.clear();
    }

    record Offer(LocalDate date, String reward) { }

    private static final class Session {
        private final Player player;
        private BukkitTask task;
        private Session(Player player) { this.player = player; }
    }
}
