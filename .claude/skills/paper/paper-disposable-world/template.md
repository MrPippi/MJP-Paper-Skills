# Paper Disposable World — Code Template

Full, compile-verified source for [`SKILL.md`](SKILL.md). The base package is `com.example.arena`; rename it to your `base_package`. Rules, thread safety and the fallback table are in `SKILL.md`.

## `VoidChunkGenerator.java`

```java
package com.example.arena;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

import java.util.Random;

/**
 * A world that generates nothing (void).
 *
 * <p>Note: a custom generator is **not** written into the world folder. It must be passed again every time an existing world is loaded (including ones copied from a template),
 * otherwise new chunks are generated with vanilla terrain and will not connect to the old chunks.
 */
public final class VoidChunkGenerator extends ChunkGenerator {

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    /** Fixed spawn point; without it, Paper searches the void for a "safe" spawn point and stalls for a long time. */
    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, 64.0, 0.5);
    }
}
```

## `ArenaNames.java`

```java
package com.example.arena;

import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Serial naming: {@code arena_1} ... {@code arena_<max>}; wraps back to 1 at the top and skips numbers still in use.
 * Pure logic, does not touch Bukkit; "in use" is decided by the caller's predicate (loaded, reserved, folder exists).
 */
public final class ArenaNames {

    public static final String PREFIX = "arena_";
    private static final Pattern PATTERN = Pattern.compile("arena_\\d{1,6}");

    private final int max;
    private int cursor;

    public ArenaNames(int max) {
        if (max < 1 || max > 999_999) {
            throw new IllegalArgumentException("max must be in 1..999999: " + max);
        }
        this.max = max;
    }

    /** Guard before deletion: only folders matching this mechanism's naming may be deleted. */
    public static boolean isArena(String worldName) {
        return PATTERN.matcher(worldName).matches();
    }

    public String next(Predicate<String> taken) {
        for (int i = 0; i < max; i++) {
            cursor = cursor % max + 1;
            String candidate = PREFIX + cursor;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("All " + max + " arena names are in use");
    }
}
```

## `ArenaRules.java`

```java
package com.example.arena;

import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;

import java.util.logging.Logger;

/**
 * Settings applied **immediately** after world creation (before any player enters, before any tick).
 *
 * <p>Rules are always looked up by registry key ({@code advance_time}, {@code spawn_mobs}...), not through the deprecated
 * constants such as {@code GameRule.DO_DAYLIGHT_CYCLE}: old constants get renamed across versions, and a key that cannot be found only warns instead of failing the creation flow.
 */
public final class ArenaRules {

    private final Logger logger;

    public ArenaRules(Logger logger) {
        this.logger = logger;
    }

    public void apply(World world) {
        world.setDifficulty(Difficulty.NORMAL);
        world.setAutoSave(false);          // Disposable: do not write chunks back to disk
        world.setTime(6000L);
        world.setStorm(false);
        world.setThundering(false);

        setBoolean(world, "advance_time", false);          // formerly doDaylightCycle
        setBoolean(world, "advance_weather", false);       // formerly doWeatherCycle
        setBoolean(world, "spawn_mobs", false);            // formerly doMobSpawning
        setBoolean(world, "spawn_monsters", false);
        setBoolean(world, "mob_griefing", false);
        setBoolean(world, "keep_inventory", true);
        setBoolean(world, "immediate_respawn", true);
        setBoolean(world, "show_advancement_messages", false);
    }

    @SuppressWarnings("unchecked")
    private void setBoolean(World world, String key, boolean value) {
        GameRule<?> rule = Registry.GAME_RULE.get(NamespacedKey.minecraft(key));
        if (rule == null || rule.getType() != Boolean.class) {
            logger.warning("Unknown or non-boolean game rule '" + key + "'; skipped for " + world.getName());
            return;
        }
        world.setGameRule((GameRule<Boolean>) rule, value);
    }
}
```

## `WorldFolders.java`

