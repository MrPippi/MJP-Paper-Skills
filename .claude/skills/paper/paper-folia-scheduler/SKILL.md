---
name: paper-folia-scheduler
description: "Paper／Folia 共用排程：用 RegionScheduler（位置）、EntityScheduler（實體，含 retired 回呼）、GlobalRegionScheduler（全域）、AsyncScheduler（IO）取代 BukkitScheduler，同一份程式在 Paper 與 Folia 都能跑；folia-supported、teleportAsync、延遲至少 1 tick、事件在擁有該區域的執行緒 / Paper and Folia scheduling with one code path: RegionScheduler for locations, EntityScheduler (with a retired callback) for entities, GlobalRegionScheduler for global work and AsyncScheduler for IO instead of BukkitScheduler; folia-supported, teleportAsync, delays of at least one tick, events on the owning region's thread"
---

# Paper Folia Scheduler

## Skill Name

`paper-folia-scheduler`

## Purpose

Folia runs each group of nearby chunks ("region") on its own thread. There is no single main thread, so `BukkitScheduler` cannot say where a task should run. Paper ships the same four schedulers Folia uses, and on Paper they all run on the main thread, so a plugin written against them runs unchanged on both:

| Work | Scheduler | On Paper | On Folia |
|------|------|------|------|
| Blocks, chunks, spawning at a location | `getRegionScheduler()` (location) | main thread | the thread of the region that owns the location |
| One entity or player (messages, inventory, potion effects) | `entity.getScheduler()` | main thread | the thread of the region the entity is in **now**; follows it when it moves |
| World-independent: broadcasts, console commands, game rules, weather and time | `getGlobalRegionScheduler()` | main thread | the global region thread |
| Database, files, HTTP | `getAsyncScheduler()` | pool thread | pool thread |

Observed at runtime (the same jar on Paper 26.3 build 258, Paper 1.21.11, Folia 26.3 build 28 and Folia 1.21.11):

| Behavior | Paper | Folia |
|------|------|------|
| `BukkitScheduler.runTask` | works | throws `UnsupportedOperationException` |
| `Entity#teleport`, even one block | works | throws `UnsupportedOperationException: Must use teleportAsync while in region threading` |
| Delay or period 0 on the raw schedulers | `IllegalArgumentException` | `IllegalArgumentException` |
| Region / entity task thread | main thread; `isOwnedByCurrentRegion` true | the owning region's thread; `isOwnedByCurrentRegion` true |
| Async task | not the main thread, `isOwnedByCurrentRegion(entity)` false | same |
| Entity removed with a task pending | `retired` runs instead of the task; scheduling again returns `null` | same |
| `teleportAsync` 3000 blocks away | completes `true`; the entity scheduler then runs where the entity is | same |
| Repeating task cancelling itself from its body | stops after that run | same |

## Paper Version Requirements

- Paper 1.21.11 / 26.3; the four schedulers, `ScheduledTask`, `isOwnedByCurrentRegion` and `teleportAsync` have identical signatures on both (compared with `javap`); compile-verified on both
- Folia 1.21.11 / 26.3 builds exist; Folia only loads plugins whose `plugin.yml` says `folia-supported: true`
- **Spigot**: none of these schedulers exist; Spigot plugins stay on `BukkitScheduler` and cannot run on Folia

## Triggers

- 「Folia」「folia-supported」「區域執行緒」「regionized」「RegionScheduler」「EntityScheduler」
- 「GlobalRegionScheduler」「AsyncScheduler」「ScheduledTask」「isOwnedByCurrentRegion」「teleportAsync」
- 「Folia 相容」「BukkitScheduler 不能用」「UnsupportedOperationException runTask」

## Inputs

| Parameter | Example | Description |
|------|------|------|
| `task_kinds` | player HUD, block regeneration, saving | Which work runs where (table above) |
| `period` | 20 ticks | Repeating interval |
| `folia_required` | no | Whether the plugin must also run on Folia (always write it as if yes) |

## Outputs

