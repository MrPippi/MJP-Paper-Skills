---
id: paper-display-entities
title: Paper Display Entities
titleZh: Paper Display 實體
description: Paper display entities - TextDisplay holograms, ItemDisplay / BlockDisplay showcases and interpolated animations without armor stands or packets; non-persistent entities rebuilt on chunk load, PDC cleanup of duplicates and per-player holograms. Runtime-verified on Paper and Folia.
descriptionZh: Paper Display 實體：TextDisplay 全息字、ItemDisplay／BlockDisplay 展示與插值動畫，不用盔甲座或封包；非持久化實體在區塊載入時重建、以 PDC 清掉重複、只給單一玩家看。已在 Paper 與 Folia 實機驗證。
version: "1.0.0"
status: active
category: paper-display
categoryLabel: Paper 顯示
categoryLabelEn: Paper Display
tags: [paper-api, display-entity, hologram, text-display]
triggerKeywords:
  - "全息字"
  - "hologram"
  - "TextDisplay"
  - "ItemDisplay"
  - "BlockDisplay"
  - "display entity"
  - "插值動畫"
  - "浮空文字"
updatedAt: "2026-11-28"
githubPath: Skills/paper/paper-display-entities/SKILL.md
featured: false
---

# Paper Display Entities

## 目的

`TextDisplay`、`ItemDisplay`、`BlockDisplay` 取代盔甲座與封包做全息字、浮空物品與動畫裝飾：沒有碰撞箱、AI、重力，可任意縮放旋轉，客戶端會在兩個變換之間插值，所以平滑動畫每一步只要更新一次。

真正難的是「永遠只有一份」：

| 問題 | 範本的做法 |
|------|------|
| 存進世界的全息字重啟或當機後重複 | `setPersistent(false)`：實體不存檔，由插件保存定義並重新生成 |
| 區塊卸載後實體不一定還在 | `EntitiesUnloadEvent` 移除、`ChunkLoadEvent` 重建 |
| 舊版留下的持久化副本 | 每個實體加 PDC 標記；`EntitiesLoadEvent` 移除不是現役那份的 |
| 改文字 | 直接改現役實體（`text(...)`），不移除重生 |
| 只給一位玩家看 | 在生成的 consumer 裡 `setVisibleByDefault(false)`，再 `player.showEntity(plugin, display)` |

---

## 平台需求

- Paper 1.21.11 / 26.3：`Display`、`TextDisplay`、`ItemDisplay`、`BlockDisplay`、`Transformation`、`EntitiesLoadEvent`、`setVisibleByDefault`／`showEntity` 兩版簽名相同；兩版皆經編譯驗證
- 排程用區域與實體排程器，範本也能在 Folia 上執行；已在 Paper 與 Folia 的 26.3、1.21.11 實機驗證
- **Spigot**：1.19.4 起有 Display 實體；`text(Component)` 改 `setText(String)`、排程改 `BukkitScheduler`

---

## 產生的代碼

### Holograms.java

```java
TextDisplay display = world.spawn(at, TextDisplay.class, d -> {   // 加入世界前就設定好
    d.setPersistent(false);
    d.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, spec.id());
    d.text(spec.text());
    d.setBillboard(Display.Billboard.CENTER);
});
```

### ItemSpinner.java（插值動畫）

```java
display.setInterpolationDelay(0);              // 現在開始
display.setInterpolationDuration(stepTicks);    // 用整個步長移動過去
display.setTransformation(new Transformation(translation, new AxisAngle4f(angle, 0, 1, 0), scale, new AxisAngle4f()));
```

---

## 實機驗證結果

| 項目 | 結果 |
|------|------|
| 區塊卸載後立刻重新載入 | Paper 丟棄了實體；**Folia 把同一個實體帶回來**。加上 `EntitiesUnloadEvent` 移除後，兩者都只剩一個新的 |
| 留下持久化副本後重啟 | 副本被存檔、載入時移除，只剩一個非持久化的現役實體 |
| `setTeleportDuration(100)` | `IllegalArgumentException`：只接受 0..59 |
| `setText` | 同一個實體（UUID 不變）直接更新 |

---

## 執行緒安全

- 生成會改世界：在位置的區域排程器執行（Paper 上就是主執行緒）；修改現役實體走它的實體排程器
- Folia 上不同區域的區塊事件可能同時執行，`Holograms` 的 Map 用 `ConcurrentHashMap`
