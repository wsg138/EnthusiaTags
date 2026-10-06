package org.enthusia.tags.advancements;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.tags.rewards.RewardService;

/** Owns one set of read-only provider readers independently of native presentation. */
@SuppressWarnings("PMD.UseConcurrentHashMap") // Session state is accessed on the server thread only.
public final class ProviderRewardTracker implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final RewardService rewards;
    private final ArrayDeque<UUID> queue = new ArrayDeque<>();
    private final Map<UUID, Map<String, Integer>> progress = new HashMap<>();
    private final Map<UUID, Set<String>> celebrations = new HashMap<>();
    private BukkitTask task;
    private BukkitTask duelTask;
    private BukkitTask commendTask;
    private BukkitTask expressTask;
    private BukkitTask diaryTask;
    private WarzoneAdvancementBridge duels;
    private CommendAdvancementBridge commend;
    private ExpressAdvancementBridge express;
    private DiaryAdvancementBridge diary;
    private boolean closed;
    public ProviderRewardTracker(JavaPlugin plugin, RewardService rewards) {
        this.plugin = plugin;
        this.rewards = rewards;
        initializeBridges();
        scheduleBridgeRefreshes();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }
    private void initializeBridges() {
        initializeDuels();
        initializeCommend();
        initializeExpress();
        initializeDiary();
    }

    private void initializeDuels() {
        if (plugin.getConfig().getBoolean("advancements.warzone-duels-enabled", false)) {
            var provider = Bukkit.getPluginManager().getPlugin("WarzoneDuels");
            if (provider != null && provider.isEnabled()) {
                duels = new WarzoneAdvancementBridge(provider.getDataFolder().toPath().resolve("stats.yml"));
                Bukkit.getOnlinePlayers().forEach(player -> duels.beginSession(player.getUniqueId()));
            } else plugin.getLogger().warning("Warzone Duels advancements requested but WarzoneDuels is unavailable; bridge disabled.");
        }
    }

    private void initializeCommend() {
        if (plugin.getConfig().getBoolean("advancements.commendation-enabled", true)) {
            var provider = Bukkit.getPluginManager().getPlugin("EnthusiaCommend");
            if (provider != null && provider.isEnabled()) {
                commend = new CommendAdvancementBridge(provider.getDataFolder().toPath().resolve("data.yml"));
                Bukkit.getOnlinePlayers().forEach(player -> commend.beginSession(player.getUniqueId()));
            } else plugin.getLogger().warning("Reputation advancements requested but EnthusiaCommend is unavailable; bridge disabled.");
        }
    }

    private void initializeExpress() {
        if (plugin.getConfig().getBoolean("advancements.express-enabled", true)) {
            var provider = Bukkit.getPluginManager().getPlugin("EnthusiaExpress");
            if (provider != null && provider.isEnabled()) {
                express = new ExpressAdvancementBridge(provider.getDataFolder().toPath().resolve("mail.db"));
                Bukkit.getOnlinePlayers().forEach(player -> express.beginSession(player.getUniqueId()));
            } else plugin.getLogger().warning("EnthusiaExpress advancements requested but EnthusiaExpress is unavailable; bridge disabled.");
        }
    }

    private void initializeDiary() {
        if (plugin.getConfig().getBoolean("advancements.diary-enabled", true)) {
            var provider = Bukkit.getPluginManager().getPlugin("DiaryKeeper");
            if (provider != null && provider.isEnabled()) {
                diary = new DiaryAdvancementBridge(provider.getDataFolder().toPath().resolve("diaries.yml"));
                Bukkit.getOnlinePlayers().forEach(player -> diary.beginSession(player.getUniqueId()));
            } else plugin.getLogger().warning("DiaryKeeper advancements requested but DiaryKeeper is unavailable; bridge disabled.");
        }
    }

    private void scheduleBridgeRefreshes() {
        scheduleDuelRefresh();
        scheduleCommendRefresh();
        scheduleExpressRefresh();
        scheduleDiaryRefresh();
    }

    private void scheduleDuelRefresh() {
        if (duels != null) duelTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
            private long warningAfter;
            @Override public void run() {
                try { duels.refresh(); }
                catch (Exception ex) {
                    if (System.currentTimeMillis() >= warningAfter) {
                        warningAfter = System.currentTimeMillis() + 60000;
                        plugin.getLogger().warning("Warzone duel statistics unavailable; retaining known advancement progress: " + ex.getMessage());
                    }
                }
            }
        }, 20L, 100L);
    }

    private void scheduleCommendRefresh() {
        if (commend != null) commendTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
            private long warningAfter;
            @Override public void run() {
                try { commend.refresh(); }
                catch (Exception ex) {
                    if (System.currentTimeMillis() >= warningAfter) {
                        warningAfter = System.currentTimeMillis() + 60000;
                        plugin.getLogger().warning("Reputation advancement evidence unavailable; retaining known progress: "
                            + ex.getMessage());
                    }
                }
            }
        }, 20L, 100L);
    }

    private void scheduleExpressRefresh() {
        if (express != null) expressTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
            private long warningAfter;
            @Override public void run() {
                try { express.refresh(); }
                catch (Exception ex) {
                    if (System.currentTimeMillis() >= warningAfter) {
                        warningAfter = System.currentTimeMillis() + 60000;
                        plugin.getLogger().warning("EnthusiaExpress mail history unavailable; retaining known advancement progress: "
                            + ex.getMessage());
                    }
                }
            }
        }, 20L, 100L);
    }

    private void scheduleDiaryRefresh() {
        if (diary != null) diaryTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
            private long warningAfter;
            @Override public void run() {
                try { diary.refresh(); }
                catch (Exception ex) {
                    if (System.currentTimeMillis() >= warningAfter) {
                        warningAfter = System.currentTimeMillis() + 60000;
                        plugin.getLogger().warning("DiaryKeeper advancement evidence unavailable; retaining known progress: "
                            + ex.getMessage());
                    }
                }
            }
        }, 20L, 100L);
    }

    WarzoneAdvancementBridge duels() { return duels; }
    CommendAdvancementBridge commend() { return commend; }
    ExpressAdvancementBridge express() { return express; }
    DiaryAdvancementBridge diary() { return diary; }
    Map<String, Integer> progress(UUID id) { return progress.getOrDefault(id, Map.of()); }
    Set<String> takeCelebrations(UUID id) {
        Set<String> pending = celebrations.remove(id);
        return pending == null ? Set.of() : Set.copyOf(pending);
    }
    void tick() {
        if (closed || !rewards.isAvailable()) return;
        if (queue.isEmpty()) Bukkit.getOnlinePlayers().forEach(player -> queue.add(player.getUniqueId()));
        int limit = Math.max(1, Math.min(100, plugin.getConfig().getInt("advancements.players-per-tick", 25)));
        for (int count = 0; count < limit && !queue.isEmpty(); count++) {
            Player player = Bukkit.getPlayer(queue.removeFirst());
            if (player != null && player.isOnline()) observe(player);
        }
    }
    void observe(Player player) {
        UUID id = player.getUniqueId();
        Map<String, Integer> known = new HashMap<>();
        Map<String, Integer> fresh = new HashMap<>();
        if (duels != null) {
            var update = duels.observe(id);
            merge(id, known, fresh, update.progress(), update.celebrate(),
                available("WarzoneDuels", duels.evidenceAvailable(id)));
        }
        if (commend != null) {
            var update = commend.observe(id);
            merge(id, known, fresh, update.progress(), update.celebrate(),
                available("EnthusiaCommend", commend.evidenceAvailable(id)));
        }
        if (express != null) {
            var update = express.observe(id);
            merge(id, known, fresh, update.progress(), update.celebrate(),
                available("EnthusiaExpress", express.evidenceAvailable(id)));
        }
        if (diary != null) {
            var update = diary.observe(id);
            merge(id, known, fresh, update.progress(), update.celebrate(),
                available("DiaryKeeper", diary.evidenceAvailable(id)));
        }
        progress.put(id, Map.copyOf(known));
        rewards.observeProviderProgress(player, Map.copyOf(fresh));
    }
    private boolean available(String name, boolean verified) {
        return verified && Bukkit.getPluginManager().isPluginEnabled(name);
    }
    private void merge(UUID id, Map<String, Integer> known, Map<String, Integer> fresh,
                       Map<String, Integer> update, Set<String> celebrate, boolean available) {
        known.putAll(update);
        if (available) fresh.putAll(update);
        if (available && !celebrate.isEmpty() && plugin.getConfig().getBoolean("advancements.enabled", true))
            celebrations.computeIfAbsent(id, ignored -> new HashSet<>()).addAll(celebrate);
    }
    /** Reload fences cached and in-flight reads without restarting reader tasks. */
    public void resetSessions() {
        progress.clear();
        celebrations.clear();
        queue.clear();
        Bukkit.getOnlinePlayers().forEach(player -> beginSession(player.getUniqueId()));
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        beginSession(event.getPlayer().getUniqueId());
    }
    private void beginSession(UUID id) {
        progress.remove(id);
        celebrations.remove(id);
        if (duels != null) duels.beginSession(id);
        if (commend != null) commend.beginSession(id);
        if (express != null) express.beginSession(id);
        if (diary != null) diary.beginSession(id);
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        progress.remove(id);
        celebrations.remove(id);
        queue.removeIf(id::equals);
        if (duels != null) duels.forget(id);
        if (commend != null) commend.forget(id);
        if (express != null) express.forget(id);
        if (diary != null) diary.forget(id);
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        if (task != null) task.cancel();
        if (duelTask != null) duelTask.cancel();
        if (commendTask != null) commendTask.cancel();
        if (expressTask != null) expressTask.cancel();
        if (diaryTask != null) diaryTask.cancel();
        HandlerList.unregisterAll(this);
        progress.clear();
        celebrations.clear();
        queue.clear();
    }
}
