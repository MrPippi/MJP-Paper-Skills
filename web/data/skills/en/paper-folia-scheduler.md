# Paper Folia Scheduler

## Purpose

Folia splits nearby chunks into "regions" that each run on their own thread. There is no single main thread, so `BukkitScheduler` cannot be used. Paper ships the same four schedulers Folia uses and runs them all on the main thread, so a plugin written against them runs unchanged on both:

| Work | Scheduler |
|------|------|
| Blocks, chunks, spawning at a location | `getRegionScheduler()` |
| One entity or player (messages, inventory, effects) | `entity.getScheduler()`: follows the entity; `retired` runs instead when it is removed |
| World-independent: broadcasts, console commands, game rules | `getGlobalRegionScheduler()` |
| Database, files, HTTP | `getAsyncScheduler()` |

---

## Platform Requirements

- Paper 1.21.11 / 26.3: the four schedulers, `ScheduledTask`, `isOwnedByCurrentRegion` and `teleportAsync` have identical signatures; compile-verified on both
- Folia only loads plugins whose `plugin.yml` says `folia-supported: true`
- Runtime-verified on Paper 26.3 / 1.21.11 and Folia 26.3 / 1.21.11 with the same jar
- **Spigot**: none of these schedulers exist, and Spigot plugins cannot run on Folia

---

## Generated Code

### Tasks.java

```java
tasks.global(() -> Bukkit.dispatchCommand(console, "say hi"));          // global
tasks.atLater(block.getLocation(), 40, () -> block.setType(Material.STONE)); // a location
tasks.entityTimer(player, 20, 20,
        t -> player.sendActionBar(text),                                  // the player's own thread
        () -> running.remove(player.getUniqueId()));                      // player quit (entity removed)
tasks.async(() -> {
    String data = load();                                                 // IO
    tasks.entity(player, () -> player.sendMessage(data), () -> { });      // back to the player's thread
});
```

`Tasks` clamps delays and periods to at least one tick (the schedulers reject 0) and cancels its repeating tasks in `onDisable`.

### plugin.yml

```yaml
folia-supported: true   # Folia will not load the plugin without it; Paper ignores it
```

---

## Runtime Results

| Item | Paper | Folia |
|------|------|------|
| `BukkitScheduler.runTask` | works | throws `UnsupportedOperationException` |
| `Entity#teleport` (even one block) | works | throws `UnsupportedOperationException: Must use teleportAsync while in region threading` |
| Delay 0 | `IllegalArgumentException` | `IllegalArgumentException` |
| Entity removed | pending tasks run `retired` instead; scheduling again returns `null` | same |
| `teleportAsync` 3000 blocks away | afterwards the entity scheduler runs on the new location's thread | same |

---

## Thread Safety

- Pick the scheduler by what the code touches; after async IO, go back to the entity's or location's scheduler before touching game state
- On Folia events fire on the thread that owns the entity or block, so events of different players can run at the same time: shared maps and counters must be `ConcurrentHashMap` or atomics
- Entities only tick in loaded chunks; with no player nearby, keep the chunk loaded with `getChunkAtAsync` plus `addPluginChunkTicket` before scheduling for an entity there (the runtime check does this)