```java
package com.example.arena;

import org.bukkit.Server;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * File operations on world data folders. **All blocking IO; call only from an async thread** (except at server shutdown,
 * and {@link #dataRoot}, which only reads paths).
 *
 * <p>Where a world keeps its data depends on the version (checked on a running Paper 26.3 server):
 * <ul>
 *   <li>26.x: every world is a dimension of the main world: {@code <main world>/dimensions/minecraft/<name>/}
 *       ({@code region/}, {@code data/}, {@code paper-world.yml}; no {@code level.dat} of its own)</li>
 *   <li>1.21.11: a separate folder {@code <world container>/<name>/} with its own {@code level.dat}</li>
 * </ul>
 * A template is therefore a copy of an arena's data folder ({@code World#getWorldPath()}) saved on the <b>same</b> version.
 *
 * <p>Crash safety does not depend on any marker file: a copy is written to {@code <name>.copying} and renamed into place
 * atomically, and a deletion first renames the folder to {@code <name>.deleting}. A crash leaves only suffixed debris,
 * which {@link DisposableWorlds#purgeStaleAsync()} removes, never a half-written folder under a real world name.
 */
public final class WorldFolders {

    public static final String COPYING = ".copying";
    public static final String DELETING = ".deleting";

    /**
     * Files that belong to one particular world and must never be duplicated (paths relative to the data folder):
     * the world UUID (26.x {@code data/paper/metadata.dat}, 1.21.11 {@code uid.dat}) and the session lock.
     * A copied UUID makes two loaded worlds collide.
     */
    private static final Set<String> SKIP = Set.of("data/paper/metadata.dat", "uid.dat", "session.lock");

    private WorldFolders() {
    }

    /**
     * Parent folder of every world's data folder: on 26.x the main world's {@code dimensions/minecraft} folder, on 1.21.11
     * the world container. Call after the default worlds are loaded (any time from a normal {@code onEnable}).
     */
    public static Path dataRoot(Server server) {
        Path main = server.getWorlds().get(0).getWorldPath().toAbsolutePath().normalize();
        Path namespace = main.getParent();                         // .../dimensions/minecraft on 26.x
        Path dimensions = namespace == null ? null : namespace.getParent();
        boolean dimensionLayout = dimensions != null && dimensions.getFileName() != null
                && dimensions.getFileName().toString().equals("dimensions");
        return dimensionLayout ? namespace : server.getWorldContainer().toPath().toAbsolutePath().normalize();
    }

    /** A world data folder (or template) always has {@code region/}, on both versions. */
    public static boolean looksLikeWorld(Path folder) {
        return Files.isDirectory(folder.resolve("region"));
    }

    /** Copy the template into {@code <target>.copying}, then rename it to {@code target} in one atomic step. */
    public static void copyTemplate(Path template, Path target) throws IOException {
        if (!looksLikeWorld(template)) {
            throw new IOException("Template has no region/ folder (not a world data folder): " + template);
        }
        if (Files.exists(target)) {
            throw new IOException("Target already exists: " + target);
        }
        Path staging = target.resolveSibling(target.getFileName() + COPYING);
        deleteTree(staging);                                      // debris from an earlier crash
        Files.walkFileTree(template, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(staging.resolve(template.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relative = template.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                if (!SKIP.contains(relative)) {
                    Files.copy(file, staging.resolve(relative), StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Rename the folder to {@code <name>.deleting} first, so an interrupted delete never leaves a loadable world behind. */
    public static void deleteWorld(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        Path doomed = folder.resolveSibling(folder.getFileName() + DELETING);
        deleteTree(doomed);
        Files.move(folder, doomed, StandardCopyOption.ATOMIC_MOVE);
        deleteTree(doomed);
    }

    /** Plain recursive delete, children first. */
    public static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(folder)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
```

## `DisposableWorlds.java`

