---
id: paper-data-components
title: Paper Data Components
titleZh: Paper 資料元件
description: Paper data component API - build and read item components without NMS (food, consumable effects, weapon stats, glider, equippable, tooltip, cooldown), set vs unset vs reset against the prototype, PDC item ids and matchesWithoutData comparisons.
descriptionZh: Paper Data Component API：不用 NMS 讀寫物品元件（食物、可食用效果、武器屬性、滑翔、裝備、提示框、冷卻），分清 set／unset／reset 與原型預設值，用 PDC 標記自訂物品並以 matchesWithoutData 比對。
version: "1.0.0"
status: active
category: paper-gameplay
categoryLabel: Paper 玩法
categoryLabelEn: Paper Gameplay
tags: [paper-api, data-component, item, food, weapon, pdc]
triggerKeywords:
  - "Data Component API"
  - "data component"
  - "物品組件"
  - "DataComponentTypes"
  - "setData"
  - "自訂食物"
  - "自訂武器"
  - "matchesWithoutData"
updatedAt: "2026-11-23"
githubPath: Skills/paper/paper-data-components/SKILL.md
featured: false
---

# Paper Data Components

## 目的

1.20.5 起物品是「材質＋資料元件」：`food`、`max_stack_size`、`tooltip_display` 等。Paper 透過 `DataComponentTypes` 與 `ItemStack#setData` / `getData` 直接操作，自訂物品不再需要 `ItemMeta` 或 NMS。每個元件都有來自物品類型的**原型**值，三種寫法語意不同：`setData` 覆寫、`unsetData` 移除（連原型也移除，例如不能吃的蘋果）、`resetData` 回到原型。

---

## 平台需求

- Paper 1.21.11 / 26.3（兩版皆經編譯驗證，範本兩版相同；已在 Paper 26.3 與 1.21.11 實機驗證）
- 1.21.11 的 Data Component API 仍標記 `@ApiStatus.Experimental`；Paper 26.3 已移除
- 26.3 新增 `ATTACK_ANIMATION`、`INTERACT_ANIMATION`、`MOB_VISIBILITY`、`VILLAGER_FOOD` 等，移除 `SWING_ANIMATION`、`MAP_COLOR`；`brewing_fuel`、`cooking_fuel`、`compostable`、`block_transformer` Paper 尚未提供（改用 `nms-data-component`）
- Java 21（1.21.11）／25（26.3）
- **Spigot**：僅 Paper——`io.papermc.paper.datacomponent` 不存在於 Spigot-API 26.3（Spigot 的 `org.bukkit.inventory.meta.components` 是另一套以 `ItemMeta` 為基礎的 API）

---

## 產生的代碼

### CustomItems.java（只用資料元件）

```java
ItemStack item = ItemStack.of(Material.GLOW_BERRIES);
item.setData(DataComponentTypes.ITEM_NAME, Component.text("Healing Berry"));
item.setData(DataComponentTypes.FOOD, FoodProperties.food().nutrition(2).saturation(1.0f).canAlwaysEat(true));
item.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
    .consumeSeconds(0.8f).animation(ItemUseAnimation.EAT)
    .addEffect(ConsumeEffect.applyStatusEffects(List.of(new PotionEffect(PotionEffectType.REGENERATION, 100, 1)), 1.0f)));
item.setData(DataComponentTypes.MAX_STACK_SIZE, 16);
item.setData(DataComponentTypes.GLIDER);   // 無值元件：有或沒有
```

### ItemIds.java（用 PDC 識別）

```java
item.editPersistentDataContainer(pdc -> pdc.set(key, PersistentDataType.STRING, id));
String id = item.getPersistentDataContainer().get(key, PersistentDataType.STRING);
```

### ItemChecks.java（比對與修改）

```java
a.matchesWithoutData(b, Set.of(DataComponentTypes.DAMAGE, DataComponentTypes.REPAIR_COST), true); // true = 忽略數量
target.copyDataFrom(skin, COSMETIC::contains);   // 只複製外觀元件
item.resetData(DataComponentTypes.DAMAGE);       // 回到原型 = 完全修復
item.unsetData(DataComponentTypes.FOOD);         // 連原型的 FOOD 也移除
```

---

## 規則

- 自訂物品一律用 PDC id 識別，不用名稱、說明或模型
- 食物要同時設定 `FOOD` 與 `CONSUMABLE`
- 物品本身的名稱用 `ITEM_NAME`（不是斜體）；`CUSTOM_NAME` 是鐵砧改名用的
- 同時用 `ItemMeta` 與元件時，先 `editMeta` 再 `setData`，不要留著舊的 `ItemMeta` 寫回去
- 實機驗證（Paper 26.3、1.21.11）：27 項檢查全過，包含 set／unset／reset 語意、忽略耐久比對、序列化後 PDC id 仍在

---

## 執行緒安全

- `ItemStack` 可變且非執行緒安全：在主執行緒建立與修改，工廠方法每次回傳新物品
- 要交給非同步程式前先在主執行緒 `clone()`
