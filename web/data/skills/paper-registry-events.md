---
id: paper-registry-events
title: Paper Registry Events
titleZh: Paper 註冊表事件
description: Paper registry events - add custom enchantments and damage types from a PluginBootstrap, change vanilla entries with entryAdd, extend tags with LifecycleEvents.TAGS, and look everything up at runtime with RegistryAccess.
descriptionZh: Paper 註冊表事件：在 PluginBootstrap 新增自訂附魔與傷害類型、用 entryAdd 修改原版項目、用 LifecycleEvents.TAGS 加入標籤，並在執行期以 RegistryAccess 取回。
version: "1.0.0"
status: active
category: paper-gameplay
categoryLabel: Paper 玩法
categoryLabelEn: Paper Gameplay
tags: [paper-api, registry, enchantment, damage-type, bootstrap, tags]
triggerKeywords:
  - "自訂附魔"
  - "custom enchantment"
  - "自訂傷害類型"
  - "custom damage type"
  - "RegistryEvents"
  - "registry modification"
  - "EnchantmentRegistryEntry"
  - "LifecycleEvents.TAGS"
updatedAt: "2026-11-23"
githubPath: Skills/paper/paper-registry-events/SKILL.md
featured: false
---

# Paper Registry Events

## 目的

附魔、傷害類型、生物變種、唱片、對話框等內容都存在**註冊表**裡，伺服器載入完成後就凍結。Paper 允許插件在凍結前從 `PluginBootstrap` 新增或修改這些內容，不需要資料包：`compose()` 新增項目、`entryAdd()` + `filter` 修改既有項目、`LifecycleEvents.TAGS.postFlatten` 加入標籤，執行期再用 `RegistryAccess` 取回。範本新增「吸血」附魔與「流血」傷害類型、把鋒利上限提高到 6，並用監聽器實作附魔效果（API 只定義附魔是什麼，不定義它做什麼）。

---

## 平台需求

- Paper 1.21.11 / 26.3（兩版皆經編譯驗證，範本兩版相同；已在 Paper 26.3 實機驗證）
- 純 Paper API，不需要 Paperweight；必須使用 `paper-plugin.yml` 並宣告 `bootstrapper`
- `TRIM_MATERIAL`、`TRIM_PATTERN`、`DECORATED_POT_PATTERN`、`SULFUR_CUBE_ARCHETYPE`、`WORLD_CLOCK`、`TIMELINE` 的註冊表事件只有 26.3 有
- Java 21（1.21.11）／25（26.3）
- **Spigot**：僅 Paper——`RegistryEvents`、`PluginBootstrap`、`LifecycleEvents` 都是 Paper API；Spigot 只能用資料包新增內容

---

## 產生的代碼

### ContentBootstrap.java（新增附魔與傷害類型）

```java
lifecycle.registerEventHandler(RegistryEvents.ENCHANTMENT.compose().newHandler(event ->
    event.registry().register(ContentKeys.LIFESTEAL, b -> b
        .description(Component.text("Lifesteal"))
        .supportedItems(event.getOrCreateTag(ItemTypeTagKeys.ENCHANTABLE_MELEE_WEAPON))
        .primaryItems(event.getOrCreateTag(ItemTypeTagKeys.ENCHANTABLE_SHARP_WEAPON))
        .weight(2).maxLevel(3)
        .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(15, 9))
        .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(65, 9))
        .anvilCost(4)
        .activeSlots(EquipmentSlotGroup.MAINHAND)
        .exclusiveWith(RegistrySet.keySet(RegistryKey.ENCHANTMENT, EnchantmentKeys.SMITE)))));
```

### 修改原版項目與標籤

```java
// 只改鋒利：沒有 filter 會套用到每一個附魔
RegistryEvents.ENCHANTMENT.entryAdd().newHandler(e -> e.builder().maxLevel(6)).filter(EnchantmentKeys.SHARPNESS);
// 標籤處理器在每次 /minecraft:reload 都會再執行，必須可重複執行
LifecycleEvents.TAGS.postFlatten(RegistryKey.ENCHANTMENT), event ->
    event.registrar().addToTag(EnchantmentTagKeys.IN_ENCHANTING_TABLE, Set.of(ContentKeys.LIFESTEAL));
LifecycleEvents.TAGS.postFlatten(RegistryKey.DAMAGE_TYPE), event ->
    event.registrar().addToTag(DamageTypeTagKeys.BYPASSES_ARMOR, Set.of(ContentKeys.BLEED));
```

### 執行期取回與自訂傷害

```java
DamageType bleed = RegistryAccess.registryAccess().getRegistry(RegistryKey.DAMAGE_TYPE).get(ContentKeys.BLEED);
target.damage(level, DamageSource.builder(bleed).withCausingEntity(attacker).withDirectEntity(attacker).build());
```

---

## 規則

- bootstrap 絕不拋例外：每個處理器都 catch、記錄，`onEnable` 再回報；`bootstrap()` 階段沒有 Bukkit 伺服器
- 所有 key 集中在 `ContentKeys`，發佈後不可改名（物品與世界會存 key）
- 附魔效果用監聽器實作；監聽器要略過自己的後續傷害，避免無限循環
- 自訂 `messageId` 的死亡訊息需要資源包提供 `death.attack.<messageId>` 翻譯
- 實機驗證（Paper 26.3）：附魔與傷害類型註冊成功、標籤在 `/minecraft:reload` 後仍在；流血對全套鑽石裝僵屍造成完整 6.0 傷害，對照組 mob_attack 只有 1.2

---

## 執行緒安全

- bootstrap 處理器在伺服器啟動階段執行，不可碰 Bukkit、世界、玩家、排程器
- 執行期查詢與 `damage()` 只在主執行緒；延遲任務只攜帶 UUID 並重新取得實體
