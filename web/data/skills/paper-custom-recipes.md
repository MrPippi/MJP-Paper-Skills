---
id: paper-custom-recipes
title: Paper Custom Recipes
titleZh: Paper 自訂配方
description: Paper custom recipes - shaped and shapeless crafting, ItemTypeChoice alternatives, TransmuteRecipe upgrades that keep enchantments, 26.3 BrewingRecipe (PotionMix on 1.21.11), batch registration with one resync and re-registration after /minecraft:reload.
descriptionZh: Paper 自訂配方：有形狀／無形狀合成、ItemTypeChoice 多材料、TransmuteRecipe 升級保留附魔、26.3 BrewingRecipe（1.21.11 用 PotionMix）、批次註冊只同步一次，以及 /minecraft:reload 後重新註冊。
version: "1.0.0"
status: active
category: paper-gameplay
categoryLabel: Paper 玩法
categoryLabelEn: Paper Gameplay
tags: [paper-api, recipe, crafting, brewing, transmute]
triggerKeywords:
  - "自訂配方"
  - "custom recipe"
  - "ShapedRecipe"
  - "TransmuteRecipe"
  - "釀造配方"
  - "BrewingRecipe"
  - "addRecipes"
updatedAt: "2026-11-23"
githubPath: Skills/paper/paper-custom-recipes/SKILL.md
featured: false
---

# Paper Custom Recipes

## 目的

從插件註冊合成與釀造配方、同步給玩家並乾淨移除。`ShapedRecipe`／`ShapelessRecipe` 做新物品，`RecipeChoice.itemType(...)` 接受多種材料，`TransmuteRecipe` 換物品類型但**保留所有元件**（鐵劍＋鑽石 → 附魔與名稱都在的鑽石劍），釀造在 26.3 是一般的 `BrewingRecipe`。每次變更配方都會重送整份清單給所有玩家，所以整批註冊只同步一次。

---

## 平台需求

- Paper 1.21.11 / 26.3（兩版皆經編譯驗證，開啟淘汰 API 檢查零警告；已在 Paper 26.3 實機驗證）
- 26.3 起 `addRecipe(Recipe, boolean)`、`removeRecipe(key, boolean)` 淘汰，改用 `addRecipes`／`removeRecipes`；`ExactChoice` 建構子淘汰，改用 `RecipeChoice.exactChoice(...)`；不同處以 `// @1.21.11:` 標註
- 釀造：26.3 用 `BrewingRecipe`，1.21.11 用 `PotionMix`（以 `// @only` 分開）
- Java 21（1.21.11）／25（26.3）
- **Spigot**：核心可移植——有形狀／無形狀、`TransmuteRecipe`、`ExactChoice` 在 Spigot-API 26.3 都有；`RecipeChoice.itemType` 是 Paper 限定（Spigot 用 `MaterialChoice`），Spigot 的 `BrewingRecipe` 建構子不同

---

## 產生的代碼

### CustomRecipes.java

```java
Bukkit.addRecipes(fresh);   // 26.3：整批一次同步（1.21.11：addRecipe(r, false) + updateRecipes()）

// 換類型、保留附魔／名稱／耐久
new TransmuteRecipe(key("diamond_sword_upgrade"), Material.DIAMOND_SWORD,
    RecipeChoice.itemType(ItemType.IRON_SWORD), RecipeChoice.itemType(ItemType.DIAMOND));

recipe.setIngredient('H', RecipeChoice.itemType(ItemType.STICK, ItemType.BLAZE_ROD)); // 多選一材料
```

### Brewing.java（26.3）

```java
new BrewingRecipe(key, potion(PotionType.REGENERATION),
    RecipeChoice.exactChoice(potion(PotionType.AWKWARD)),   // 下方三格
    RecipeChoice.itemType(ItemType.GLOW_BERRIES));           // 上方材料格
```

### RecipesPlugin.java（reload 後重新註冊）

```java
@EventHandler
public void onResourcesReloaded(ServerResourcesReloadedEvent event) {
    recipes.registerAll(ingredient);   // /minecraft:reload 會清掉插件配方
    brewing.register();
}
```

---

## 規則

- 配方 key 固定為 `<插件>:<名稱>`，玩家的配方書會記住 key
- 停用時移除所有 key；加入時 `discoverRecipes` 才會出現在配方書
- `/minecraft:reload` 會從資料包重建配方清單並**清掉插件配方**（合成與釀造皆然，Paper 26.3 實測），必須在 `ServerResourcesReloadedEvent` 重新註冊
- 實機驗證（Paper 26.3）：三叉戟可用烈焰棒當握柄、Transmute 升級保留鋒利 III 與自訂名稱、真實釀造台把粗製藥水＋螢光莓釀成再生藥水、reload 後配方恢復

---

## 執行緒安全

- `addRecipe(s)`、`removeRecipe(s)`、`updateRecipes`、`discoverRecipes` 只在主執行緒
- 配方是全伺服器狀態：只在 `onEnable`（與 reload 事件）註冊，不要每位玩家或每個事件註冊
