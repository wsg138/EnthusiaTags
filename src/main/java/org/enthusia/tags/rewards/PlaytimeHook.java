package org.enthusia.tags.rewards;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class PlaytimeHook {
    private static final String PLAYTIME_PLUGIN_NAME = "EnthusiaPlaytime";
    private static final String PLAYTIME_SERVICE_CLASS = "org.enthusia.playtime.api.PlaytimeService";

    private Object service;
    private Method getLifetime;
    private Method getLiveState;
    private Class<?> snapshotClass;
    private Field activeMinutesField;
    private Field afkMinutesField;
    private Field totalMinutesField;

    public void setup() {
        try {
            bind(resolveService());
        } catch (ReflectiveOperationException | RuntimeException ex) {
            clear();
        }
    }

    public boolean isAvailable() {
        refreshProvider();
        return service != null && getLifetime != null;
    }

    public long getMinutes(UUID playerId, RewardCriterionType type) {
        if (!isAvailable()) {
            return 0L;
        }
        try {
            Object result = getLifetime.invoke(service, playerId);
            if (!(result instanceof Optional<?> optional)) {
                return 0L;
            }
            Object snapshot = optional.orElse(null);
            if (snapshot == null) {
                return 0L;
            }
            bindSnapshot(snapshot);
            return switch (type) {
                case PLAYTIME_ACTIVE_MINUTES -> activeMinutesField.getLong(snapshot);
                case PLAYTIME_AFK_MINUTES -> afkMinutesField.getLong(snapshot);
                case PLAYTIME_TOTAL_MINUTES -> totalMinutesField.getLong(snapshot);
                default -> 0L;
            };
        } catch (ReflectiveOperationException | RuntimeException ex) {
            setup();
            return 0L;
        }
    }

    public String getLiveState(UUID playerId) {
        if (!isAvailable() || getLiveState == null) {
            return "";
        }
        try {
            Object result = getLiveState.invoke(service, playerId);
            return result == null ? "" : result.toString();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            setup();
            return "";
        }
    }

    private Object resolveService() {
        Plugin playtimePlugin = Bukkit.getPluginManager().getPlugin(PLAYTIME_PLUGIN_NAME);
        if (playtimePlugin != null && playtimePlugin.isEnabled()) {
            try {
                Method accessor = playtimePlugin.getClass().getMethod("getPlaytimeService");
                Object direct = accessor.invoke(playtimePlugin);
                if (direct != null) {
                    return direct;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Fall through to Bukkit's service registry for compatibility.
            }
        }

        try {
            ClassLoader loader = playtimePlugin == null
                ? getClass().getClassLoader()
                : playtimePlugin.getClass().getClassLoader();
            Class<?> apiClass = Class.forName(PLAYTIME_SERVICE_CLASS, false, loader);
            return Bukkit.getServicesManager().load(apiClass);
        } catch (ClassNotFoundException | RuntimeException ex) {
            return null;
        }
    }

    private void refreshProvider() {
        try {
            Object current = resolveService();
            if (current != service || getLifetime == null) {
                bind(current);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            clear();
        }
    }

    private void bind(Object loaded) throws ReflectiveOperationException {
        if (loaded == null) {
            clear();
            return;
        }
        Class<?> implementationClass = loaded.getClass();
        Method lifetimeMethod = implementationClass.getMethod("getLifetime", UUID.class);
        Method liveStateMethod = implementationClass.getMethod("getLiveState", UUID.class);
        service = loaded;
        getLifetime = lifetimeMethod;
        getLiveState = liveStateMethod;
        clearSnapshotBinding();
    }

    private void bindSnapshot(Object snapshot) throws NoSuchFieldException {
        Class<?> currentClass = snapshot.getClass();
        if (currentClass == snapshotClass) {
            return;
        }
        activeMinutesField = currentClass.getField("activeMinutes");
        afkMinutesField = currentClass.getField("afkMinutes");
        totalMinutesField = currentClass.getField("totalMinutes");
        snapshotClass = currentClass;
    }

    private void clear() {
        service = null;
        getLifetime = null;
        getLiveState = null;
        clearSnapshotBinding();
    }

    private void clearSnapshotBinding() {
        snapshotClass = null;
        activeMinutesField = null;
        afkMinutesField = null;
        totalMinutesField = null;
    }
}
