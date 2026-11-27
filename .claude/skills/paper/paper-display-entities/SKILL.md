---
name: paper-display-entities
description: "Paper Display 實體：TextDisplay 全息字、ItemDisplay／BlockDisplay 展示與插值動畫，不用盔甲座或封包；非持久化實體＋區塊載入時重建、以 PDC 標記清掉重複、只給單一玩家看（setVisibleByDefault＋showEntity）、Folia 相容排程 / Paper display entities: TextDisplay holograms, ItemDisplay / BlockDisplay showcases and interpolated animations without armor stands or packets; non-persistent entities rebuilt on chunk load, PDC tags to remove duplicates, per-player holograms (setVisibleByDefault + showEntity), Folia-compatible scheduling"
---

# Paper Display Entities

## Skill Name

`paper-display-entities`

## Purpose

Display entities (`TextDisplay`, `ItemDisplay`, `BlockDisplay`) replace the old armor-stand and packet tricks for holograms, floating items and animated decorations. They have no hitbox, no AI and no gravity, are rendered with any scale and rotation, and the client interpolates between transformations, so smooth animations need one update per step instead of one per tick.

The hard part is not spawning them but keeping exactly one copy alive:

| Problem | Approach in this template |
|------|------|
| Holograms saved into the world duplicate on every restart or after a crash | `setPersistent(false)`: the entity is never saved; the plugin owns the definition and spawns it again |
| A non-persistent entity does not reliably survive its chunk unloading (Paper discards it; Folia kept it in memory and brought it back on a quick reload) | Remove it in `EntitiesUnloadEvent`, spawn again on `ChunkLoadEvent` |
| Leftovers from older versions that were persistent | Tag every display with a PDC key; on `EntitiesLoadEvent` remove tagged entities that are not the live copy |
| Text changes | Update the live entity (`text(...)`); do not remove and respawn |
| One player only | `setVisibleByDefault(false)` inside the spawn consumer, then `player.showEntity(plugin, display)` |

Observed at runtime (Paper and Folia, 26.3 and 1.21.11, no player online; the chunks were kept loaded with plugin chunk tickets):

| Behavior | Result |
|------|------|
| `define` in a loaded chunk | one non-persistent, PDC-tagged `TextDisplay`; `setText` changes it in place (same UUID) |
| `define` in an unloaded chunk | nothing until the chunk loads; `ChunkLoadEvent` then spawns it |
| Chunk unloaded and loaded again right away | **Paper** discarded the display; **Folia** kept it in memory and the same entity came back. With the `EntitiesUnloadEvent` removal both end with exactly one new display |
| Persistent tagged copy left in the world, then a restart | it was saved, loaded again (`EntitiesLoadEvent`) and removed; one live non-persistent copy remained |
| `setTeleportDuration(100)` | `IllegalArgumentException: duration (100) cannot be lower than 0 or higher than 59` |
| `ItemSpinner` with 5-tick steps | interpolation duration 5 stored on the entity; the rotation advanced every step |

## Paper Version Requirements

- Paper 1.21.11 / 26.3: `Display`, `TextDisplay`, `ItemDisplay`, `BlockDisplay`, `Transformation`, `EntitiesLoadEvent`, `setVisibleByDefault` / `showEntity` have identical signatures on both (compared with `javap`); compile-verified on both
- Scheduling uses the region and entity schedulers, so the template also runs on Folia (see `paper-folia-scheduler`)
- **Spigot**: display entities and the API exist on Spigot 1.19.4+; replace `text(Component)` with `setText(String)` and the Paper schedulers with `BukkitScheduler`

## Triggers

- 「全息字」「hologram」「TextDisplay」「ItemDisplay」「BlockDisplay」「Display 實體」「display entity」
- 「浮空文字」「浮空物品」「排行榜全息」「插值動畫」「interpolation」「Transformation」
- 「只給某玩家看」「setVisibleByDefault」「showEntity」「盔甲座全息替代」

## Inputs

| Parameter | Example | Description |
|------|------|------|
| `holograms` | `spawn` at world 0 70 0: "Welcome" | Id, location and text of each hologram |
| `animated_items` | diamond spinning above the shop | Item displays and their animation |
| `per_player` | quest marker | Holograms only one player sees |

