# paper-registry-events Examples

## Example 1: Spike trap that kills with its own death message

**Input:**
```
damage_types: bleed (registered in ContentBootstrap)
trap: polished blackstone pressure plate, 4 damage
death message: "<player> bled out on a spike trap"
```

**Output — reuse the `ContentKeys.BLEED` type registered by `ContentBootstrap`; the location makes the damage directional (knockback, armor) without a causing entity:**
```java
package com.example.content;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

/** Register from onEnable after the lookup succeeded: new SpikeTrapListener(bleed). */
public final class SpikeTrapListener implements Listener {

    private static final double SPIKE_DAMAGE = 4.0;

    private final DamageType bleed;

    public SpikeTrapListener(DamageType bleed) {
        this.bleed = bleed;
    }

    /** Convenience for callers that only have the registry. Returns null when bootstrap failed. */
    public static SpikeTrapListener create() {
        DamageType type = RegistryAccess.registryAccess().getRegistry(RegistryKey.DAMAGE_TYPE).get(ContentKeys.BLEED);
        return type == null ? null : new SpikeTrapListener(type);
    }

    @EventHandler(ignoreCancelled = true)
    public void onStep(PlayerInteractEvent event) {
        Block plate = event.getClickedBlock();
        if (event.getAction() != Action.PHYSICAL || plate == null
                || plate.getType() != Material.POLISHED_BLACKSTONE_PRESSURE_PLATE) {
            return;
        }
        DamageSource source = DamageSource.builder(bleed)
                .withDamageLocation(plate.getLocation().toCenterLocation())
                .build();
        event.getPlayer().damage(SPIKE_DAMAGE, source);
    }
}
```

**Death message — the client resolves `death.attack.<messageId>`, so ship the text in the server resource pack (`assets/example/lang/en_us.json`):**
```json
{
  "death.attack.example.bleed": "%1$s bled out on a spike trap",
  "death.attack.example.bleed.player": "%1$s bled out while fighting %2$s"
}
```

- `%1$s` is the victim and `%2$s` the causing entity; the `.player` variant is used when a causing entity is set (the Lifesteal follow-up damage)
- Without the resource pack the chat shows the raw key; if that is not acceptable, register the type with a vanilla `messageId` such as `"generic"`

---

## Example 2: Rebalance vanilla enchantments (no new content)

**Input:**
```
vanilla_changes:
  - Protection: max level 3 (vanilla 4)
  - Mending: no longer sold by librarians
```

**Output — `entryAdd` with a filter changes one entry; the tag change goes through `postFlatten` so it survives `/minecraft:reload`:**
```java
package com.example.balance;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.keys.EnchantmentKeys;
import io.papermc.paper.registry.keys.tags.EnchantmentTagKeys;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.enchantments.Enchantment;

import java.util.Collection;
import java.util.List;

/** paper-plugin.yml: bootstrapper: com.example.balance.BalanceBootstrap (no createPlugin override needed). */
public final class BalanceBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(BootstrapContext context) {
        ComponentLogger log = context.getLogger();
        try {
            context.getLifecycleManager().registerEventHandler(RegistryEvents.ENCHANTMENT.entryAdd()
                    .newHandler(event -> event.builder().maxLevel(3))
                    .filter(EnchantmentKeys.PROTECTION));

            context.getLifecycleManager().registerEventHandler(LifecycleEvents.TAGS.postFlatten(RegistryKey.ENCHANTMENT), event -> {
                try {
                    Collection<TypedKey<Enchantment>> tradeable = event.registrar().getTag(EnchantmentTagKeys.TRADEABLE);
                    List<TypedKey<Enchantment>> withoutMending = tradeable.stream()
                            .filter(key -> !key.equals(EnchantmentKeys.MENDING))
                            .toList();
                    event.registrar().setTag(EnchantmentTagKeys.TRADEABLE, withoutMending);
                } catch (RuntimeException e) {
                    log.error("Could not remove Mending from #tradeable", e);
                }
            });
        } catch (RuntimeException e) {
            log.error("Failed to register balance handlers", e);
        }
    }
}
```

**Notes:**
- Existing items keep their level (Protection IV books stay IV); the new maximum applies to the enchanting table, anvils and `/enchant`
- Villagers that already offer Mending keep that trade; only new trades follow the changed tag
- `setTag` replaces the whole tag, so always start from `getTag` as above instead of writing a list by hand

---

## Example 3: Checking the result in-game

```
/enchant @s example:lifesteal 3        -> works on swords and axes (supportedItems)
enchant a sword at an enchanting table  -> Lifesteal can be offered (in_enchanting_table + primaryItems)
/damage @s 2 example:bleed             -> armor is ignored (bypasses_armor)
/enchant @s minecraft:sharpness 6      -> accepted (maxLevel raised by entryAdd)
```

If a command reports an unknown key, the corresponding bootstrap handler failed; `ContentPlugin` logs the reason at startup.
