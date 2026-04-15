package org.enthusia.tags;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.UUID;

public final class PlayerLookup {
    private final JavaPlugin plugin;

    public PlayerLookup(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public OfflinePlayer findPlayer(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        try {
            UUID uuid = UUID.fromString(input);
            return Bukkit.getOfflinePlayer(uuid);
        } catch (IllegalArgumentException ignored) {
        }

        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            return online;
        }

        String lowerInput = input.toLowerCase(Locale.ROOT);
        for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
            String name = offlinePlayer.getName();
            if (name != null && name.toLowerCase(Locale.ROOT).equals(lowerInput)) {
                return offlinePlayer;
            }
        }

        try {
            OfflinePlayer fallback = Bukkit.getOfflinePlayer(input);
            if (fallback.isOnline() || fallback.hasPlayedBefore()) {
                return fallback;
            }
        } catch (Exception ex) {
            plugin.getLogger().fine("Player lookup fallback failed for " + input + ": " + ex.getMessage());
        }
        return null;
    }
}