## Outputs

- `HologramSpec.java` — immutable definition (id, world, position, text)
- `Holograms.java` — owns definitions, spawns non-persistent `TextDisplay`s, respawns on chunk load, removes tagged leftovers, updates text in place
- `ItemSpinner.java` — `ItemDisplay` rotated with client-side interpolation on the entity scheduler
- `PrivateHologram.java` — a hologram only one player sees
- `HologramPlugin.java` — wiring

## Build Setup

See [`references/paper-api-platform.md`](references/paper-api-platform.md). Only `paper-api` (`compileOnly`). Add `folia-supported: true` to `plugin.yml` only if the rest of the plugin is Folia-safe.

## Code Template

### `HologramSpec.java`

```java
package com.example.hologram;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** What a hologram should be. The live entity is derived from this and can be rebuilt at any time. */
public record HologramSpec(String id, String world, double x, double y, double z, Component text) {

    public HologramSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Hologram id must not be blank");
        }
    }

    public static HologramSpec at(String id, Location location, Component text) {
        return new HologramSpec(id, location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), text);
    }

    public HologramSpec withText(Component newText) {
        return new HologramSpec(id, world, x, y, z, newText);
    }

    /** Null when the world is not loaded. */
    public Location location() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z);
    }

    public boolean inChunk(World w, int chunkX, int chunkZ) {
        return w.getName().equals(world) && ((int) Math.floor(x)) >> 4 == chunkX && ((int) Math.floor(z)) >> 4 == chunkZ;
    }
}
```

### `Holograms.java`

```java
package com.example.hologram;

import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Text holograms as non-persistent TextDisplays. The plugin keeps the definitions; entities are never saved, so a
 * restart or crash cannot duplicate them. Spawning and edits run on the region / entity scheduler (main thread on
 * Paper, owning region on Folia).
 */
public final class Holograms implements Listener {

    private final Plugin plugin;
    private final NamespacedKey idKey;
    private final Map<String, HologramSpec> specs = new ConcurrentHashMap<>();
    private final Map<String, TextDisplay> live = new ConcurrentHashMap<>();

    public Holograms(Plugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "hologram");
    }

    /** Adds or replaces a hologram; spawns it now if its chunk is loaded, otherwise when the chunk loads. */
    public void define(HologramSpec spec) {
        HologramSpec previous = specs.put(spec.id(), spec);
        if (previous != null && !samePosition(previous, spec)) {
            despawn(spec.id());
        }
        Location at = spec.location();
        if (at != null && at.isChunkLoaded()) {
            plugin.getServer().getRegionScheduler().run(plugin, at, task -> spawn(spec));
        }
    }

    /** Changes the text of the live entity in place; the definition is updated for later respawns. */
    public void setText(String id, net.kyori.adventure.text.Component text) {
        HologramSpec spec = specs.computeIfPresent(id, (k, s) -> s.withText(text));
        TextDisplay display = live.get(id);
        if (spec != null && display != null) {
            display.getScheduler().run(plugin, task -> display.text(text), null);
        }
    }

    public void remove(String id) {
        specs.remove(id);
        despawn(id);
    }

    public Optional<TextDisplay> entity(String id) {
        TextDisplay display = live.get(id);
        return display != null && display.isValid() ? Optional.of(display) : Optional.empty();
    }

    public NamespacedKey idKey() {
        return idKey;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        for (HologramSpec spec : specs.values()) {
            if (spec.inChunk(chunk.getWorld(), chunk.getX(), chunk.getZ())) {
                // next tick on the chunk's region: never add entities while the chunk is still loading
                plugin.getServer().getRegionScheduler().run(plugin, spec.location(), task -> spawn(spec));
            }
        }
    }

    /** Saved copies (older persistent versions, other tools) are removed; the plugin's own copy is non-persistent. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            String id = entity.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
            if (id != null && live.get(id) != entity) {
                entity.remove();
            }
        }
    }

    /**
     * Removes the plugin's displays when their chunk unloads. Without this, Folia brought the same entity back when the
     * chunk was loaded again right away (Paper had discarded it), which races with the respawn above.
     */
    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            String id = entity.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
            if (id != null) {
                live.remove(id, entity);
                entity.remove();
            }
        }
    }

    /** Paper: call from onDisable so /reload or a disabled plugin leaves nothing behind. */
    public void despawnAll() {
        live.keySet().forEach(this::despawn);
    }

    private void spawn(HologramSpec spec) {
        if (specs.get(spec.id()) != spec) {
            return;                                   // replaced or removed meanwhile
        }
        TextDisplay current = live.get(spec.id());
        if (current != null && current.isValid()) {
            return;
        }
        Location at = spec.location();
        if (at == null) {
            return;
        }
        removeLeftovers(at.getChunk(), spec.id());
        TextDisplay display = at.getWorld().spawn(at, TextDisplay.class, d -> {
            // configured before it is added to the world: no client ever sees a default-looking entity
            d.setPersistent(false);
            d.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, spec.id());
            d.text(spec.text());
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(96, 0, 0, 0));
            d.setShadowed(true);
            d.setViewRange(0.5f);                    // x 64 blocks
        });
        live.put(spec.id(), display);
    }

    /** Copies already in the chunk (e.g. saved by an older, persistent version). Runs on the chunk's region. */
    private void removeLeftovers(Chunk chunk, String id) {
        for (Entity entity : chunk.getEntities()) {
            if (id.equals(entity.getPersistentDataContainer().get(idKey, PersistentDataType.STRING))) {
                entity.remove();
            }
        }
    }

    private void despawn(String id) {
        TextDisplay display = live.remove(id);
        if (display != null && display.isValid()) {
            display.getScheduler().run(plugin, task -> display.remove(), null);
        }
    }

    private static boolean samePosition(HologramSpec a, HologramSpec b) {
        return a.world().equals(b.world()) && a.x() == b.x() && a.y() == b.y() && a.z() == b.z();
    }
}
```

