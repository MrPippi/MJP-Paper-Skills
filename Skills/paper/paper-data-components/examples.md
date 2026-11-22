# paper-data-components Examples

## Example 1: Give the custom items and react to eating one

**Input:**
```
items: healing berry, war hammer, glider vest
command: /kit
on eat: tell the player when a Healing Berry was eaten
```

**Output — the effect itself comes from the `CONSUMABLE` component; the listener only adds plugin behavior and recognizes the item by its PDC id:**
```java
package com.example.items;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class ItemsPlugin extends JavaPlugin implements Listener {

    private ItemIds ids;
    private CustomItems items;

    @Override
    public void onEnable() {
        ids = new ItemIds(this);
        items = new CustomItems(this, ids);
        getServer().getPluginManager().registerEvents(this, this);
    }

    /** Called from your command layer (see paper-brigadier-command). */
    public void giveKit(Player player) {
        player.give(items.healingBerry(), items.warHammer(), items.gliderVest());   // leftovers drop at the player's feet
    }

    @EventHandler(ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent event) {
        if (ids.is(event.getItem(), CustomItems.HEALING_BERRY)) {
            event.getPlayer().sendActionBar(Component.text("You feel your wounds closing"));
        }
    }
}
```

---

## Example 2: Vanilla tweaks with set / unset / reset

**Input:**
```
- golden apples stack to 16 only on this server
- a "cursed" apple that cannot be eaten
- repair any tool back to full
```

**Output:**
```java
package com.example.items;

import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class VanillaTweaks {

    private VanillaTweaks() {
    }

    /** setData overrides the prototype value (64) for this one stack. */
    public static ItemStack smallStackGoldenApple(int amount) {
        ItemStack apple = ItemStack.of(Material.GOLDEN_APPLE, amount);
        apple.setData(DataComponentTypes.MAX_STACK_SIZE, 16);
        return apple;
    }

    /** unsetData removes a component even though the apple's prototype has FOOD and CONSUMABLE. */
    public static ItemStack cursedApple() {
        ItemStack apple = ItemStack.of(Material.APPLE);
        ItemChecks.makeInedible(apple);
        return apple;
    }

    /** resetData drops the override, so DAMAGE goes back to the prototype value 0. */
    public static void repairInPlace(ItemStack tool) {
        if (tool.hasData(DataComponentTypes.MAX_DAMAGE)) {
            ItemChecks.repair(tool);
        }
    }
}
```

**Resulting state (apple):**

| After | `hasData(FOOD)` | `isDataOverridden(FOOD)` |
|------|------|------|
| `ItemStack.of(APPLE)` | true | false |
| `unsetData(FOOD)` | false | true |
| `resetData(FOOD)` | true | false |

`MAX_STACK_SIZE` on one stack does not change other golden apples: a 16-stack apple and a normal one no longer stack with each other, because their components differ.

---

## Example 3: Shop that sells any durability of the same item

**Input:**
```
sell price for a used iron sword = same as a new one
```

**Output — compare while ignoring wear and stack size:**
```java
package com.example.items;

import org.bukkit.inventory.ItemStack;

public final class ShopMatcher {

    private final ItemStack template;

    public ShopMatcher(ItemStack template) {
        this.template = template.clone();      // keep a private copy: ItemStack is mutable
    }

    public boolean accepts(ItemStack offered) {
        return ItemChecks.sameIgnoringWear(template, offered);
    }
}
```

Enchantments, names and PDC still count, so a renamed or enchanted sword is not accepted as the plain template.
