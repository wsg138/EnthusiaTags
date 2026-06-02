package org.enthusia.tags;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class VanishHook {
    private List<String> metadataKeys = List.of("vanished", "vanish");
    private boolean treatInvisibilityEffectAsVanish;
    private Plugin essentialsPlugin;
    private Class<?> essentialsClass;
    private Method essentialsGetUserPlayer;
    private Method essentialsGetUserUuid;
    private Method myzelyamMethod;

    public void reload(JavaPlugin plugin) {
        metadataKeys = new ArrayList<>(plugin.getConfig().getStringList("vanish.metadata-keys"));
        if (metadataKeys.isEmpty()) {
            metadataKeys = List.of("vanished", "vanish");
        }
        treatInvisibilityEffectAsVanish = plugin.getConfig().getBoolean("vanish.treat-invisibility-effect-as-vanish", false);
        cacheEssentials();
        cacheMyzelyam();
    }

    public boolean isVanished(Player player) {
        if (player == null) {
            return false;
        }
        if (treatInvisibilityEffectAsVanish
            && (player.isInvisible() || player.hasPotionEffect(PotionEffectType.INVISIBILITY))) {
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
        for (String key : metadataKeys) {
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
        Plugin plugin = essentialsPlugin;
        if (plugin == null || essentialsClass == null) {
            return false;
        }
        try {
            if (!essentialsClass.isInstance(plugin)) {
                return false;
            }
            Object user = tryInvokeGetUser(plugin, player);
            if (user == null) {
                return false;
            }
            Method isVanished = user.getClass().getMethod("isVanished");
            Object result = isVanished.invoke(user);
            return result instanceof Boolean && (Boolean) result;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Object tryInvokeGetUser(Plugin plugin, Player player) {
        if (essentialsGetUserPlayer != null) {
            try {
                return essentialsGetUserPlayer.invoke(plugin, player);
            } catch (Exception ignored) {
            }
        }
        if (essentialsGetUserUuid != null) {
            try {
                return essentialsGetUserUuid.invoke(plugin, player.getUniqueId());
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void cacheEssentials() {
        essentialsPlugin = Bukkit.getPluginManager().getPlugin("Essentials");
        essentialsClass = null;
        essentialsGetUserPlayer = null;
        essentialsGetUserUuid = null;
        if (essentialsPlugin == null) {
            return;
        }
        try {
            essentialsClass = Class.forName("com.earth2me.essentials.Essentials");
            essentialsGetUserPlayer = essentialsClass.getMethod("getUser", Player.class);
        } catch (Exception ignored) {
        }
        try {
            if (essentialsClass == null) {
                essentialsClass = Class.forName("com.earth2me.essentials.Essentials");
            }
            essentialsGetUserUuid = essentialsClass.getMethod("getUser", java.util.UUID.class);
        } catch (Exception ignored) {
        }
    }

    private boolean isMyzelyamVanished(Player player) {
        if (myzelyamMethod == null) {
            return false;
        }
        try {
            Object result = myzelyamMethod.invoke(null, player);
            return result instanceof Boolean && (Boolean) result;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void cacheMyzelyam() {
        myzelyamMethod = null;
        try {
            Class<?> vanishApi = Class.forName("de.myzelyam.api.vanish.VanishAPI");
            try {
                myzelyamMethod = vanishApi.getMethod("isInvisible", Player.class);
            } catch (NoSuchMethodException ignored) {
                myzelyamMethod = vanishApi.getMethod("isVanished", Player.class);
            }
        } catch (Exception ignored) {
        }
    }
}
