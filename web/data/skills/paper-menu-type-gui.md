---
id: paper-menu-type-gui
title: Paper MenuType GUI
titleZh: Paper 原版介面
description: Paper MenuType vanilla menus - open portable crafting tables, anvils, stonecutters, smithing tables, grindstones, looms, cartography tables and custom merchant screens without a block, with anvil cost limits and the temporary block-entity caveat.
descriptionZh: Paper MenuType 原版介面：不需要方塊就開啟可攜式工作台、鐵砧、切石機、鍛造台、砂輪、織布機、製圖台與自訂交易介面，並處理鐵砧花費上限與方塊實體類介面的暫存陷阱。
version: "1.0.0"
status: active
category: paper-ui
categoryLabel: Paper 介面
categoryLabelEn: Paper UI
tags: [paper-api, menu-type, gui, anvil, merchant]
triggerKeywords:
  - "MenuType"
  - "可攜式工作台"
  - "portable crafting table"
  - "portable anvil"
  - "open vanilla menu"
  - "custom merchant"
  - "AnvilView"
updatedAt: "2026-11-23"
githubPath: Skills/paper/paper-menu-type-gui/SKILL.md
featured: false
---

# Paper MenuType GUI

## 目的

`MenuType` 不需要方塊就能開啟**真正的原版介面**：工作台、鐵砧、切石機、鍛造台、砂輪、織布機、製圖台、交易。和箱子 GUI（`paper-chest-gui`）不同，客戶端照常運作（配方書、鐵砧改名、切石機清單）；和 `nms-custom-menu` 不同，不需要 NMS。

---

## 平台需求

- Paper 1.21.11 / 26.3（`MenuType`、view builder、`AnvilView` 兩版簽名相同；兩版皆經編譯驗證）
- 開啟介面需要玩家客戶端，未做實機測試；builder 行為取自 Paper 26.3 原始碼
- Java 21（1.21.11）／25（26.3）
- **Spigot**：核心可移植——`MenuType.Typed#builder`、`AnvilView#setMaximumRepairCost`、`Bukkit.createMerchant` 在 Spigot-API 26.3 都有；Adventure 標題需改成字串

---

## 產生的代碼

### Workstations.java（可攜式方塊介面）

```java
// 不呼叫 location()：介面跟著玩家，不需要方塊，關閉時原版會歸還放入的物品
player.openInventory(MenuType.CRAFTING.builder().title(Component.text("Portable Crafting")).build(player));
```

### PortableAnvil.java（提高花費上限）

```java
AnvilView view = MenuType.ANVIL.builder().title(Component.text("Portable Anvil")).build(player);
view.setMaximumRepairCost(100);   // 原版上限 40
// PrepareAnvilEvent 會重算，必須在事件中再套用一次
```

### TradeMenu.java（自訂交易）

```java
Merchant merchant = Bukkit.createMerchant();
merchant.setRecipes(List.of(offer(new ItemStack(Material.EMERALD), new ItemStack(Material.BREAD, 4))));
player.openInventory(MenuType.MERCHANT.builder().merchant(merchant).title(Component.text("Wandering Baker")).build(player));
```

---

## 規則

- 可攜式介面不要呼叫 `location(...)`；`location` 會綁定到該位置的真實方塊（真的鐵砧會損壞）
- `checkReachable` 預設 `false`；設為 `true` 時離開方塊太遠會自動關閉
- 方塊實體類介面（熔爐、釀造台、漏斗、界伏盒、9x3／9x6 箱子、合成器）沒有 `location` 時是**不在世界裡的暫存方塊實體**：不會運作（熔爐不燒），內容也不會存檔——存放用途請用 `Bukkit.createInventory`
- 用玩家 UUID 判斷是不是自己開的介面，不要比對標題文字

---

## 執行緒安全

- `build(player)` 與 `openInventory` 只在主執行緒；從指令或排程任務開啟，不要從非同步回呼開啟
