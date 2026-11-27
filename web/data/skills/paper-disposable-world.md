---
id: paper-disposable-world
title: Paper Disposable World
titleZh: 拋棄式世界與競技場重置
description: Throw-away Paper worlds and resettable arenas - void generator, serial naming with a stale-data guard, async template copy with atomic rename, safe unload and delete, the 26.x dimensions storage layout, a pre-generated pool, and a budgeted in-place reset with residual-entity sweep.
descriptionZh: 拋棄式世界與可重置競技場：虛空生成器、流水號命名與殘留資料守衛、非同步複製模板（暫存後原子改名）、安全卸載與刪除、26.x dimensions 存檔路徑、預產生池，以及預算式就地還原與殘留實體清掃。
version: "1.1.0"
status: active
category: paper-world
categoryLabel: Paper 世界
categoryLabelEn: Paper World
tags: [paper-api, world, arena, worldcreator, chunk-generator, reset, gamerule]
triggerKeywords:
  - "拋棄式世界"
  - "disposable world"
  - "競技場重置"
  - "arena reset"
  - "WorldCreator"
  - "unloadWorld"
  - "void generator"
  - "殘留實體"
updatedAt: "2026-11-22"
githubPath: Skills/paper/paper-disposable-world/SKILL.md
featured: false
---

# Paper Disposable World

## 目的

小遊戲與決鬥需要用完就丟的世界，或每場結束要還原的競技場。提供「換世界」與「就地還原」兩條路線；世界操作一律在主執行緒，只有檔案 IO（複製、刪除）在非同步。

---

## 平台需求

- Paper 1.21.11 / 26.3（兩版皆經編譯驗證）
- 純 Paper API，不需要 Paperweight
- GameRule 舊常數已棄用，範本改以 `Registry.GAME_RULE` 的 snake_case key 查詢（兩版相同）
- **Spigot**：僅 Paper——使用 `Entity#teleportAsync` 與 Paper 的世界存檔結構

---

## 產生的代碼

### VoidChunkGenerator.java

```java
public final class VoidChunkGenerator extends ChunkGenerator {
    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    // ... Bedrock / Caves / Decorations / Mobs / Structures 同樣回 false
    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, 64.0, 0.5);
    }
}
```

### 建立與守衛（DisposableWorlds）

```java
// 資料夾已存在就拒絕：WorldCreator 會把殘缺資料夾當新世界默默生成地形
World world = WorldCreator.name(name)
        .generator(generator)
        .generateStructures(false)
        .createWorld();
rules.apply(world);   // 建立後立刻套用遊戲規則
```

### 複製模板與刪除（WorldFolders，非同步）

```java
// 26.x 世界資料在 <主世界>/dimensions/minecraft/<名稱>/（1.21.11 在 <世界容器>/<名稱>/）；路徑一律用 WorldFolders.dataRoot / getWorldPath()
// 複製：略過世界 UUID 檔（26.x data/paper/metadata.dat、1.21.11 uid.dat）與 session.lock；先寫到 <名稱>.copying 再原子改名
// 刪除：先改名為 <名稱>.deleting 再刪；啟動時 purgeStaleAsync 清掉殘留與 .copying / .deleting（已在 Paper 26.3 與 1.21.11 實機驗證）
WorldFolders.copyTemplate(template, target);
WorldFolders.deleteWorld(folder);
```

### 就地還原（ChangeRecorder + ArenaResetService）

```java
// 記錄每格第一次被改之前的 BlockData；還原時每 tick 受方塊數與時間預算限制
cells.putIfAbsent(ChangeRecorder.key(block), block.getBlockData());   // 自行打包 x/y/z；Block#getBlockKey 已淘汰
block.setBlockData(original, false);
sweeper.sweepAll(world);   // 掉落物、箭、TNT 礦車、水晶等殘留實體
```

---

## 執行緒安全

- 世界建立／卸載、方塊與實體操作只在主執行緒
- 只有 `WorldFolders` 的複製與刪除在非同步；lambda 只攜帶 `Path` / `String`
- 回傳的 future 一律在主執行緒完成；`onDisable` 用 `closeAllSync()` 同步收尾
- 大量方塊（數十萬格）的 NMS 快速路徑見 `nms-chunk-access`
