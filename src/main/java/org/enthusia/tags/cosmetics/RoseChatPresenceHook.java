package org.enthusia.tags.cosmetics;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
/** Optional binding to the explicit per-viewer companion API, including late enable/re-enable. */
public final class RoseChatPresenceHook {
    private static final String EVENT = "dev.rosewood.rosechat.api.event.PresenceMessageEvent";
    private RoseChatPresenceHook() {}
    @FunctionalInterface interface EventLookup { Class<? extends Event> load(Plugin provider) throws ReflectiveOperationException; }
    public static void register(JavaPlugin owner, CosmeticsService cosmetics) {
        var binding=new Binding(owner,cosmetics,provider -> Class.forName(EVENT,true,provider.getClass().getClassLoader()).asSubclass(Event.class));
        owner.getServer().getPluginManager().registerEvents(binding,owner);
        binding.bind(owner.getServer().getPluginManager().getPlugin("RoseChat"));
    }
    static final class Binding implements Listener {
        private final Plugin owner;
        private final CosmeticsService cosmetics;
        private final EventLookup lookup;
        private Plugin provider;
        private Listener installed;
        Binding(Plugin owner,CosmeticsService cosmetics,EventLookup lookup) { this.owner=owner;this.cosmetics=cosmetics;this.lookup=lookup; }
        void bind(Plugin candidate) {
            if(candidate==null || !candidate.isEnabled() || candidate==provider || !"RoseChat".equals(candidate.getName())) return;
            try {
                Class<? extends Event> eventType=lookup.load(candidate);
                if(eventType.getMethod("getPlayer").getReturnType()!=Player.class || eventType.getMethod("getKind").getReturnType()!=String.class)
                    throw new NoSuchMethodException("Unsupported RoseChat presence contract");
                eventType.getMethod("setLines",List.class);
                Listener delegate=new Listener() {};
                owner.getServer().getPluginManager().registerEvent(eventType,delegate,EventPriority.HIGH,(listener,event)-> {
                    try { applyReplacement(event,cosmetics); } catch(ReflectiveOperationException ex) { throw new EventException(ex); }
                },owner,true);
                installed=delegate; provider=candidate;
            } catch(ReflectiveOperationException | ClassCastException ex) {
                owner.getLogger().warning("RoseChat presence hook unavailable; retaining RoseChat audience and defaults: "+ex.getMessage());
            }
        }
        @EventHandler public void onEnable(PluginEnableEvent event) { bind(event.getPlugin()); }
        @EventHandler public void onDisable(PluginDisableEvent event) {
            if(event.getPlugin()==provider || event.getPlugin()==owner) {
                if(installed!=null) HandlerList.unregisterAll(installed);
                installed=null; provider=null;
            }
        }
    }
    static void applyReplacement(Event event, CosmeticsService cosmetics) throws ReflectiveOperationException {
        if (event instanceof Cancellable cancellable && cancellable.isCancelled())
            return;
        Class<?> type = event.getClass();
        Player player = (Player) type.getMethod("getPlayer").invoke(event);
        String kind = (String) type.getMethod("getKind").invoke(event);
        String message = switch (kind) {
            case "join" -> cosmetics.getJoinMessage(player);
            case "quit" -> cosmetics.getQuitMessage(player);
            default -> null;
        };
        // Original or inaccessible selections leave every original template intact.
        if (Objects.nonNull(message) && !message.isBlank()) {
            Method setter = type.getMethod("setLines", List.class);
            setter.invoke(event, List.of(message));
        }
    }
}
