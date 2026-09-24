package org.enthusia.tags.cosmetics;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.server.*;
import org.bukkit.plugin.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
class RoseChatLateBindingTest {
    public static class Presence extends Event {
        static final HandlerList HANDLERS=new HandlerList();
        public Player getPlayer(){return null;} public String getKind(){return "join";}
        public void setLines(List<String> lines){} public HandlerList getHandlers(){return HANDLERS;}
        public static HandlerList getHandlerList(){return HANDLERS;}
    }
    @Test void lateEnableBindsOnceAndReenableRebindsWithoutDuplicateHandlers() {
        Plugin owner=mock(Plugin.class); Plugin provider=mock(Plugin.class);
        Server server=mock(Server.class); PluginManager manager=mock(PluginManager.class);
        when(owner.getServer()).thenReturn(server); when(server.getPluginManager()).thenReturn(manager);
        when(owner.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(provider.getName()).thenReturn("RoseChat"); when(provider.isEnabled()).thenReturn(true);
        var binding=new RoseChatPresenceHook.Binding(owner,new CosmeticsService(null,null,null),p->Presence.class);
        try(var bukkit=mockStatic(org.bukkit.Bukkit.class)) {
        bukkit.when(org.bukkit.Bukkit::isPrimaryThread).thenReturn(true);
        binding.bind(null); binding.onEnable(new PluginEnableEvent(provider)); binding.onEnable(new PluginEnableEvent(provider));
        verify(manager,times(1)).registerEvent(eq(Presence.class),any(Listener.class),eq(EventPriority.HIGH),any(EventExecutor.class),eq(owner),eq(true));
        binding.onDisable(new PluginDisableEvent(provider)); binding.onEnable(new PluginEnableEvent(provider));
        verify(manager,times(2)).registerEvent(eq(Presence.class),any(Listener.class),eq(EventPriority.HIGH),any(EventExecutor.class),eq(owner),eq(true));
        }
    }
}
