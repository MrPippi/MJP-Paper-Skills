package com.example.probe.shop;

import com.example.probe.Probe;
import com.example.shop.integration.WalletHook;
import org.bukkit.plugin.Plugin;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * paper-service-api: the consumer side (WalletHook, in this jar) against the provider (Wallet, its own jar and class
 * loader). With Wallet installed: calls across plugins, the main-thread rule, and degrading after Wallet is disabled.
 * Without Wallet: the hook reports "unavailable" instead of throwing.
 */
public final class ShopProbe extends Probe {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000beef");

    @Override
    protected long startDelay() {
        return 20L;
    }

    @Override
    protected void start() throws Exception {
        WalletHook hook = new WalletHook(this);
        Plugin wallet = getServer().getPluginManager().getPlugin(WalletHook.PLUGIN_NAME);
        if (wallet == null) {
            check("without Wallet: hook not available", !hook.available());
            check("without Wallet: balance empty", hook.balance(PLAYER).isEmpty());
            check("without Wallet: withdraw returns false", !hook.withdraw(PLAYER, 1, "probe"));
            finish();
            return;
        }
        check("hook available", hook.available());
        check("no account yet: balance empty", hook.balance(PLAYER).isEmpty());
        check("no account yet: withdraw returns false", !hook.withdraw(PLAYER, 1, "probe"));
        // Direct API calls live in WalletCalls so this class never links WalletApi when Wallet is absent
        check("service registered by Wallet", WalletCalls.registeredBy(this, wallet));
        check("deposit 500 through the service -> OK", WalletCalls.deposit(this, PLAYER, 500));
        check("hook sees balance 500", hook.balance(PLAYER).equals(OptionalLong.of(500)));
        check("hook withdraw 200 -> true", hook.withdraw(PLAYER, 200, "probe"));
        check("withdraw 1000 -> false (insufficient)", !hook.withdraw(PLAYER, 1000, "probe"));
        check("balance 300 after the failed withdrawal", hook.balance(PLAYER).equals(OptionalLong.of(300)));
        check("negative deposit -> INVALID_AMOUNT", WalletCalls.negativeDepositRejected(this, PLAYER));
        WalletCalls.callOffMainThread(this, PLAYER).thenAccept(rejected -> getServer().getScheduler().runTask(this, () -> guard(() -> {
            check("call off the main thread -> IllegalStateException", rejected);
            getServer().getPluginManager().disablePlugin(wallet);
            check("after disabling Wallet: hook not available", !hook.available());
            check("after disabling Wallet: balance empty", hook.balance(PLAYER).isEmpty());
            check("after disabling Wallet: service unregistered", !WalletCalls.registered(this));
            finish();
        })));
    }
}
