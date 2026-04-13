package org.enthusia.tags.rewards;

import org.bukkit.Bukkit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

public final class PlaytimeHook {
    private Object service;
    private Method getLifetime;
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
            Class<?> snapshotClass = Class.forName("org.enthusia.playtime.data.model.PlaytimeSnapshot");
            activeMinutesField = snapshotClass.getField("activeMinutes");
            afkMinutesField = snapshotClass.getField("afkMinutes");
            totalMinutesField = snapshotClass.getField("totalMinutes");
            service = loaded;
            getLifetime = lifetimeMethod;
        } catch (ReflectiveOperationException ex) {
            clear();
        }
    }

    public long getMinutes(UUID playerId, RewardCriterionType type) {
        if (service == null || getLifetime == null) {
            return -1L;
        }
        try {
            Object result = getLifetime.invoke(service, playerId);
            if (!(result instanceof Optional<?> opt)) {
                return -1L;
            }
            Object snapshot = opt.orElse(null);
            if (snapshot == null) {
                return 0L;
            }
            return switch (type) {
                case PLAYTIME_ACTIVE_MINUTES -> activeMinutesField.getLong(snapshot);
                case PLAYTIME_AFK_MINUTES -> afkMinutesField.getLong(snapshot);
                case PLAYTIME_TOTAL_MINUTES -> totalMinutesField.getLong(snapshot);
                default -> -1L;
            };
        } catch (ReflectiveOperationException ex) {
            return -1L;
        }
    }

    private void clear() {
        service = null;
        getLifetime = null;
        activeMinutesField = null;
        afkMinutesField = null;
        totalMinutesField = null;
    }
}