```java
package com.example.arena;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Creation and destruction of disposable worlds.
 *
 * <p>Threading: {@code createVoid}, {@code createFromTemplate}, and {@code destroy} must be called on the main thread;
 * world loading/unloading always happens on the main thread; only copying and deleting folders is async. Returned futures always complete on the main thread.
 * This class's fields are read and written only on the main thread, so no locks are needed.
 *
 * <p>World data folders are resolved with {@link WorldFolders#dataRoot}: on 26.x they live under the main world's
 * {@code dimensions/minecraft/}, not next to it, so never build them from {@code getWorldContainer()} yourself.
 */
public final class DisposableWorlds {

    private final Plugin plugin;
    private final Server server;
    private final ArenaNames names;
    private final ArenaRules rules;
    private final Path dataRoot;
    private final Set<String> owned = new LinkedHashSet<>();
    private final Set<String> reserved = new HashSet<>();

    /** Construct in {@code onEnable} (the default worlds must already be loaded). */
    public DisposableWorlds(Plugin plugin, ArenaNames names, ArenaRules rules) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.names = names;
        this.rules = rules;
        this.dataRoot = WorldFolders.dataRoot(server);
    }

    /** Create a void world with a single spawn platform block in the center (in practice, replace with your paste flow). */
    public World createVoid() {
        requireMainThread();
        String name = names.next(this::taken);
        requireFresh(name);
        World world = load(name, new VoidChunkGenerator());
        world.getBlockAt(0, 63, 0).setType(Material.STONE);
        return world;
    }

    /**
     * Copy the template async, then load it on the main thread.
     *
     * @param generator pass {@link VoidChunkGenerator} if the template is a void map; pass {@code null} for vanilla terrain
     */
    public CompletableFuture<World> createFromTemplate(Path template, ChunkGenerator generator) {
        requireMainThread();
        String name = names.next(this::taken);
        requireFresh(name);
        reserved.add(name);                      // Hold the number during the copy so a second call in the same tick does not collide on the name
        Path target = dataRoot.resolve(name);    // where createWorld will look for this world's data
        CompletableFuture<World> pipeline = CompletableFuture
                .runAsync(() -> copy(template, target), this::async)
                .thenCompose(v -> onMain(() -> load(name, generator)));
        return finishOnMain(pipeline, () -> reserved.remove(name), failure -> async(() -> deleteQuietly(target)));
    }

    /** Teleport players away -> confirm nobody is left -> unload (without saving) -> delete the folder async. */
    public CompletableFuture<Void> destroy(World world) {
        requireMainThread();
        String name = world.getName();
        if (!owned.contains(name) || !ArenaNames.isArena(name)) {
            throw new IllegalArgumentException("Not a disposable world: " + name);
        }
        Location exit = server.getWorlds().get(0).getSpawnLocation();
        List<CompletableFuture<Boolean>> moves = world.getPlayers().stream()
                .map(player -> player.teleportAsync(exit)).toList();
        Path folder = world.getWorldPath();
        CompletableFuture<Void> pipeline = CompletableFuture.allOf(moves.toArray(new CompletableFuture<?>[0]))
                .thenCompose(v -> onMain(() -> {
                    unload(world);
                    return null;
                }))
                .thenRunAsync(() -> deleteFolder(folder), this::async);
        return finishOnMain(pipeline, () -> { }, failure -> { });
    }

    /**
     * On startup, clean up arena_* data and {@code .copying} / {@code .deleting} debris left by the last crash
     * (the prefix is reserved for this mechanism). Scans {@link WorldFolders#dataRoot}, which on 26.x is
     * {@code <main world>/dimensions/minecraft}: scanning the world container would miss every arena.
     */
    public void purgeStaleAsync() {
        Set<String> loaded = server.getWorlds().stream().map(World::getName).collect(Collectors.toSet());
        async(() -> {
            try (Stream<Path> dirs = Files.list(dataRoot)) {
                for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                    String name = dir.getFileName().toString();
                    String base = stripDebrisSuffix(name);
                    if (!ArenaNames.isArena(base)) {
                        continue;
                    }
                    if (!base.equals(name)) {
                        deleteTreeQuietly(dir);            // interrupted copy or delete
                    } else if (!loaded.contains(name)) {
                        deleteQuietly(dir);
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not scan for stale arena folders", e);
            }
        });
    }

    /** For onDisable only: the scheduler no longer accepts tasks, so unload and delete synchronously (blocking is acceptable at shutdown). */
    public void closeAllSync() {
        Location exit = server.getWorlds().get(0).getSpawnLocation();
        for (String name : List.copyOf(owned)) {
            World world = server.getWorld(name);
            if (world == null) {
                owned.remove(name);
                continue;
            }
            Path folder = world.getWorldPath();
            for (Player player : List.copyOf(world.getPlayers())) {
                player.teleport(exit);
            }
            if (server.unloadWorld(world, false)) {
                owned.remove(name);
                deleteQuietly(folder);
            }
        }
    }

    // ---- Internals ----

    private boolean taken(String name) {
        return server.getWorld(name) != null || reserved.contains(name) || Files.exists(dataRoot.resolve(name));
    }

    /**
     * Guard: refuse if data for this name already exists. {@code createWorld} silently loads whatever it finds there
     * (stale arena data after a crash) or generates new terrain over an incomplete folder.
     */
    private void requireFresh(String name) {
        if (server.getWorld(name) != null) {
            throw new IllegalStateException("World already loaded: " + name);
        }
        Path folder = dataRoot.resolve(name);
        if (Files.exists(folder)) {
            String why = WorldFolders.looksLikeWorld(folder)
                    ? "existing world data"
                    : "a folder without region/ (half-deleted or foreign data)";
            throw new IllegalStateException("Refusing to create '" + name + "' over " + why + " at " + folder);
        }
    }

    private static String stripDebrisSuffix(String name) {
        for (String suffix : List.of(WorldFolders.COPYING, WorldFolders.DELETING)) {
            if (name.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    private World load(String name, ChunkGenerator generator) {
        World world = WorldCreator.name(name)
                .environment(World.Environment.NORMAL)
                .generator(generator)
                .generateStructures(false)
                .createWorld();
        if (world == null) {
            throw new IllegalStateException("createWorld returned null for " + name);
        }
        rules.apply(world);                       // Apply right after creation, before any player or tick
        owned.add(name);
        return world;
    }

    private void unload(World world) {
        if (!world.getPlayers().isEmpty()) {
            throw new IllegalStateException("Players still inside " + world.getName());
        }
        if (!server.unloadWorld(world, false)) {
            throw new IllegalStateException("unloadWorld refused for " + world.getName());
        }
        owned.remove(world.getName());
    }

    private void copy(Path template, Path target) {
        try {
            WorldFolders.copyTemplate(template, target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteFolder(Path folder) {
        try {
            WorldFolders.deleteWorld(folder);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteQuietly(Path folder) {
        try {
            WorldFolders.deleteWorld(folder);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not delete " + folder, e);
        }
    }

    private void deleteTreeQuietly(Path folder) {
        try {
            WorldFolders.deleteTree(folder);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not delete " + folder, e);
        }
    }

    private void async(Runnable task) {
        server.getScheduler().runTaskAsynchronously(plugin, task);
    }

    private <T> CompletableFuture<T> onMain(Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        server.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(task.get());
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /** Whichever thread the pipeline ends on, the returned future completes back on the main thread; on failure, onFailure runs first. */
    private <T> CompletableFuture<T> finishOnMain(CompletableFuture<T> pipeline, Runnable mainCleanup,
                                                  Consumer<Throwable> onFailure) {
        CompletableFuture<T> result = new CompletableFuture<>();
        pipeline.whenComplete((value, error) -> {
            if (error != null) {
                onFailure.accept(error);
            }
            server.getScheduler().runTask(plugin, () -> {
                mainCleanup.run();
                if (error == null) {
                    result.complete(value);
                } else {
                    result.completeExceptionally(error);
                }
            });
        });
        return result;
    }

    private void requireMainThread() {
        if (!server.isPrimaryThread()) {
            throw new IllegalStateException("DisposableWorlds must be used on the main thread");
        }
    }
}
```

