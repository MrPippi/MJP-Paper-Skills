# paper-custom-recipes Examples

## Example 1: Ingredient recognized by PDC id instead of ExactChoice

**Input:**
```
recipe: 1 "Healing Berry" (plugin item) + 1 bread -> 2 "Healing Bread"
the berry may have any stack size or durability-like data; only its id matters
```

**Output — register with a broad choice (the berry's item type), then reject non-plugin berries in `PrepareItemCraftEvent`:**
```java
package com.example.recipes;

import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class HealingBreadRecipe implements Listener {

    private final NamespacedKey recipeKey;
    private final NamespacedKey idKey;

    public HealingBreadRecipe(Plugin plugin) {
        this.recipeKey = new NamespacedKey(plugin, "healing_bread");
        this.idKey = new NamespacedKey(plugin, "item_id");    // same key as ItemIds in paper-data-components
    }

    public ShapelessRecipe recipe(ItemStack result) {
        ShapelessRecipe recipe = new ShapelessRecipe(recipeKey, result);
        recipe.addIngredient(RecipeChoice.itemType(ItemType.GLOW_BERRIES));
        recipe.addIngredient(RecipeChoice.itemType(ItemType.BREAD));
        return recipe;
    }

    /** Runs whenever the grid changes; clearing the result is how a recipe is refused. */
    @EventHandler
    public void onPrepare(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof Keyed keyed) || !keyed.getKey().equals(recipeKey)) {
            return;
        }
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (ingredient != null && ingredient.getType() == Material.GLOW_BERRIES
                    && !"healing_berry".equals(ingredient.getPersistentDataContainer().get(idKey, PersistentDataType.STRING))) {
                event.getInventory().setResult(null);   // plain glow berries do not count
                return;
            }
        }
    }
}
```

- Use `ExactChoice` when the ingredient is always an identical stack; use this pattern when only an id matters

---

## Example 2: Disable a vanilla recipe

**Input:**
```
TNT cannot be crafted on this server
```

**Output:**
```java
package com.example.recipes;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

public final class VanillaRecipeRules {

    private static final NamespacedKey TNT = NamespacedKey.minecraft("tnt");

    private VanillaRecipeRules() {
    }

    /** Call from onEnable (and again after /minecraft:reload, which restores data pack recipes). */
    public static void apply() {
        Bukkit.removeRecipe(TNT);                 // resends the recipe list
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.undiscoverRecipe(TNT);         // also hide it from recipe books that already had it
        }
    }
}
```

- Vanilla recipes come from the built-in data pack; listen to `ServerResourcesReloadedEvent` and call `apply()` again so a reload does not bring TNT back
