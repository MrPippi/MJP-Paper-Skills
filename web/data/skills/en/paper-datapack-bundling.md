# Paper Datapack Bundling

## Purpose

Recipes, advancements, functions, loot tables and worldgen are easier to write as data pack JSON than as Java. A Paper plugin can **ship the data pack inside its jar** and register it during bootstrap, instead of asking admins to copy it into `world/datapacks/`. The pack id is `<plugin name>/<id>`; `autoEnableOnServerStart` defaults to **false**, so without it the pack is only discovered, not enabled; an invalid or incompatible pack makes `discoverPack` return `null`.

---

## Platform Requirements

- Paper 1.21.11 / 26.3 (`DatapackRegistrar`, `DatapackManager` and `DATAPACK_DISCOVERY` have identical signatures; compile-verified on both; runtime-verified on Paper 26.3)
- Data pack formats: 1.21.11 = 94.1, 26.2 = 107.1, 26.3 = 121.0; both versions use `min_format` / `max_format` in `pack.mcmeta`, and the range `[94, 1]`..`121` covers both
- Crafting recipes, functions and function tags are written the same way on both versions; 26.3 renamed many loot table, predicate, advancement trigger and worldgen fields, so ship those per version
- Java 21 (1.21.11) / 25 (26.3)
- **Spigot**: Paper only — `PluginBootstrap` and `DATAPACK_DISCOVERY` are Paper API; on Spigot the admin installs the pack into `world/datapacks/`

---

## Generated Code

### pack.mcmeta

```json
{ "pack": { "description": "Example content", "min_format": [94, 1], "max_format": 121 } }
```

### PackBootstrap.java

```java
context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
    URL root = PackBootstrap.class.getResource("/datapack");
    DiscoveredDatapack pack = event.registrar().discoverPack(root.toURI(), "content", config -> config
        .title(Component.text("Example content"))
        .autoEnableOnServerStart(true)              // default false
        .position(false, Datapack.Position.TOP));
    if (pack == null) problems.add("pack rejected");
});
```

### PackPlugin.java (verify it is enabled)

```java
Datapack pack = getServer().getDatapackManager().getPack(getPluginMeta().getName() + "/content");
if (pack == null || !pack.isEnabled()) getLogger().warning("bundled data pack is not enabled");
```

---

## Rules

- Bootstrap never throws; record why `discoverPack` returned `null` and report it in `onEnable`
- If `processResources` expands `${...}`, restrict it to `paper-plugin.yml` so the JSON files stay untouched
- For per-version folders choose with `ServerBuildInfo.buildInfo().minecraftVersionId()`
- Runtime-verified on Paper 26.3: `CustomContent/content` enabled, its recipe craftable, the `#minecraft:load` function ran, and the recipe survives `/minecraft:reload`

---

## Thread Safety

- The discovery handler runs during bootstrap: no Bukkit server, only resolve the resource and call the registrar
- Query `DatapackManager` on the main thread (`onEnable`, commands)
