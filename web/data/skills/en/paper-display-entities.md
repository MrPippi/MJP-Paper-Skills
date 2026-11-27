# Paper Display Entities

## Purpose

`TextDisplay`, `ItemDisplay` and `BlockDisplay` replace armor stands and packet tricks for holograms, floating items and animated decorations: no hitbox, AI or gravity, any scale and rotation, and the client interpolates between transformations, so a smooth animation needs only one update per step.

The hard part is keeping exactly one copy:

| Problem | Approach in the template |
|------|------|
| Holograms saved into the world duplicate after restarts or crashes | `setPersistent(false)`: never saved; the plugin keeps the definition and spawns it again |
| An entity does not reliably survive its chunk unloading | Remove it in `EntitiesUnloadEvent`, rebuild it on `ChunkLoadEvent` |
| Persistent copies left by an older version | A PDC tag on every display; `EntitiesLoadEvent` removes tagged entities that are not the live copy |
| Text changes | Update the live entity (`text(...)`) instead of removing and respawning |
| One player only | `setVisibleByDefault(false)` inside the spawn consumer, then `player.showEntity(plugin, display)` |

---

## Platform Requirements

- Paper 1.21.11 / 26.3: `Display`, `TextDisplay`, `ItemDisplay`, `BlockDisplay`, `Transformation`, `EntitiesLoadEvent`, `setVisibleByDefault` / `showEntity` have identical signatures; compile-verified on both
- Scheduling uses the region and entity schedulers, so the template also runs on Folia; runtime-verified on Paper and Folia, 26.3 and 1.21.11
- **Spigot**: display entities exist since 1.19.4; use `setText(String)` instead of `text(Component)` and `BukkitScheduler` instead of the Paper schedulers

---

## Generated Code

### Holograms.java

```java
TextDisplay display = world.spawn(at, TextDisplay.class, d -> {   // configured before it joins the world
    d.setPersistent(false);
    d.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, spec.id());
    d.text(spec.text());
    d.setBillboard(Display.Billboard.CENTER);
});
```

### ItemSpinner.java (interpolated animation)

```java
display.setInterpolationDelay(0);              // start now
display.setInterpolationDuration(stepTicks);    // take the whole step to get there
display.setTransformation(new Transformation(translation, new AxisAngle4f(angle, 0, 1, 0), scale, new AxisAngle4f()));
```

---

## Runtime Results

| Item | Result |
|------|------|
| Chunk unloaded and loaded again right away | Paper discarded the entity; **Folia brought the same entity back**. With the `EntitiesUnloadEvent` removal both end with exactly one new display |
| Persistent copy left behind, then a restart | the copy was saved, removed when it loaded, and one live non-persistent display remained |
| `setTeleportDuration(100)` | `IllegalArgumentException`: only 0..59 is accepted |
| `setText` | updates the same entity (same UUID) |

---

## Thread Safety

- Spawning changes the world: run it on the location's region scheduler (the main thread on Paper); edit a live display through its entity scheduler
- On Folia chunk events of different regions can run at the same time, so `Holograms` keeps its maps in `ConcurrentHashMap`s