## `ArenaPool.java`

```java
package com.example.arena;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Pool of pre-generated idle worlds. Everything runs on the main thread; copying is async and handled by {@link DisposableWorlds},
 * whose futures always complete on the main thread, so no locks are needed here.
 *
 * <p>After a refill failure, back off for {@value #FAILURE_COOLDOWN_MS} ms, to avoid copying and spamming the log every 5 seconds when the template is broken.
 */
public final class ArenaPool {

    private static final long FAILURE_COOLDOWN_MS = 60_000L;

    private final Plugin plugin;
    private final DisposableWorlds worlds;
    private final Path template;
    private final int target;
    private final Deque<World> idle = new ArrayDeque<>();
    private int pending;
    private long failedAtMillis;
    private BukkitTask refillTask;

    public ArenaPool(Plugin plugin, DisposableWorlds worlds, Path template, int target) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.template = template;
        this.target = target;
    }

    public void start() {
        refillTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refill, 20L, 100L);
    }

    /** Returns empty when the pool is empty: the caller should queue the players rather than wait synchronously for a copy on the main thread. */
    public Optional<World> acquire() {
        return Optional.ofNullable(idle.pollFirst());
    }

    /** Returning after use = destroying (disposable worlds are not reused); the timer handles refilling. */
    public CompletableFuture<Void> release(World world) {
        return worlds.destroy(world);
    }

    public int idleCount() {
        return idle.size();
    }

    public void shutdown() {
        if (refillTask != null) {
            refillTask.cancel();
        }
        idle.clear();     // The worlds themselves are handled by DisposableWorlds.closeAllSync()
    }

    private void refill() {
        if (System.currentTimeMillis() - failedAtMillis < FAILURE_COOLDOWN_MS) {
            return;
        }
        while (idle.size() + pending < target) {
            pending++;
            worlds.createFromTemplate(template, new VoidChunkGenerator()).whenComplete((world, error) -> {
                pending--;                       // Main thread
                if (error == null) {
                    idle.addLast(world);
                    return;
                }
                failedAtMillis = System.currentTimeMillis();
                plugin.getLogger().log(Level.WARNING, "Arena pre-generation failed; pausing refill", error);
            });
        }
    }
}
```