- `Platform.java` — detects Folia (for logging and the few behaviors that differ)
- `Tasks.java` — one plugin-bound facade over the four schedulers; clamps delays to 1 tick and cancels its repeating tasks on disable
- `PlayerHud.java` — per-player repeating task on the entity scheduler, cleaned up through the retired callback
- `BlockRegen.java` — restores broken blocks later through the region scheduler
- `ProfileLoader.java` — async load, then back to the player's own thread
- `SchedulingPlugin.java` — wiring; `plugin.yml` with `folia-supported: true`

## Build Setup

See [`references/paper-api-platform.md`](references/paper-api-platform.md). Only `paper-api` (`compileOnly`). `plugin.yml`:

```yaml
name: Scheduling
main: com.example.scheduling.SchedulingPlugin
api-version: '26.3'   # 1.21.11 server: '1.21.11'
folia-supported: true # without it Folia refuses to load the plugin; Paper ignores it
```

## Code Template

### `Platform.java`

```java
package com.example.scheduling;

/** Folia detection. Prefer the shared schedulers over branching on this; use it for logging and true differences. */
public final class Platform {

    private static final boolean FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    private Platform() {
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
```

### `Tasks.java`

```java
package com.example.scheduling;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The four Paper/Folia schedulers bound to one plugin. Same code on Paper (everything on the main thread) and Folia
 * (region, entity and global threads). Delays and periods are clamped to at least 1 tick: the schedulers reject 0.
 */
public final class Tasks {

    private final Plugin plugin;
    private final Server server;
    private final Set<ScheduledTask> repeating = ConcurrentHashMap.newKeySet();

    public Tasks(Plugin plugin) {
        this.plugin = plugin;
        this.server = plugin.getServer();
    }

    // ---- global: broadcasts, console commands, game rules, weather/time ----

    public ScheduledTask global(Runnable work) {
        return server.getGlobalRegionScheduler().run(plugin, task -> work.run());
    }

    public ScheduledTask globalLater(long ticks, Runnable work) {
        return server.getGlobalRegionScheduler().runDelayed(plugin, task -> work.run(), atLeastOneTick(ticks));
    }

    public ScheduledTask globalTimer(long delayTicks, long periodTicks, Consumer<ScheduledTask> body) {
        return track(server.getGlobalRegionScheduler().runAtFixedRate(plugin, body,
                atLeastOneTick(delayTicks), atLeastOneTick(periodTicks)));
    }

    // ---- region: blocks and chunks at a location ----

    public ScheduledTask at(Location location, Runnable work) {
        return server.getRegionScheduler().run(plugin, location, task -> work.run());
    }

    public ScheduledTask atLater(Location location, long ticks, Runnable work) {
        return server.getRegionScheduler().runDelayed(plugin, location, task -> work.run(), atLeastOneTick(ticks));
    }

    // ---- entity: follows the entity; `retired` runs instead when the entity is removed first ----

    /** Returns null when the entity is already removed (nothing scheduled, `retired` not called). */
    public ScheduledTask entity(Entity entity, Runnable work, Runnable retired) {
        return entity.getScheduler().run(plugin, task -> work.run(), retired);
    }

    public ScheduledTask entityLater(Entity entity, long ticks, Runnable work, Runnable retired) {
        return entity.getScheduler().runDelayed(plugin, task -> work.run(), retired, atLeastOneTick(ticks));
    }

    public ScheduledTask entityTimer(Entity entity, long delayTicks, long periodTicks,
                                     Consumer<ScheduledTask> body, Runnable retired) {
        return track(entity.getScheduler().runAtFixedRate(plugin, body, retired,
                atLeastOneTick(delayTicks), atLeastOneTick(periodTicks)));
    }

    // ---- async: IO only; never touch worlds, entities or players here ----

    public ScheduledTask async(Runnable work) {
        return server.getAsyncScheduler().runNow(plugin, task -> work.run());
    }

    public ScheduledTask asyncLater(Duration delay, Runnable work) {
        return server.getAsyncScheduler().runDelayed(plugin, task -> work.run(), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Call from onDisable: stops the repeating tasks this facade started and everything global/async. */
    public void cancelAll() {
        repeating.forEach(ScheduledTask::cancel);
        repeating.clear();
        server.getGlobalRegionScheduler().cancelTasks(plugin);
        server.getAsyncScheduler().cancelTasks(plugin);
    }

    private ScheduledTask track(ScheduledTask task) {
        if (task != null) {
            repeating.add(task);
            repeating.removeIf(ScheduledTask::isCancelled);   // keep the set small
        }
        return task;
    }

    private static long atLeastOneTick(long ticks) {
        return Math.max(1L, ticks);
    }
}
```