### `ItemSpinner.java`

```java
package com.example.hologram;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * A floating item that turns a quarter per step. The server sends one transformation per step and the client
 * interpolates in between, so `stepTicks` can be large without the animation stuttering.
 */
public final class ItemSpinner {

    private static final float QUARTER_TURN = (float) (Math.PI / 2);

    private ItemSpinner() {
    }

    /** Spawns the display (call on the location's region) and starts the animation on its own scheduler. */
    public static ItemDisplay spawn(Plugin plugin, Location at, ItemStack item, int stepTicks) {
        ItemDisplay display = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            d.setBillboard(Display.Billboard.FIXED);
        });
        float[] angle = {0f};
        display.getScheduler().runAtFixedRate(plugin, (ScheduledTask task) -> {
            angle[0] += QUARTER_TURN;
            Transformation t = display.getTransformation();
            display.setInterpolationDelay(0);                 // start now ...
            display.setInterpolationDuration(stepTicks);       // ... and take the whole step to get there
            display.setTransformation(new Transformation(
                    t.getTranslation(), new AxisAngle4f(angle[0], 0, 1, 0), t.getScale(), new AxisAngle4f()));
        }, null, 2L, Math.max(1, stepTicks));                  // first step one tick after spawn: the client needs the start pose
        return display;
    }

    /** The interpolation target is stored on the entity; useful for tests and for resuming after a restart. */
    public static Vector3f scale(ItemDisplay display) {
        return display.getTransformation().getScale();
    }
}
```

### `PrivateHologram.java`

```java
package com.example.hologram;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;

/** A hologram only `viewer` sees, e.g. a quest marker. Call on the location's region. */
public final class PrivateHologram {

    private PrivateHologram() {
    }

    public static TextDisplay show(Plugin plugin, Player viewer, Location at, Component text) {
        TextDisplay display = at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.setVisibleByDefault(false);   // inside the consumer: hidden before anyone is sent the entity
            d.setPersistent(false);
            d.text(text);
            d.setBillboard(Display.Billboard.CENTER);
        });
        viewer.showEntity(plugin, display);
        return display;
    }
}
```

### `HologramPlugin.java`

