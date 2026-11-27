---
id: paper-folia-scheduler
title: Paper Folia Scheduler
titleZh: Paper／Folia 排程
description: Paper and Folia scheduling with one code path - region, entity (with a retired callback), global and async schedulers instead of BukkitScheduler, folia-supported, teleportAsync and delays of at least one tick. Runtime-verified on Paper and Folia.
descriptionZh: Paper 與 Folia 共用一份排程程式：以區域、實體（含 retired 回呼）、全域、非同步排程器取代 BukkitScheduler，加上 folia-supported、teleportAsync、延遲至少 1 tick。已在 Paper 與 Folia 實機驗證。
version: "1.0.0"
status: active
category: paper-threading
categoryLabel: Paper 執行緒
categoryLabelEn: Paper Threading
tags: [paper-api, folia, scheduler, threading]
triggerKeywords:
  - "Folia"
  - "folia-supported"
  - "RegionScheduler"
  - "EntityScheduler"
  - "GlobalRegionScheduler"
  - "AsyncScheduler"
  - "區域執行緒"
  - "teleportAsync"
updatedAt: "2026-11-27"
githubPath: Skills/paper/paper-folia-scheduler/SKILL.md
featured: false
---

# Paper Folia Scheduler

## 目的

Folia 把相鄰的區塊分成「區域」，每個區域在自己的執行緒上執行，沒有單一主執行緒，所以 `BukkitScheduler` 無法使用。Paper 也內建 Folia 的四種排程器，而且在 Paper 上全部跑在主執行緒，所以改用它們後，同一份程式可以在 Paper 與 Folia 上執行：

| 工作 | 排程器 |
|------|------|
| 某個位置的方塊、區塊、生成 | `getRegionScheduler()` |
| 某個實體或玩家（訊息、物品欄、效果） | `entity.getScheduler()`：跟著實體移動，實體被移除時改呼叫 `retired` |
| 與世界無關：廣播、主控台指令、遊戲規則 | `getGlobalRegionScheduler()` |
| 資料庫、檔案、HTTP | `getAsyncScheduler()` |

---

## 平台需求

- Paper 1.21.11 / 26.3：四種排程器、`ScheduledTask`、`isOwnedByCurrentRegion`、`teleportAsync` 兩版簽名相同；兩版皆經編譯驗證
- Folia 只載入 `plugin.yml` 寫了 `folia-supported: true` 的插件
- 已在 Paper 26.3／1.21.11 與 Folia 26.3／1.21.11 實機驗證，同一個 jar
- **Spigot**：沒有這些排程器，也不能在 Folia 上執行

---

## 產生的代碼

### Tasks.java

```java
tasks.global(() -> Bukkit.dispatchCommand(console, "say hi"));          // 全域
tasks.atLater(block.getLocation(), 40, () -> block.setType(Material.STONE)); // 位置
tasks.entityTimer(player, 20, 20,
        t -> player.sendActionBar(text),                                  // 玩家自己的執行緒
        () -> running.remove(player.getUniqueId()));                      // 玩家離線（實體被移除）
tasks.async(() -> {
    String data = load();                                                 // IO
    tasks.entity(player, () -> player.sendMessage(data), () -> { });      // 回到玩家的執行緒
});
```

`Tasks` 會把延遲與間隔限制在至少 1 tick（排程器拒絕 0），並在 `onDisable` 取消重複任務。

### plugin.yml

```yaml
folia-supported: true   # 沒有它 Folia 不會載入插件；Paper 忽略此設定
```

---

## 實機驗證結果

| 項目 | Paper | Folia |
|------|------|------|
| `BukkitScheduler.runTask` | 可用 | 丟出 `UnsupportedOperationException` |
| `Entity#teleport`（即使只移動 1 格） | 可用 | 丟出 `UnsupportedOperationException: Must use teleportAsync while in region threading` |
| 延遲 0 | `IllegalArgumentException` | `IllegalArgumentException` |
| 實體被移除後 | 待執行的任務改呼叫 `retired`；再排程回傳 `null` | 相同 |
| `teleportAsync` 到 3000 格外 | 完成後實體排程器在新位置的執行緒執行 | 相同 |

---

## 執行緒安全

- 依「程式碰到什麼」選排程器；非同步做完 IO 後，先回到實體或位置的排程器再碰遊戲狀態
- Folia 上事件在擁有該實體或方塊的執行緒觸發，不同玩家的事件可能同時執行：共用的 Map 與計數器要用 `ConcurrentHashMap`、atomic
- 實體只在已載入的區塊中 tick；附近沒有玩家時，先用 `getChunkAtAsync` 加 `addPluginChunkTicket` 讓區塊保持載入，再排程給那裡的實體（實機測試也這麼做）