### `PlayerHud.java`

```java
package com.example.scheduling;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One repeating task per player on the player's own scheduler. On Folia join events of different players can fire
 * on different threads at the same time, so shared state is concurrent. When the player quits, the entity is removed
 * and the scheduler calls `retired` instead of the body: that is where the bookkeeping is cleaned up.
 */
public final class PlayerHud implements Listener {

    private final Tasks tasks;
    private final Map<UUID, ScheduledTask> running = new ConcurrentHashMap<>();

    public PlayerHud(Tasks tasks) {
        this.tasks = tasks;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        ScheduledTask task = tasks.entityTimer(player, 20L, 20L,
                t -> player.sendActionBar(Component.text("Ping: " + player.getPing() + " ms")),   // player's thread
                () -> running.remove(id));                                                         // player removed
        if (task != null) {
            ScheduledTask previous = running.put(id, task);
            if (previous != null) {
                previous.cancel();
            }
        }
    }

    public int active() {
        return running.size();
    }
}
```

### `BlockRegen.java`

```java
package com.example.scheduling;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.Set;

/**
 * Ores broken in a mine come back after a delay. The block event fires on the thread that owns the block, and the
 * region scheduler brings the restore back to whichever thread owns that location later.
 */
public final class BlockRegen implements Listener {

    private static final Set<Material> REGENERATING = Set.of(Material.COAL_ORE, Material.IRON_ORE, Material.DIAMOND_ORE);

    private final Tasks tasks;
    private final long delayTicks;

    public BlockRegen(Tasks tasks, long delayTicks) {
        this.tasks = tasks;
        this.delayTicks = delayTicks;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (REGENERATING.contains(event.getBlock().getType())) {
            schedule(event.getBlock().getLocation(), event.getBlock().getBlockData());
        }
    }

    /** Restores `original` at `location` after the delay, unless something else was placed there meanwhile. */
    public void schedule(Location location, BlockData original) {
        BlockData copy = original.clone();
        tasks.atLater(location, delayTicks, () -> {
            if (location.getBlock().getType().isAir()) {
                location.getBlock().setBlockData(copy, false);
            }
        });
    }
}
```

### `ProfileLoader.java`

```java
package com.example.scheduling;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Async IO, then back to the player's own thread for anything that touches the player. */
public final class ProfileLoader implements Listener {

    private final Tasks tasks;
    private final Path folder;
    private final Logger log;

    public ProfileLoader(Tasks tasks, Path folder, Logger log) {
        this.tasks = tasks;
        this.folder = folder;
        this.log = log;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Path file = folder.resolve(player.getUniqueId() + ".txt");
        tasks.async(() -> {
            String title = read(file);                                    // pool thread: no Bukkit world/player API
            tasks.entity(player,
                    () -> player.sendMessage(Component.text("Welcome back, " + title)),   // player's thread
                    () -> { });                                                          // left before it loaded
        });
    }

    private String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file).strip() : "newcomer";
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not read " + file, e);
            return "newcomer";
        }
    }
}
```

### `SchedulingPlugin.java`