```java
package com.example.hologram;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

public final class HologramPlugin extends JavaPlugin {

    private Holograms holograms;

    @Override
    public void onEnable() {
        holograms = new Holograms(this);
        getServer().getPluginManager().registerEvents(holograms, this);
        World world = getServer().getWorlds().get(0);
        Location spawn = world.getSpawnLocation().add(0.5, 2.5, 0.5);
        holograms.define(HologramSpec.at("welcome", spawn,
                Component.text("Welcome!", NamedTextColor.GOLD).appendNewline().append(Component.text("Have fun"))));
        // A changing hologram: compute on the global scheduler, then setText (applied on the entity's own thread)
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> holograms.setText("welcome",
                Component.text("Welcome!", NamedTextColor.GOLD).appendNewline()
                        .append(Component.text(getServer().getOnlinePlayers().size() + " online"))), 20L, 20L * 10);
    }

    @Override
    public void onDisable() {
        if (holograms != null) {
            holograms.despawnAll();
        }
    }
}
```

## Recommended Directory Structure

```
src/main/java/com/example/hologram/
├── HologramSpec.java     <- definition (immutable)
├── Holograms.java        <- spawn / respawn on chunk load / update / leftover cleanup
├── ItemSpinner.java      <- interpolated ItemDisplay animation
├── PrivateHologram.java  <- per-player visibility
└── HologramPlugin.java
```

## Thread Safety

- Spawning touches the world: run it on the location's region scheduler (main thread on Paper); edits to a live display run on its entity scheduler
- `Holograms` keeps its maps concurrent because on Folia chunk-load events of different regions can run at the same time
- See [`references/paper-threading.md`](references/paper-threading.md) and `paper-folia-scheduler`

### Key Techniques

| Topic | Approach |
|------|------|
| Configure before spawn | `world.spawn(location, TextDisplay.class, d -> { ... })` sets everything before the entity is added, so clients never see a half-configured one |
| Never saved | `setPersistent(false)`; the definition lives in the plugin (or its config) |
| Respawn | `EntitiesUnloadEvent` → remove; `ChunkLoadEvent` → region scheduler `run` (next tick) → spawn if not alive |
| Leftover cleanup | PDC id tag; `EntitiesLoadEvent` removes tagged entities that are not the live copy |
| Smooth animation | Per step: `setInterpolationDelay(0)`, `setInterpolationDuration(step)`, then `setTransformation(...)`; start one tick after spawning |
| Movement | `setTeleportDuration(ticks)` makes the client interpolate teleports of the display; only 0..59 is accepted |
| Multi-line text | One `TextDisplay` with `Component.newline()`; `setLineWidth` wraps long lines |
| View distance | `setViewRange(f)` is a multiplier of 64 blocks |
| Per-player | `setVisibleByDefault(false)` in the spawn consumer + `player.showEntity(plugin, display)` |

## Fallback

| Error | Cause | Fix |
|------|------|------|
| Hologram duplicated after every restart | Persistent displays spawned again on enable | `setPersistent(false)` and respawn from definitions; remove tagged leftovers in `EntitiesLoadEvent` |
| Hologram missing after players walked away and came back | Non-persistent entity discarded with its chunk | Respawn on `ChunkLoadEvent` (this template) |
| Animation jumps instead of gliding | Interpolation duration not set, or the transformation changed on the spawn tick | Set delay 0 and duration before each `setTransformation`; first step at least one tick after spawning |
| Everyone sees the private hologram for a moment | `setVisibleByDefault(false)` called after `spawn` returned | Call it inside the spawn consumer |
| Folia: exceptions when updating text | Edited from another region's thread | Edit through `display.getScheduler()` (`setText` does) |
| Text shows `[text]` or legacy colour codes | `setText(String)` with `&` / `§` codes | Use `text(Component)` with Adventure / MiniMessage |
| `IllegalArgumentException: duration (...) cannot be lower than 0 or higher than 59` | `setTeleportDuration` outside 0..59 | Clamp to 0..59 |
| Two copies after a chunk reload (Folia) | The old display came back with the chunk while a new one was spawned | Remove the plugin's displays in `EntitiesUnloadEvent` (this template) |