## `ChangeRecorder.java`

```java
package com.example.arena;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Records the baseline of "which cells a match changed": key = {@link #key(Block)} (x/y/z packed into a long), value = the BlockData **before the first change**.
 * A cell changed ten times records only the first ({@code putIfAbsent}), so what is written back on restore is the state before the match started.
 *
 * <p>Everything runs on the main thread (event handlers and restore alike). Uses {@link EventPriority#MONITOR}:
 * the event is already certain to take effect, and the block has not actually been changed yet (place events take the old value from the replaced state).
 *
 * <p>Beyond {@link #MAX_CELLS} cells, the destruction was too large and the baseline is no longer trustworthy -> mark as overflowed,
 * and the caller falls back to the "world swapping" route.
 */
public final class ChangeRecorder implements Listener {

    public static final int MAX_CELLS = 500_000;

    // Same layout as vanilla BlockPos#asLong: 26 bits x | 26 bits z | 12 bits y (y -2048..2047).
    // Bukkit's Block#getBlockKey family is deprecated (it only encodes y -512..511), so the template packs its own keys.
    private static final int XZ_BITS = 26;
    private static final int Y_BITS = 12;
    private static final long XZ_MASK = (1L << XZ_BITS) - 1;
    private static final long Y_MASK = (1L << Y_BITS) - 1;

    private static final BlockFace[] ADJACENT = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final Map<UUID, Map<Long, BlockData>> baselines = new HashMap<>();
    private final Set<UUID> overflowed = new HashSet<>();

    public void track(World world) {
        baselines.putIfAbsent(world.getUID(), new HashMap<>());
    }

    public void untrack(World world) {
        baselines.remove(world.getUID());
        overflowed.remove(world.getUID());
    }

    public boolean overflowed(World world) {
        return overflowed.contains(world.getUID());
    }

    public int pending(World world) {
        Map<Long, BlockData> cells = baselines.get(world.getUID());
        return cells == null ? 0 : cells.size();
    }

    /** Take the current baseline (an immutable copy) and start recording the next round. */
    public Map<Long, BlockData> drain(World world) {
        Map<Long, BlockData> cells = baselines.get(world.getUID());
        if (cells == null) {
            return Map.of();
        }
        Map<Long, BlockData> snapshot = Map.copyOf(cells);
        baselines.put(world.getUID(), new HashMap<>());
        return snapshot;
    }

    // ---- Keys ----

    public static long key(Block block) {
        return ((block.getX() & XZ_MASK) << (XZ_BITS + Y_BITS))
                | ((block.getZ() & XZ_MASK) << Y_BITS)
                | (block.getY() & Y_MASK);
    }

    public static Block blockAt(World world, long key) {
        int x = (int) (key >> (XZ_BITS + Y_BITS));                       // arithmetic shifts restore the sign
        int z = (int) (key << (64 - XZ_BITS - Y_BITS) >> (64 - XZ_BITS));
        int y = (int) (key << (64 - Y_BITS) >> (64 - Y_BITS));
        return world.getBlockAt(x, y, z);
    }

    // ---- Recording ----

    private void record(Block block) {
        Map<Long, BlockData> cells = baselines.get(block.getWorld().getUID());
        if (cells != null) {
            put(block.getWorld(), cells, key(block), block.getBlockData());
        }
    }

    private void recordOriginal(Block position, BlockData original) {
        Map<Long, BlockData> cells = baselines.get(position.getWorld().getUID());
        if (cells != null) {
            put(position.getWorld(), cells, key(position), original);
        }
    }

    private void put(World world, Map<Long, BlockData> cells, long key, BlockData original) {
        if (cells.size() >= MAX_CELLS && !cells.containsKey(key)) {
            overflowed.add(world.getUID());
            return;
        }
        cells.putIfAbsent(key, original);
    }

    private void recordWithNeighbours(Block block) {
        record(block);
        for (BlockFace face : ADJACENT) {
            record(block.getRelative(face));      // Torches, doors, and plants drop when the block they are attached to disappears
        }
    }

    private void recordAll(List<Block> blocks) {
        for (Block block : blocks) {
            record(block);
        }
    }

    private void recordPiston(Block piston, BlockFace direction, List<Block> moved) {
        record(piston.getRelative(direction));
        for (Block block : moved) {
            record(block);
            record(block.getRelative(direction));
        }
    }

    // ---- Events ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        recordOriginal(event.getBlock(), event.getBlockReplacedState().getBlockData());
        if (event instanceof BlockMultiPlaceEvent multi) {       // Multi-cell blocks such as beds and doors
            for (BlockState replaced : multi.getReplacedBlockStates()) {
                recordOriginal(replaced.getBlock(), replaced.getBlockData());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        recordWithNeighbours(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        recordAll(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        recordAll(event.blockList());
    }

    /** Liquid flow: the cell flowed into. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        record(event.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        record(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        record(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        record(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        record(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        record(event.getBlock());
    }

    /** Falling sand, endermen carrying blocks, wither destruction, etc. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        record(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        recordPiston(event.getBlock(), event.getDirection(), event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        recordPiston(event.getBlock(), event.getDirection(), event.getBlocks());
    }
}
```

