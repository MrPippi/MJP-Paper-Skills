# examples — paper-client-side-effects

## Example 1: Player toggles a preference on the settings page, applied immediately

**Input:**
```
base_package: com.example.effects
effects: border, time, weather, night-vision
preference_source: SettingsApi
```

**Output — after the settings page writes the preference it only calls `refresh`; `reconcile()` decides whether to apply or restore:**
```java
import com.example.effects.EffectState;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Called by the settings page on the main thread after the preference is written. Preferences are re-read from SettingsHook; no effect is applied manually here. */
public final class SettingsPageActions {

    private final EffectState state;

    public SettingsPageActions(EffectState state) {
        this.state = state;
    }

    public void onToggled(Player player) {
        state.refresh(player);
        player.sendMessage(Component.text("Your visual settings were updated."));
    }
}
```

**Why not call `addPotionEffect` directly when toggling:** when night vision is turned off, `reconcile()` removes only the infinite night vision, so a finite potion the player drank themselves is not deleted by mistake; when turned on, a finite night vision already on the player is overwritten with a permanent one.

---

## Example 2: Hide non-friends only in the lobby, restored automatically when leaving

**Input:**
```
effects: visibility
visibility_rule: In the lobby only friends are visible; in other worlds everyone is visible
```

**Output — the rule only answers "should this player be hidden"; the service only touches players whose state changed:**
```java
import com.example.effects.PlayerVisibilityService;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

public final class LobbyFriendsPolicy implements PlayerVisibilityService.Policy {

    private final Predicate<World> isLobby;
    private final Function<UUID, Set<UUID>> friendsOf;

    public LobbyFriendsPolicy(Predicate<World> isLobby, Function<UUID, Set<UUID>> friendsOf) {
        this.isLobby = isLobby;
        this.friendsOf = friendsOf;
    }

    @Override
    public boolean shouldHide(Player viewer, Player target) {
        if (!isLobby.test(viewer.getWorld())) return false;
        return !friendsOf.apply(viewer.getUniqueId()).contains(target.getUniqueId());
    }
}
```

**Wiring:** `new PlayerVisibilityService(plugin, new LobbyFriendsPolicy(world -> world.getName().equals("lobby"), friendsLookup))`.
When the friends list or the "lobby" definition changes without an event, call `refreshAll()` from a timer of about 20 ticks; it calls `hidePlayer`/`showPlayer` only for differences, costing a quadratic number of cache lookups in the online player count and sending no packets.

**Note:** `hidePlayer` also removes the hidden player from the TAB list.

---

## Example 3: When the real border shrinks, the low-health vignette updates with it

**Input:**
```
effects: border
```

**Output — an admin shrinks the border with a command; the vignette is corrected by `WorldBorderBoundsChangeEvent` together with the periodic scan, so the command never touches players:**
```java
import io.papermc.paper.util.Tick;
import org.bukkit.World;

import java.time.Duration;

public final class BorderCommands {

    private BorderCommands() {}

    /** Shrinks the border. EffectListener re-copies each player's virtual border on the next tick, and tick() then follows the gradual change. */
    public static void shrink(World world, double newSize, Duration over) {
        world.getWorldBorder().changeSize(newSize, Tick.tick().fromDuration(over)); // setSize(double, long seconds) is deprecated for removal
    }
}
```

**Verification (manual):** run `/effect give @s instant_damage` on yourself until health drops below the threshold -> the vignette appears -> `/worldborder set 100 30` -> the vignette's warning distance changes gradually with the border instead of staying at the old size -> after healing the vignette disappears and the player returns to the real border.

---

## Example 4: Screen post effect during a "fear" debuff (26.3+)

**Input:**
```
effects: post-effect
post_effect_key: example:fear   # defined by the server resource pack
duration: 10s
```

**Output — Paper 26.3 added `Player#postEffects()` (the API behind the vanilla `/posteffect` command). The list is saved in the player's data file, so a temporary effect must be removed by the plugin itself, including on quit and on disable:**
```java
// @only 26.3
package com.example.effects;

import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Temporary full-screen post effect (shader) for one player. Main thread only.
 *
 * <p>Post effects persist: the server stores them in the player's data ("post_effects") and resends them
 * after a dimension change. Anything this class adds is therefore removed again on expiry, quit and disable,
 * or the player would keep the effect after a restart.
 */
public final class PostEffectService implements Listener {

    private final Plugin plugin;
    private final Key effect;
    private final Map<UUID, BukkitTask> expiries = new HashMap<>();

    public PostEffectService(Plugin plugin, Key effect) {
        this.plugin = plugin;
        this.effect = effect;
    }

    /** Applies the effect for {@code ticks} ticks; calling again extends it. */
    public void apply(Player player, long ticks) {
        player.postEffects().add(effect);        // no-op when already present; synced to the client on the next tick
        BukkitTask previous = expiries.put(player.getUniqueId(),
                Bukkit.getScheduler().runTaskLater(plugin, () -> expire(player.getUniqueId()), ticks));
        if (previous != null) {
            previous.cancel();
        }
    }

    public void remove(Player player) {
        BukkitTask task = expiries.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        player.postEffects().remove(effect);     // only our key: effects added by /posteffect or other plugins stay
    }

    /** onDisable: the scheduler no longer runs, so remove every effect this plugin added. */
    public void removeAll() {
        for (UUID id : Map.copyOf(expiries).keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                remove(player);
            }
        }
        expiries.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        remove(event.getPlayer());               // before the player data is saved, so it is not persisted
    }

    private void expire(UUID id) {
        expiries.remove(id);
        Player player = Bukkit.getPlayer(id);
        if (player != null) {
            player.postEffects().remove(effect);
        }
    }
}
```

**Notes:**
- The key names a post effect from the client's resources (vanilla or the server resource pack). The server does not validate it; an unknown key simply shows nothing.
- `add` / `remove` / `set` / `clear` mark the list dirty and the server sends it on the next player tick; call `postEffects().update()` only when it must reach the client immediately.
- 1.21.11 has no post effect API (`Player#postEffects()` and the `/posteffect` command were added in 26.3).
