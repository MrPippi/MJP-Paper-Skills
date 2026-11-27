package com.example.probe.shop;

import com.example.wallet.api.ApiResult;
import com.example.wallet.api.WalletApi;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Direct WalletApi calls, kept out of ShopProbe so that class loads without Wallet installed. */
final class WalletCalls {

    private WalletCalls() {
    }

    static boolean registeredBy(JavaPlugin plugin, Plugin provider) {
        RegisteredServiceProvider<WalletApi> registration = plugin.getServer().getServicesManager().getRegistration(WalletApi.class);
        return registration != null && registration.getPlugin() == provider;
    }

    static boolean registered(JavaPlugin plugin) {
        return plugin.getServer().getServicesManager().load(WalletApi.class) != null;
    }

    static boolean deposit(JavaPlugin plugin, UUID player, long amount) {
        WalletApi api = plugin.getServer().getServicesManager().load(WalletApi.class);
        return api != null && api.deposit(player, amount, "probe") == ApiResult.OK;
    }

    static boolean negativeDepositRejected(JavaPlugin plugin, UUID player) {
        WalletApi api = plugin.getServer().getServicesManager().load(WalletApi.class);
        return api != null && api.deposit(player, -1, "probe") == ApiResult.INVALID_AMOUNT;
    }

    /** Calls the API from a pool thread; completes with whether the call was refused. */
    static CompletableFuture<Boolean> callOffMainThread(JavaPlugin plugin, UUID player) {
        WalletApi api = plugin.getServer().getServicesManager().load(WalletApi.class);
        return CompletableFuture.supplyAsync(() -> {
            try {
                api.balance(player);
                return false;
            } catch (IllegalStateException expected) {
                return true;
            }
        });
    }
}
