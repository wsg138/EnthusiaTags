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
    private static final String API="net.badgersmc.ek.api.KothRewardsMenuV1";
    private static final String PAGE="challenges";
    private static final int ARGUMENTS=2;
    private static final int EXPECTED_CALLS=1;
    private record Fixture(Server server,Player player,AtomicBoolean enabled,AtomicInteger calls,
                           AtomicReference<RegisteredServiceProvider<?>> registration) { }

    public static void main(String[] args) throws Exception {
        require(args.length==ARGUMENTS,"Expected KOTH JAR and Tags JAR");
        verifyTagsArtifact(args[1]);
        try(URLClassLoader loader=providerLoader(args[0])) {
            Class<?> api=Class.forName(API,true,loader);
            require(api.getMethod("open",UUID.class,String.class).getReturnType()==boolean.class,"Wrong menu API signature");
            verifyNavigation(fixture(loader,api));
        }
        System.out.println("Actual KOTH/Tags menu contract, caller ownership, disabled and missing-provider checks passed.");
    }
    private static void verifyTagsArtifact(String path) throws Exception {
        try(JarFile tags=new JarFile(path)) {
            require(tags.getJarEntry(API.replace('.','/')+".class")==null,"Tags must not bundle KOTH API");
        }
    }
    /** Explicit artifact loader boundary, not a J2EE context lookup. */
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static URLClassLoader providerLoader(String path) throws Exception {
        return new URLClassLoader(new java.net.URL[]{Path.of(path).toUri().toURL()},KothMenuArtifactProbe.class.getClassLoader());
    }
    private static void verifyNavigation(Fixture fixture) {
        KothRewardsHook hook=new KothRewardsHook(fixture.server());
        require(hook.open(fixture.player(),PAGE),"Actual KOTH API cannot open through Tags");
        require(fixture.calls().get()==EXPECTED_CALLS,"Unexpected number of provider calls");
        fixture.enabled().set(false);
        require(!hook.open(fixture.player(),PAGE),"Disabled owner accepted");
        fixture.enabled().set(true); fixture.registration().set(null);
        require(!hook.open(fixture.player(),PAGE),"Missing registration accepted");
        require(fixture.calls().get()==EXPECTED_CALLS,"Unavailable provider invoked");
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private static Fixture fixture(ClassLoader loader,Class<?> api) {
        AtomicBoolean enabled=new AtomicBoolean(true); UUID caller=UUID.randomUUID(); AtomicInteger calls=new AtomicInteger();
        Plugin owner=(Plugin)Proxy.newProxyInstance(loader,new Class[]{Plugin.class},(p,m,a)->switch(m.getName()) {
            case "isEnabled" -> enabled.get(); case "getName" -> "EnthusiaKOTH"; default -> null;
        });
        Object provider=Proxy.newProxyInstance(loader,new Class[]{api},(p,m,a)->{
            require(caller.equals(a[0]),"Caller changed"); require(PAGE.equals(a[1]),"Page changed"); calls.incrementAndGet(); return true;
        });
        AtomicReference<RegisteredServiceProvider<?>> registration=new AtomicReference<>(new RegisteredServiceProvider(api,provider,ServicePriority.Normal,owner));
        PluginManager plugins=(PluginManager)Proxy.newProxyInstance(loader,new Class[]{PluginManager.class},(p,m,a)->owner);
        ServicesManager services=(ServicesManager)Proxy.newProxyInstance(loader,new Class[]{ServicesManager.class},(p,m,a)->a[0]==api?registration.get():null);
        Server server=(Server)Proxy.newProxyInstance(loader,new Class[]{Server.class},(p,m,a)->switch(m.getName()) { case "getPluginManager" -> plugins; case "getServicesManager" -> services; default -> null; });
        Player player=(Player)Proxy.newProxyInstance(loader,new Class[]{Player.class},(p,m,a)->switch(m.getName()) { case "isOnline" -> true; case "getUniqueId" -> caller; default -> null; });
        return new Fixture(server,player,enabled,calls,registration);
    }
    private static void require(boolean condition,String failure) {
        if(!condition) throw new AssertionError(failure);
    }
}
