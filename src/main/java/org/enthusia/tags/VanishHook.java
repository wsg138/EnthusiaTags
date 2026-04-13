package org.enthusia.tags;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;

public final class VanishHook {
    private static final String[] METADATA_KEYS = new String[] {
        "vanished",
        "vanish",
        "invisible",
        "invis"
    };

    public boolean isVanished(Player player) {
        if (player == null) {
            return false;
        }
        if (player.isInvisible()) {
            return true;
        }
        if (hasVanishedMetadata(player)) {
            return true;
        }
        if (isEssentialsVanished(player)) {
            return true;
        }
        return isMyzelyamVanished(player);
    }

    private boolean hasVanishedMetadata(Player player) {
        for (String key : METADATA_KEYS) {
            if (!player.hasMetadata(key)) {
                continue;
            }
            List<MetadataValue> values = player.getMetadata(key);
            for (MetadataValue value : values) {
                try {
                    if (value.asBoolean()) {
                        return true;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    private boolean isEssentialsVanished(Player player) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Essentials");
        if (plugin == null) {
            return false;
        }
        try {
            Class<?> essentialsClass = Class.forName("com.earth2me.essentials.Essentials");
            if (!essentialsClass.isInstance(plugin)) {
                return false;
            }
            Object user = tryInvokeGetUser(essentialsClass, plugin, player);
            if (user == null) {
                return false;
            }
            Method isVanished = user.getClass().getMethod("isVanished");
            Object result = isVanished.invoke(user);
            return result instanceof Boolean && (Boolean) result;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Object tryInvokeGetUser(Class<?> essentialsClass, Plugin plugin, Player player) {
        try {
            Method getUser = essentialsClass.getMethod("getUser", Player.class);
            return getUser.invoke(plugin, player);
        } catch (Exception ignored) {
        }
        try {
            Method getUser = essentialsClass.getMethod("getUser", java.util.UUID.class);
            return getUser.invoke(plugin, player.getUniqueId());
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isMyzelyamVanished(Player player) {
        try {
            Class<?> vanishApi = Class.forName("de.myzelyam.api.vanish.VanishAPI");
            Method method;
            try {
                method = vanishApi.getMethod("isInvisible", Player.class);
            } catch (NoSuchMethodException ignored) {
                method = vanishApi.getMethod("isVanished", Player.class);
            }
            Object result = method.invoke(null, player);
            return result instanceof Boolean && (Boolean) result;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }
}