```java
package com.example.scheduling;

import org.bukkit.plugin.java.JavaPlugin;

public final class SchedulingPlugin extends JavaPlugin {

    private Tasks tasks;

    @Override
    public void onEnable() {
        tasks = new Tasks(this);
        getServer().getPluginManager().registerEvents(new PlayerHud(tasks), this);
        getServer().getPluginManager().registerEvents(new BlockRegen(tasks, 20L * 30), this);
        getServer().getPluginManager().registerEvents(new ProfileLoader(tasks, getDataFolder().toPath(), getLogger()), this);
        getLogger().info("Running on " + (Platform.isFolia() ? "Folia" : "Paper"));
        // Never: getServer().getScheduler().runTask(...) - BukkitScheduler is unsupported on Folia
    }

    @Override
    public void onDisable() {
        if (tasks != null) {
            tasks.cancelAll();
        }
    }
}
```

## Recommended Directory Structure

```
src/main/java/com/example/scheduling/
├── Platform.java          <- Folia detection
├── Tasks.java             <- the four schedulers, delay clamping, cancel on disable
├── PlayerHud.java         <- entity scheduler + retired cleanup
├── BlockRegen.java        <- region scheduler for a location
├── ProfileLoader.java     <- async IO, then back to the entity
└── SchedulingPlugin.java
src/main/resources/plugin.yml   <- folia-supported: true
```

## Thread Safety

- Pick the scheduler by **what the code touches**: a block → region (its location); an entity or player → that entity's scheduler; nothing in a world → global; IO → async, then hop back to an entity or region scheduler before touching game state
- On Folia, event handlers run on the thread that owns the event's entity or block, so two handlers can run at the same time: shared maps and counters must be concurrent (`ConcurrentHashMap`, atomics) or confined to one thread
- `Bukkit.isOwnedByCurrentRegion(location | entity)` tells whether the current thread may touch it; on Paper it is the main-thread check
- See [`references/paper-threading.md`](references/paper-threading.md)

### Key Techniques

| Topic | Approach |
|------|------|
| One code path | Use the four schedulers on Paper too; avoid `if (isFolia()) BukkitScheduler else ...` branches |
| Delay 0 | The schedulers reject delays and periods below 1 tick; clamp to 1 (`Tasks.atLeastOneTick`) or use `run` / `execute` for "as soon as possible" |
| Entity gone | The `retired` callback runs instead of the task when the entity is removed (quit, death, `remove()`); `run` returns `null` if it is already gone |
| Cancel a repeating task | Call `task.cancel()` inside the body (it receives its `ScheduledTask`) or keep the handle; cancel global/async tasks with `cancelTasks(plugin)` |
| Teleport | `entity.teleportAsync(location)`; continue on the entity scheduler after the future completes |
| Console commands | `Bukkit.dispatchCommand(console, ...)` from the global scheduler |

## Fallback

| Error | Cause | Fix |
|------|------|------|
| Folia does not load the plugin | `folia-supported: true` missing from `plugin.yml` | Add it once the plugin uses only these schedulers |
| Folia: `UnsupportedOperationException` from `runTask` / `runTaskTimer` | `BukkitScheduler` used | Replace with `Tasks` (region / entity / global / async) |
| Folia: `UnsupportedOperationException: Must use teleportAsync while in region threading` | `Entity#teleport` | `teleportAsync`, then continue on the entity scheduler |
| `IllegalArgumentException: Delay ticks may not be <= 0` | A delay (or period) of 0 | Clamp to at least 1 tick (`Tasks` does) or use `run` / `execute` |
| Folia: exceptions or corrupted state when touching an entity or block | Code ran on another region's thread or on an async thread | Schedule on the entity's or location's scheduler first; `Bukkit.isOwnedByCurrentRegion(...)` tells whether the current thread may touch it |
| Tasks for an entity never run (no player nearby) | The entity's chunk is not loaded, so it does not tick | Keep the chunk loaded (`getChunkAtAsync` + `addPluginChunkTicket`) or schedule only for loaded entities |
| A per-player task keeps references after the player quit | Cleanup only in the body | Clean up in the `retired` callback |
| Tasks keep running after `/reload` or disable | Repeating tasks not cancelled | `Tasks.cancelAll()` in `onDisable` |