## `DebrisSweeper.java`

```java
package com.example.arena;

import org.bukkit.GameMode;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.minecart.ExplosiveMinecart;

import java.util.List;

/**
 * Leftover entity sweep: restoring blocks does not restore entities, so dropped items, arrows, and TNT minecarts would linger into the next match.
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@link #sweepAll}: sweep everything on reset, **including** shulker box items (the match is over, so players' items are not kept either)</li>
 *   <li>{@link #sweepStale}: periodic cleanup (during a match), removing only entities that have lived long enough with nobody nearby;
 *       <b>exempts</b> ignited TNT minecarts (one ignited from afar must not "light and do nothing") and shulker box items
 *       (when a shulker box is blown up its contents drop with it, and those are the players' items)</li>
 * </ul>
 * Scans only loaded entities and does not load chunks for cleanup. Call on the main thread.
 */
public final class DebrisSweeper {

    private static final List<Class<? extends Entity>> TYPES = List.of(
            Item.class, Projectile.class, ExplosiveMinecart.class, TNTPrimed.class,
            EnderCrystal.class, ExperienceOrb.class, FallingBlock.class, AreaEffectCloud.class);
    private static final long MILLIS_PER_TICK = 50L;

    public int sweepAll(World world) {
        int removed = 0;
        for (Entity entity : entities(world)) {
            entity.remove();
            removed++;
        }
        return removed;
    }

    public int sweepStale(World world, long ttlMillis, double safeRadius) {
        List<Player> present = world.getPlayers().stream()
                .filter(player -> player.getGameMode() != GameMode.SPECTATOR).toList();
        int removed = 0;
        for (Entity entity : entities(world)) {
            if (exempt(entity)) {
                continue;
            }
            long ageMillis = entity.getTicksLived() * MILLIS_PER_TICK;
            if (ageMillis >= ttlMillis && nearest(entity, present) >= safeRadius) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    private static boolean exempt(Entity entity) {
        if (entity instanceof ExplosiveMinecart cart && cart.isIgnited()) {
            return true;
        }
        return entity instanceof Item item && Tag.SHULKER_BOXES.isTagged(item.getItemStack().getType());
    }

    private static double nearest(Entity entity, List<Player> players) {
        double best = Double.POSITIVE_INFINITY;
        for (Player player : players) {
            best = Math.min(best, player.getLocation().distanceSquared(entity.getLocation()));
        }
        return Math.sqrt(best);
    }

    private static List<Entity> entities(World world) {
        return List.copyOf(world.getEntitiesByClasses(TYPES.toArray(new Class<?>[0])));
    }
}
```

