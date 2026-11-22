---
id: paper-datapack-bundling
title: Paper Datapack Bundling
titleZh: Paper 內附資料包
description: Paper plugin-bundled data packs - discover a data pack inside the jar from a PluginBootstrap with LifecycleEvents.DATAPACK_DISCOVERY, auto-enable it, pick pack.mcmeta format ranges (1.21.11 = 94.1, 26.3 = 121.0) or per-version folders, and verify it with DatapackManager.
descriptionZh: Paper 插件內附資料包：在 PluginBootstrap 以 LifecycleEvents.DATAPACK_DISCOVERY 從 jar 載入資料包並自動啟用，設定 pack.mcmeta 版本範圍（1.21.11 = 94.1、26.3 = 121.0）或依版本分資料夾，再以 DatapackManager 確認。
version: "1.0.0"
status: active
category: paper-data
categoryLabel: Paper 資料
categoryLabelEn: Paper Data
tags: [paper-api, datapack, bootstrap, lifecycle]
triggerKeywords:
  - "插件內附資料包"
  - "bundle datapack"
  - "DATAPACK_DISCOVERY"
  - "DatapackRegistrar"
  - "discoverPack"
  - "pack.mcmeta"
updatedAt: "2026-11-23"
githubPath: Skills/paper/paper-datapack-bundling/SKILL.md
featured: false
---

# Paper Datapack Bundling

## 目的

配方、進度、函式、戰利品表、世界生成用資料包 JSON 寫比 Java 簡單。Paper 插件可以把資料包**放在 jar 裡**，在 bootstrap 註冊，不用請管理員複製到 `world/datapacks/`。資料包 id 是 `<插件名>/<id>`；`autoEnableOnServerStart` 預設是 **false**，不設定就只會被發現、不會啟用；無效或版本不符時 `discoverPack` 回傳 `null`。

---

## 平台需求

- Paper 1.21.11 / 26.3（`DatapackRegistrar`、`DatapackManager`、`DATAPACK_DISCOVERY` 兩版簽名相同；兩版皆經編譯驗證；已在 Paper 26.3 實機驗證）
- 資料包格式：1.21.11 = 94.1、26.2 = 107.1、26.3 = 121.0；兩版的 `pack.mcmeta` 都用 `min_format`／`max_format`，範圍 `[94, 1]`..`121` 可同時支援
- 合成配方、函式與函式標籤兩版寫法相同；26.3 改了許多戰利品表、謂詞、進度觸發器與世界生成欄位，這些請依版本分資料夾
- Java 21（1.21.11）／25（26.3）
- **Spigot**：僅 Paper——`PluginBootstrap` 與 `DATAPACK_DISCOVERY` 是 Paper API；Spigot 只能由管理員安裝到 `world/datapacks/`

---

## 產生的代碼

### pack.mcmeta

```json
{ "pack": { "description": "Example content", "min_format": [94, 1], "max_format": 121 } }
```

### PackBootstrap.java

```java
context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
    URL root = PackBootstrap.class.getResource("/datapack");
    DiscoveredDatapack pack = event.registrar().discoverPack(root.toURI(), "content", config -> config
        .title(Component.text("Example content"))
        .autoEnableOnServerStart(true)              // 預設 false
        .position(false, Datapack.Position.TOP));
    if (pack == null) problems.add("pack rejected");
});
```

### PackPlugin.java（確認已啟用）

```java
Datapack pack = getServer().getDatapackManager().getPack(getPluginMeta().getName() + "/content");
if (pack == null || !pack.isEnabled()) getLogger().warning("bundled data pack is not enabled");
```

---

## 規則

- bootstrap 不拋例外；`discoverPack` 回傳 `null` 時記錄原因，在 `onEnable` 回報
- 用 `processResources` 展開 `${...}` 時只套用到 `paper-plugin.yml`，不要動到 JSON
- 依版本分資料夾時用 `ServerBuildInfo.buildInfo().minecraftVersionId()` 選擇
- 實機驗證（Paper 26.3）：資料包 `CustomContent/content` 已啟用、資料包配方可合成、`#minecraft:load` 函式有執行、`/minecraft:reload` 後配方仍在

---

## 執行緒安全

- 發現處理器在 bootstrap 階段執行：沒有 Bukkit 伺服器，只解析資源並呼叫 registrar
- `DatapackManager` 查詢在主執行緒（`onEnable`、指令）
