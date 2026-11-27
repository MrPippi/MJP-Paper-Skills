# Paper Custom Recipes

## Purpose

Register crafting and brewing recipes from a plugin, sync them to players and remove them cleanly. `ShapedRecipe` / `ShapelessRecipe` make new items, `RecipeChoice.itemType(...)` accepts several materials, `TransmuteRecipe` changes the item type while **keeping every component** (iron sword + diamond → a diamond sword with the same enchantments and name), and on 26.3 brewing is an ordinary `BrewingRecipe`. Every recipe change resends the whole list to all players, so the batch is registered with a single resync.

---

## Platform Requirements

- Paper 1.21.11 / 26.3 (compile-verified on both with zero deprecation warnings; runtime-verified on Paper 26.3 and 1.21.11)
- 26.3 deprecated `addRecipe(Recipe, boolean)` and `removeRecipe(key, boolean)` in favor of `addRecipes` / `removeRecipes`, and the `ExactChoice` constructors in favor of `RecipeChoice.exactChoice(...)`; differing lines carry `// @1.21.11:`
- Brewing: `BrewingRecipe` on 26.3, `PotionMix` on 1.21.11 (split with `// @only`)
- Java 21 (1.21.11) / 25 (26.3)
- **Spigot**: Core portable — shaped, shapeless, `TransmuteRecipe` and `ExactChoice` exist in Spigot-API 26.3; `RecipeChoice.itemType` is Paper only (use `MaterialChoice`), and Spigot's `BrewingRecipe` has a different constructor

---

## Generated Code

### CustomRecipes.java

```java
Bukkit.addRecipes(fresh);   // 26.3: one resync for the batch (1.21.11: addRecipe(r, false) + updateRecipes())

// Change the type, keep enchantments / name / damage
new TransmuteRecipe(key("diamond_sword_upgrade"), Material.DIAMOND_SWORD,
    RecipeChoice.itemType(ItemType.IRON_SWORD), RecipeChoice.itemType(ItemType.DIAMOND));

recipe.setIngredient('H', RecipeChoice.itemType(ItemType.STICK, ItemType.BLAZE_ROD)); // any of these
```

### Brewing.java (26.3)

```java
new BrewingRecipe(key, potion(PotionType.REGENERATION),
    RecipeChoice.exactChoice(potion(PotionType.AWKWARD)),   // the three bottom slots
    RecipeChoice.itemType(ItemType.GLOW_BERRIES));           // the ingredient slot
```

### RecipesPlugin.java (re-register after reload)

```java
@EventHandler
public void onResourcesReloaded(ServerResourcesReloadedEvent event) {
    recipes.registerAll(ingredient);   // /minecraft:reload drops plugin recipes
    brewing.register();
}
```

---

## Rules

- Recipe keys stay `<plugin>:<name>`; recipe books store them
- Remove every key on disable; `discoverRecipes` on join so recipes appear in the recipe book
- `/minecraft:reload` rebuilds the recipe list from data packs and **drops plugin recipes** (crafting and brewing, observed on Paper 26.3); re-register in `ServerResourcesReloadedEvent`
- Runtime-verified on Paper 26.3 and 1.21.11: the trident accepts a blaze rod handle, the transmute upgrade keeps Sharpness III and the custom name, a real brewing stand turned Awkward Potions + glow berries into Regeneration, and recipes return after reload

---

## Thread Safety

- `addRecipe(s)`, `removeRecipe(s)`, `updateRecipes` and `discoverRecipes` are main-thread only
- Recipes are server-wide state: register them in `onEnable` (and the reload event), never per player or per event
