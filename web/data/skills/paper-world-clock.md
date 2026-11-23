---
id: paper-world-clock
title: Paper World Clock
titleZh: Paper 世界時鐘
description: Paper 26.3 world clock API - pause or speed up time per world (day length), frozen-time lobbies, blocking /time and night skips with TimeSkipEvent, and handling dimensions without a clock. 26.3 only.
descriptionZh: Paper 26.3 世界時鐘 API：每個世界獨立暫停或調整時間流速（白天長度）、固定時間的大廳、以 TimeSkipEvent 攔截 /time 與睡覺跳夜，並處理沒有時鐘的維度。僅 26.3。
version: "1.0.0"
status: active
category: paper-world
categoryLabel: Paper 世界
categoryLabelEn: Paper World
tags: [paper-api, world-clock, time, 26.3]
triggerKeywords:
  - "世界時鐘"
  - "world clock"
  - "WorldClock"
  - "時間流速"
  - "day length"
  - "freeze time"
  - "ClockTimeSkipEvent"
updatedAt: "2026-11-23"
githubPath: Skills/paper/paper-world-clock/SKILL.md
mcVersions: ["26.3"]
featured: false
---

# Paper World Clock

## 目的

Minecraft 26.1 把單一的「一天時間」改成**世界時鐘**（`minecraft:overworld`、`minecraft:the_end`），Paper 26.3 開放 API：每個世界可以暫停、改變流速、設定時間。大廳永遠中午、一天 40 分鐘、競技場禁止 `/time`、只有生存世界能睡覺跳夜，都不再需要和日夜循環對抗的重複任務。

---

## 平台需求

- **僅 Paper 26.3**：`WorldClock`、`WorldClocks`、`World` 的時鐘方法與 `ClockTimeSkipEvent` 在 1.21.11 不存在；所有範本標記 `// @only 26.3`
- 1.21.11 的替代做法：`advance_time` 遊戲規則＋`World#setTime`＋重複任務
- Java 25
- **Spigot**：僅 Paper——Spigot-API 26.3 沒有時鐘類別

---

## 產生的代碼

### WorldTime.java

```java
WorldClock clock = world.getDefaultClock();   // 地獄回傳 null
world.setFullTime(clock, 6000);                // 先設時間
world.setClockPaused(clock, true);             // 再暫停
world.setClockRate(clock, 0.5f);               // 一天 40 分鐘（20 分鐘 / 40 分鐘）
```

### TimeLock.java

```java
@EventHandler(ignoreCancelled = true)
public void onTimeSkip(TimeSkipEvent event) {        // 26.3：TimeSkipEvent 繼承 ClockTimeSkipEvent，多了 getWorld()
    if (locked.contains(event.getWorld().getName())
            && event.getSkipReason() != ClockTimeSkipEvent.SkipReason.NIGHT_SKIP) {
        event.setCancelled(true);                     // /time、setTime 都會被擋下
    }
}
```

---

## 規則

- 一律用 `world.getDefaultClock()` 並處理 `null`（地獄沒有時鐘）
- 同類型世界共用同一個 `WorldClock` key，但暫停、流速、時間**每個世界各自獨立**
- 在 `onEnable` 與 `WorldLoadEvent` 都套用設定
- 實機驗證（Paper 26.3 build 258）：暫停世界 40 tick 前進 0、流速 4 前進 160、暫停中即使設流速也不前進；`/time add` 被取消後時間不變；重啟後流速 0.5 與暫停在 6000 的競技場都保留

---

## 執行緒安全

- 時鐘方法與事件都只在主執行緒
- 狀態會隨世界存檔；每次載入重新套用是冪等的
