package org.enthusia.tags.rewards;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class VaultHook {
    private Object economy;
    private Class<?> economyClass;
    private java.lang.reflect.Method getBalancePlayer;
    private java.lang.reflect.Method getBalanceOffline;
    private java.lang.reflect.Method depositPlayer;
    private java.lang.reflect.Method depositOffline;

    public void setup() {
        try {
            economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(economyClass);
            if (rsp != null) {
                economy = rsp.getProvider();
                cacheMethods();
            }
        } catch (ClassNotFoundException ignored) {
        }
    }

    public boolean isAvailable() {
        return economy != null;
    }

    public double getBalance(Player player) {
        if (economy == null) {
            return 0.0;
        }
        try {
            if (getBalancePlayer != null) {
                return (double) getBalancePlayer.invoke(economy, player);
            }
            if (getBalanceOffline != null) {
                return (double) getBalanceOffline.invoke(economy, player);
            }
        } catch (ReflectiveOperationException ex) {
            return 0.0;
        }
        return 0.0;
    }

    public void deposit(Player player, double amount) {
        if (economy == null) {
            return;
        }
        try {
            if (depositPlayer != null) {
                depositPlayer.invoke(economy, player, amount);
                return;
            }
            if (depositOffline != null) {
                depositOffline.invoke(economy, player, amount);
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private void cacheMethods() {
        try {
            getBalancePlayer = economyClass.getMethod("getBalance", Player.class);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            getBalanceOffline = economyClass.getMethod("getBalance", org.bukkit.OfflinePlayer.class);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            depositPlayer = economyClass.getMethod("depositPlayer", Player.class, double.class);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            depositOffline = economyClass.getMethod("depositPlayer", org.bukkit.OfflinePlayer.class, double.class);
        } catch (NoSuchMethodException ignored) {
        }
    }
}
