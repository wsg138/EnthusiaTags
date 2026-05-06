package org.enthusia.tags;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class PerformanceMonitor {
    private final JavaPlugin plugin;
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private BukkitTask logTask;
    private volatile boolean enabled;

    public PerformanceMonitor(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("debug.performance.enabled", false);
        stop();
        if (!enabled) {
            return;
        }
        long seconds = Math.max(30L, plugin.getConfig().getLong("debug.performance.log-interval-seconds", 300L));
        logTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () ->
            plugin.getLogger().info("Performance counters: " + snapshot()), seconds * 20L, seconds * 20L);
    }

    public void stop() {
        if (logTask != null) {
            logTask.cancel();
            logTask = null;
        }
    }

    public void increment(String key) {
        add(key, 1L);
    }

    public void add(String key, long amount) {
        counters.computeIfAbsent(key, ignored -> new AtomicLong()).addAndGet(amount);
    }

    public void recordDurationMillis(String key, long millis) {
        add(key + ".count", 1L);
        add(key + ".total-ms", Math.max(0L, millis));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        counters.keySet().stream().sorted().forEach(key -> result.put(key, counters.get(key).get()));
        return result;
    }

    public void sendTo(CommandSender sender) {
        sender.sendMessage("EnthusiaTags performance counters:");
        if (counters.isEmpty()) {
            sender.sendMessage("  No counters recorded yet.");
            return;
        }
        snapshot().forEach((key, value) -> sender.sendMessage("  " + key + ": " + value));
    }
}
