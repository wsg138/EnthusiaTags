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
    private static final Set<String> PAGES = Set.of("home", "challenges", "claims", "results");
    private final Server server;
    private final Function<Plugin, Class<?>> api;
    public KothRewardsHook(Server server) {
        this(server, KothRewardsHook::loadOwnerApi);
    }
    KothRewardsHook(Server server, Function<Plugin, Class<?>> api) { this.server = server; this.api = api; }
    public boolean installed() { return server.getPluginManager().getPlugin("EnthusiaKOTH") != null; }
    public boolean open(Player player, String page) {
        if (!validPage(page) || !player.isOnline()) return false;
        Plugin owner = enabledOwner();
        if (owner == null) return false;
        try {
            Target target = registeredTarget(owner);
            if (target == null) return false;
            return Boolean.TRUE.equals(target.type().getMethod("open", UUID.class, String.class).invoke(target.provider(), player.getUniqueId(), page));
        } catch (InvocationTargetException invocation) {
            rethrowFatal(invocation.getCause());
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }
    private Plugin enabledOwner() {
        Plugin owner = server.getPluginManager().getPlugin("EnthusiaKOTH");
        return owner != null && owner.isEnabled() ? owner : null;
    }
    private record Target(Class<?> type, Object provider) { }
    private Target registeredTarget(Plugin owner) {
        Class<?> type = api.apply(owner);
        if (type == null) return null;
        RegisteredServiceProvider<?> registration = server.getServicesManager().getRegistration(type);
        if (registration == null || registration.getPlugin() != owner || !owner.isEnabled()) return null;
        Object provider = registration.getProvider();
        return type.isInstance(provider) ? new Target(type, provider) : null;
    }
    /** Bukkit plugin identity needs its owner loader; a J2EE context loader can resolve another API copy. */
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static Class<?> loadOwnerApi(Plugin owner) {
        try { return Class.forName(API, false, owner.getClass().getClassLoader()); }
        catch (ClassNotFoundException missing) { return null; }
    }
    private static void rethrowFatal(Throwable cause) {
        if (cause instanceof VirtualMachineError fatal) throw fatal;
        if (cause instanceof ThreadDeath fatal) throw fatal;
    }
    static boolean validPage(String page) { return PAGES.contains(page); }
}