## `ArenaResetService.java`

```java
package com.example.arena;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * In-place reset: write the cells recorded by {@link ChangeRecorder} back **within a budget** each tick, then sweep leftover entities.
 *
 * <p>The budget has two layers: at most {@code maxBlocksPerTick} cells per tick, plus a {@code maxNanosPerTick} time cap
 * (the clock is checked once every 64 cells). Writing too much at once slows the whole server's tick, not just this arena.
 *
 * <p>Restore uses {@code setBlockData(data, false)} (no block physics), so it produces no further events
 * and is not recorded again by {@link ChangeRecorder}. Move players out of the arena before restoring. Call on the main thread.
 */
public final class ArenaResetService {

    private final Plugin plugin;
    private final ChangeRecorder recorder;
    private final DebrisSweeper sweeper;
    private final int maxBlocksPerTick;
    private final long maxNanosPerTick;
    private final Set<UUID> running = new HashSet<>();

    public ArenaResetService(Plugin plugin, ChangeRecorder recorder, DebrisSweeper sweeper,
                             int maxBlocksPerTick, long maxNanosPerTick) {
        this.plugin = plugin;
        this.recorder = recorder;
        this.sweeper = sweeper;
        this.maxBlocksPerTick = maxBlocksPerTick;
        this.maxNanosPerTick = maxNanosPerTick;
    }

    /** @return the number of restored cells on completion; completes exceptionally if the baseline overflowed or a reset is already running (swap worlds instead). */
    public CompletableFuture<Integer> reset(World world) {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("reset must be called on the main thread");
        }
        CompletableFuture<Integer> done = new CompletableFuture<>();
        if (recorder.overflowed(world)) {
            done.completeExceptionally(new IllegalStateException("Baseline overflowed; recycle the world instead"));
            return done;
        }
        if (!running.add(world.getUID())) {
            done.completeExceptionally(new IllegalStateException("Reset already running for " + world.getName()));
            return done;
        }
        Map<Long, BlockData> baseline = recorder.drain(world);
        // Sorting by key = grouping by coordinates, writing the same chunk consecutively, which is faster than HashMap's random order
        long[] keys = baseline.keySet().stream().mapToLong(Long::longValue).sorted().toArray();
        plugin.getServer().getScheduler().runTaskTimer(plugin, new Job(world, keys, baseline, done), 1L, 1L);
        return done;
    }

    private final class Job implements Consumer<BukkitTask> {
        private final World world;
        private final long[] keys;
        private final Map<Long, BlockData> baseline;
        private final CompletableFuture<Integer> done;
        private int cursor;

        private Job(World world, long[] keys, Map<Long, BlockData> baseline, CompletableFuture<Integer> done) {
            this.world = world;
            this.keys = keys;
            this.baseline = baseline;
            this.done = done;
        }

        @Override
        public void accept(BukkitTask task) {
            if (plugin.getServer().getWorld(world.getUID()) == null) {
                finish(task, new IllegalStateException("World unloaded during reset"));
                return;
            }
            long deadline = System.nanoTime() + maxNanosPerTick;
            int written = 0;
            while (cursor < keys.length && written < maxBlocksPerTick) {
                if ((cursor & 63) == 0 && System.nanoTime() > deadline) {
                    break;
                }
                long key = keys[cursor++];
                Block block = ChangeRecorder.blockAt(world, key);
                BlockData original = baseline.get(key);
                if (!block.getBlockData().equals(original)) {
                    block.setBlockData(original, false);
                    written++;
                }
            }
            if (cursor >= keys.length) {
                sweeper.sweepAll(world);          // Sweep entities only after blocks are restored, so new drops do not appear after the sweep
                finish(task, null);
            }
        }

        private void finish(BukkitTask task, Throwable error) {
            task.cancel();
            running.remove(world.getUID());
            if (error == null) {
                done.complete(keys.length);
            } else {
                done.completeExceptionally(error);
            }
        }
    }
}
```

