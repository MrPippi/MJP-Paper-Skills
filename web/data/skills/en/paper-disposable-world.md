
# Paper Disposable World

## Purpose

Minigames and duels need throwaway worlds, or arenas that are restored after every match. Two routes are provided: "swap the world" and "restore in place"; world operations always run on the main thread, and only file IO (copy, delete) is async.

---

## Platform Requirements

- Paper 1.21.11 / 26.3 (compile-verified on both)
- Pure Paper API, no Paperweight needed
- The old GameRule constants are deprecated; the templates instead look rules up by snake_case key in `Registry.GAME_RULE` (identical on both versions)
- **Spigot**: Paper only — uses `Entity#teleportAsync` and Paper's world storage layout

---

## Generated Code

### VoidChunkGenerator.java

```java
public final class VoidChunkGenerator extends ChunkGenerator {
    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    // ... Bedrock / Caves / Decorations / Mobs / Structures likewise return false
    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, 64.0, 0.5);
    }
}
```

### Creation and guard (DisposableWorlds)

```java
// Refuse if the folder already exists: WorldCreator silently generates terrain for a broken folder as if it were a new world
World world = WorldCreator.name(name)
        .generator(generator)
        .generateStructures(false)
        .createWorld();
rules.apply(world);   // Apply game rules immediately after creation
```

### Template copy and deletion (WorldFolders, async)

```java
// 26.x keeps world data in <main world>/dimensions/minecraft/<name>/ (1.21.11: <world container>/<name>/); resolve paths with WorldFolders.dataRoot / getWorldPath()
// Copy: skip the world UUID file (26.x data/paper/metadata.dat, 1.21.11 uid.dat) and session.lock; write to <name>.copying, then rename atomically
// Delete: rename to <name>.deleting first; purgeStaleAsync clears leftovers and .copying / .deleting debris at startup (verified on running Paper 26.3 and 1.21.11 servers)
WorldFolders.copyTemplate(template, target);
WorldFolders.deleteWorld(folder);
```

### In-place restore (ChangeRecorder + ArenaResetService)

```java
// Record each cell's BlockData before its first change; restoring is limited per tick by block count and a time budget
cells.putIfAbsent(ChangeRecorder.key(block), block.getBlockData());   // own x/y/z packing; Block#getBlockKey is deprecated
block.setBlockData(original, false);
sweeper.sweepAll(world);   // Leftover entities: dropped items, arrows, TNT minecarts, crystals, etc.
```

---

## Thread Safety

- World creation / unloading and block and entity operations run on the main thread only
- Only the copy and delete in `WorldFolders` are async; lambdas carry only `Path` / `String`
- Returned futures always complete on the main thread; `onDisable` finishes synchronously with `closeAllSync()`
- For the NMS fast path for large block counts (hundreds of thousands of cells), see `nms-chunk-access`
