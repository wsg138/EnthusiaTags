package org.enthusia.tags;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TagDisplayManager {
    private final Map<UUID, TextDisplay> displays = new ConcurrentHashMap<>();
    private BukkitTask cleanupTask;

    public void start(JavaPlugin plugin) {
        if (cleanupTask != null) {
            return;
        }
        cleanupTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::cleanup, 200L, 200L);
    }

    public void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        clearAll();
    }

    public void update(Player player, Component component, double offset) {
        if (component == null) {
            remove(player);
            return;
        }

        TextDisplay display = displays.get(player.getUniqueId());
        if (display != null && display.isDead()) {
            displays.remove(player.getUniqueId());
            display = null;
        }
        if (display == null || display.getVehicle() != player) {
            if (display != null) {
                display.remove();
            }
            display = spawnDisplay(player);
            displays.put(player.getUniqueId(), display);
        }
        display.text(component);
        if (!player.getPassengers().contains(display)) {
            player.addPassenger(display);
        }
        display.setTransformation(new Transformation(
            new Vector3f(0f, (float) offset, 0f),
            new AxisAngle4f(),
            new Vector3f(1f, 1f, 1f),
            new AxisAngle4f()
        ));
    }

    public void remove(Player player) {
        TextDisplay display = displays.remove(player.getUniqueId());
        if (display != null && !display.isDead()) {
            display.remove();
        }
    }

    public void clearAll() {
        for (TextDisplay display : displays.values()) {
            if (display != null && !display.isDead()) {
                display.remove();
            }
        }
        displays.clear();
    }

    private TextDisplay spawnDisplay(Player player) {
        TextDisplay display = player.getWorld().spawn(player.getLocation(), TextDisplay.class, entity -> {
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setSeeThrough(false);
            entity.setShadowed(false);
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setPersistent(false);
        });
        return display;
    }

    private void cleanup() {
        for (Map.Entry<UUID, TextDisplay> entry : displays.entrySet()) {
            TextDisplay display = entry.getValue();
            if (display == null || display.isDead()) {
                displays.remove(entry.getKey());
                continue;
            }
            if (!(display.getVehicle() instanceof Player)) {
                display.remove();
                displays.remove(entry.getKey());
            }
        }
    }
}