## `ArenaPlugin.java`

```java
package com.example.arena;

import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

public final class ArenaPlugin extends JavaPlugin {

    private static final int MAX_ARENAS = 9_999;
    private static final int POOL_SIZE = 2;
    private static final int BLOCKS_PER_TICK = 2_000;
    private static final long NANOS_PER_TICK = 2_000_000L;     // 2 ms

    private DisposableWorlds worlds;
    private ArenaPool pool;
    private ChangeRecorder recorder;
    private ArenaResetService resets;

    @Override
    public void onEnable() {
        worlds = new DisposableWorlds(this, new ArenaNames(MAX_ARENAS), new ArenaRules(getLogger()));
        worlds.purgeStaleAsync();

        Path template = getDataFolder().toPath().resolve("templates").resolve("duel_map");
        pool = new ArenaPool(this, worlds, template, POOL_SIZE);
        pool.start();

        recorder = new ChangeRecorder();
        getServer().getPluginManager().registerEvents(recorder, this);
        resets = new ArenaResetService(this, recorder, new DebrisSweeper(), BLOCKS_PER_TICK, NANOS_PER_TICK);
    }

    @Override
    public void onDisable() {
        pool.shutdown();
        worlds.closeAllSync();
    }

    public DisposableWorlds worlds() {
        return worlds;
    }

    public ArenaPool pool() {
        return pool;
    }

    public ChangeRecorder recorder() {
        return recorder;
    }

    public ArenaResetService resets() {
        return resets;
    }
}
```
