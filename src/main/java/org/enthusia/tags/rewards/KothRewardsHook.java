package org.enthusia.tags.rewards;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.lang.reflect.InvocationTargetException;

/** Optional navigation adapter. No reward IDs, progress or payouts cross this boundary. */
public final class KothRewardsHook {
    static final String API = "net.badgersmc.ek.api.KothRewardsMenuV1";
    private final Server server;
    private final Function<Plugin, Class<?>> api;
    public KothRewardsHook(Server server) {
        this(server, owner -> {
            try { return Class.forName(API, false, owner.getClass().getClassLoader()); }
            catch (ClassNotFoundException missing) { return null; }
        });
    }
    KothRewardsHook(Server server, Function<Plugin, Class<?>> api) { this.server = server; this.api = api; }
    public boolean installed() { return server.getPluginManager().getPlugin("EnthusiaKOTH") != null; }
    public boolean open(Player player, String page) {
        if (!validPage(page) || !player.isOnline()) return false;
        Plugin owner = server.getPluginManager().getPlugin("EnthusiaKOTH");
        if (owner == null || !owner.isEnabled()) return false;
        try {
            Class<?> type = api.apply(owner);
            if (type == null) return false;
            RegisteredServiceProvider<?> registration = server.getServicesManager().getRegistration(type);
            if (registration == null || registration.getPlugin() != owner || !owner.isEnabled()) return false;
            Object provider = registration.getProvider();
            if (!type.isInstance(provider)) return false;
            return Boolean.TRUE.equals(type.getMethod("open", UUID.class, String.class).invoke(provider, player.getUniqueId(), page));
        } catch (InvocationTargetException invocation) {
            Throwable cause = invocation.getCause();
            if (cause instanceof VirtualMachineError fatal) throw fatal;
            if (cause instanceof ThreadDeath fatal) throw fatal;
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }
    static boolean validPage(String page) { return Set.of("home", "challenges", "claims", "results").contains(page); }
}
