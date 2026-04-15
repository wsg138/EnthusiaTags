package org.enthusia.tags.rewards;

import org.bukkit.Bukkit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

public final class PlaytimeHook {
    private Object service;
    private Method getLifetime;
    private Method getLiveState;
    private Field activeMinutesField;
    private Field afkMinutesField;
    private Field totalMinutesField;

    public void setup() {
        try {
            Class<?> serviceClass = Class.forName("org.enthusia.playtime.api.PlaytimeService");
            Object loaded = Bukkit.getServicesManager().load(serviceClass);
            if (loaded == null) {
                clear();
                return;
            }
            Method lifetimeMethod = serviceClass.getMethod("getLifetime", UUID.class);
            Method liveStateMethod = serviceClass.getMethod("getLiveState", UUID.class);
            Class<?> snapshotClass = Class.forName("org.enthusia.playtime.data.model.PlaytimeSnapshot");
            activeMinutesField = snapshotClass.getField("activeMinutes");
            afkMinutesField = snapshotClass.getField("afkMinutes");
            totalMinutesField = snapshotClass.getField("totalMinutes");
            service = loaded;
            getLifetime = lifetimeMethod;
            getLiveState = liveStateMethod;
        } catch (ReflectiveOperationException ex) {
            clear();
        }
    }

    public boolean isAvailable() {
        return service != null && getLifetime != null;
    }

    public long getMinutes(UUID playerId, RewardCriterionType type) {
        if (!isAvailable()) {
            return 0L;
        }
        try {
            Object result = getLifetime.invoke(service, playerId);
            if (!(result instanceof Optional<?> opt)) {
                return 0L;
            }
            Object snapshot = opt.orElse(null);
            if (snapshot == null) {
                return 0L;
            }
            return switch (type) {
                case PLAYTIME_ACTIVE_MINUTES -> activeMinutesField.getLong(snapshot);
                case PLAYTIME_AFK_MINUTES -> afkMinutesField.getLong(snapshot);
                case PLAYTIME_TOTAL_MINUTES -> totalMinutesField.getLong(snapshot);
                default -> 0L;
            };
        } catch (ReflectiveOperationException ex) {
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
        } catch (ReflectiveOperationException ex) {
            return "";
        }
    }

    private void clear() {
        service = null;
        getLifetime = null;
        getLiveState = null;
        activeMinutesField = null;
        afkMinutesField = null;
        totalMinutesField = null;
    }
}
