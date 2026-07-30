package org.enthusia.tags.rewards;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class VaultHook {
    public record DepositResult(boolean success, double requestedAmount, double responseAmount,
                                String responseType, String errorMessage,
                                double balanceBefore, double balanceAfter) {
        public static DepositResult unavailable(double requestedAmount, String message) {
            return new DepositResult(false, requestedAmount, 0D, "UNAVAILABLE", message, 0D, 0D);
        }
    }
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

    public boolean deposit(Player player, double amount) {
        return depositDetailed(player, amount).success();
    }

    public DepositResult depositDetailed(Player player, double amount) {
        double before = getBalance(player);
        if (economy == null) {
            return new DepositResult(false, amount, 0D, "UNAVAILABLE", "Vault economy unavailable", before, before);
        }
        try {
            Object response = null;
            if (depositPlayer != null) {
                response = depositPlayer.invoke(economy, player, amount);
            } else if (depositOffline != null) {
                response = depositOffline.invoke(economy, player, amount);
            }
            return describeResponse(response, amount, before, getBalance(player));
        } catch (ReflectiveOperationException ignored) {
            return new DepositResult(false, amount, 0D, "EXCEPTION", ignored.getMessage(), before, getBalance(player));
        }
    }

    private DepositResult describeResponse(Object response, double requested, double before, double after) {
        if (response == null) {
            return new DepositResult(false, requested, 0D, "NULL", "Economy returned no response", before, after);
        }
        try {
            boolean success = Boolean.TRUE.equals(response.getClass().getMethod("transactionSuccess").invoke(response));
            double amount = ((Number) response.getClass().getField("amount").get(response)).doubleValue();
            Object type = response.getClass().getField("type").get(response);
            Object error = response.getClass().getField("errorMessage").get(response);
            return new DepositResult(success, requested, amount, String.valueOf(type),
                error == null ? "" : error.toString(), before, after);
        } catch (ReflectiveOperationException ex) {
            return new DepositResult(false, requested, 0D, "INVALID_RESPONSE", ex.getMessage(), before, after);
        }
    }

    private boolean transactionSucceeded(Object response) {
        if (response == null) return false;
        try {
            return Boolean.TRUE.equals(response.getClass().getMethod("transactionSuccess").invoke(response));
        } catch (ReflectiveOperationException ex) {
            return false;
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
