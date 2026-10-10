package org.enthusia.tools;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.*;
import org.enthusia.tags.rewards.KothRewardsHook;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import java.util.jar.JarFile;

/** Explicit optional companion check: run with the actual built KOTH JAR, never a mirrored API. */
public final class KothMenuArtifactProbe {
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected KOTH JAR and Tags JAR");
        try (JarFile tags = new JarFile(args[1])) {
            if (tags.getJarEntry("net/badgersmc/ek/api/KothRewardsMenuV1.class") != null) throw new AssertionError("Tags must not bundle KOTH API");
        }
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{Path.of(args[0]).toUri().toURL()}, KothMenuArtifactProbe.class.getClassLoader())) {
            Class<?> api=Class.forName("net.badgersmc.ek.api.KothRewardsMenuV1",true,loader);
            if(api.getMethod("open",UUID.class,String.class).getReturnType()!=boolean.class) throw new AssertionError("Wrong menu API signature");
            AtomicBoolean enabled=new AtomicBoolean(true); UUID caller=UUID.randomUUID(); AtomicInteger calls=new AtomicInteger();
            Plugin owner=(Plugin) Proxy.newProxyInstance(loader,new Class[]{Plugin.class},(p,m,a)->switch(m.getName()) {
                case "isEnabled" -> enabled.get(); case "getName" -> "EnthusiaKOTH"; default -> null;
            });
            Object provider=Proxy.newProxyInstance(loader,new Class[]{api},(p,m,a)->{
                if(m.getName().equals("open")) { if(!caller.equals(a[0]) || !"challenges".equals(a[1])) throw new AssertionError("Caller/page changed"); calls.incrementAndGet(); return true; }
                return null;
            });
            AtomicReference<RegisteredServiceProvider<?>> registration=new AtomicReference<>(new RegisteredServiceProvider(api,provider,ServicePriority.Normal,owner));
            PluginManager plugins=(PluginManager) Proxy.newProxyInstance(loader,new Class[]{PluginManager.class},(p,m,a)->m.getName().equals("getPlugin")?owner:null);
            ServicesManager services=(ServicesManager) Proxy.newProxyInstance(loader,new Class[]{ServicesManager.class},(p,m,a)->m.getName().equals("getRegistration") && a[0]==api?registration.get():null);
            Server server=(Server) Proxy.newProxyInstance(loader,new Class[]{Server.class},(p,m,a)->switch(m.getName()) { case "getPluginManager" -> plugins; case "getServicesManager" -> services; default -> null; });
            Player player=(Player) Proxy.newProxyInstance(loader,new Class[]{Player.class},(p,m,a)->switch(m.getName()) { case "isOnline" -> true; case "getUniqueId" -> caller; default -> null; });
            KothRewardsHook hook=new KothRewardsHook(server);
            if(!hook.open(player,"challenges") || calls.get()!=1) throw new AssertionError("Actual KOTH API cannot open through Tags");
            enabled.set(false); if(hook.open(player,"challenges")) throw new AssertionError("Disabled owner accepted");
            enabled.set(true); registration.set(null); if(hook.open(player,"challenges")) throw new AssertionError("Missing registration accepted");
            if(calls.get()!=1) throw new AssertionError("Unavailable provider invoked");
        }
        System.out.println("Actual KOTH/Tags menu contract, caller ownership, disabled and missing-provider checks passed.");
    }
}
