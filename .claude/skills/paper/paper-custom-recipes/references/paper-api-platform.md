<!-- Generated from Skills/paper-api/PLATFORM.md by scripts/sync-skill-references.mjs. Do not edit; change the source and re-run the script. -->

# Paper API Platform / Paper API 平台

Skills under `Skills/paper/` use only the **Paper API** (`paper-api` compileOnly); they need no Paperweight and never touch `net.minecraft` / `org.bukkit.craftbukkit`.
When NMS is needed, use the skills in `Skills/nms/` and [`Skills/paper-nms/PLATFORM.md`](https://github.com/MrPippi/MJP-Paper-Skills/blob/main/Skills/paper-nms/PLATFORM.md) instead.

- **MC versions**: **1.21.11** and **26.3** (both compile-verified; templates default to 26.3, differences are marked with an end-of-line `// @1.21.11:`, same rules as the paper-nms platform)
- **Java**: 1.21.11 → 21; 26.3 → 25
- **Build tool**: Gradle 9.7.1+ recommended (Groovy DSL examples; Kotlin DSL is equivalent; 9.8.1 verified, same baseline as the paper-nms platform)
- **Javadoc**: https://jd.papermc.io/paper/26.3/ | https://jd.papermc.io/paper/1.21.11/

---

## 1. `build.gradle` Template

```groovy
plugins {
    id 'java'
    id 'com.gradleup.shadow' version '9.6.1' // only needed when bundling third-party libraries
}

group = 'com.example'
version = '1.0.0'

repositories {
    mavenCentral()
    maven { url = 'https://repo.papermc.io/repository/maven-public/' }
    // Add the following according to the soft dependencies actually used
    maven { url = 'https://jitpack.io' }                                  // VaultAPI
    maven { url = 'https://repo.extendedclip.com/releases/' }             // PlaceholderAPI
    maven { url = 'https://repo.codemc.io/repository/maven-releases/' }   // PacketEvents
    maven { url = 'https://repo.dmulloy2.net/repository/public/' }        // ProtocolLib
}

dependencies {
    compileOnly 'io.papermc.paper:paper-api:26.3.build.258-stable' // 1.21.11: '1.21.11-R0.1-SNAPSHOT'

    // Soft dependencies (provided by the corresponding plugins on the server; must not be bundled into the jar)
    compileOnly 'com.github.MilkBowl:VaultAPI:1.7.1'
    compileOnly 'me.clip:placeholderapi:2.11.6'
    compileOnly 'com.github.retrooper:packetevents-spigot:2.13.0'
    compileOnly 'com.comphenix.protocol:ProtocolLib:5.3.0'

    // SQLite: contains JNI native libraries, do not shade; have the server download it via libraries: in plugin.yml instead
    compileOnly 'org.xerial:sqlite-jdbc:3.49.1.0'
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25) // 1.21.11: 21
}

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
    options.release.set(25) // 1.21.11: 21
}
```

| Item | 26.3 (default) | 1.21.11 |
|------|-------------|---------|
| `paper-api` version | `26.3.build.258-stable` | `1.21.11-R0.1-SNAPSHOT` |
| Java toolchain / `options.release` | `25` | `21` |
| `api-version` in `plugin.yml` / `paper-plugin.yml` | `'26.3'` | `'1.21.11'` |

> The dependency versions in the table above are the ones actually used by BlockoSMP / Bydsmp and compile-verified in this repository.

---

## 2. `plugin.yml` Template (regular plugin)

```yaml
name: Example
version: '${version}'
main: com.example.ExamplePlugin
api-version: '26.3'
softdepend: [Vault, PlaceholderAPI, packetevents]
libraries:
  - org.xerial:sqlite-jdbc:3.49.1.0
commands:
  example:
    description: Example command
    permission: example.use
permissions:
  example.use:
    default: true
```

- When a Paper bootstrap is needed (e.g. registering a Dialog during the registry phase), switch to `paper-plugin.yml` + `PluginBootstrap`; see `paper-dialog-ui`.
- Always declare soft dependencies in `softdepend`, and check them in code with `getPlugin(name) != null`; see `paper-softdepend-hook`.

---

## 3. Common Rules

| Rule | Description |
|------|------|
| Threading | Bukkit API on the main thread only; IO / JDBC / HTTP always async; re-validate state after returning to the main thread. See [`_shared/paper-threading.md`](paper-threading.md) |
| Messages | Adventure / MiniMessage; do not use `ChatColor` or `§` |
| Config | Parse into an immutable object at startup; on reload, build a new object and swap it atomically; see `paper-config-lang` |
| Cross-plugin | Interoperate only through interfaces published via ServicesManager; see `paper-service-api` |
| Soft dependencies | Separate Hook / Bridge to avoid class loading failures; see `paper-softdepend-hook` |
| Project rules take precedence | Before generating code, read the target project's `CLAUDE.md` / `docs/` rules (naming, layering, testing approach); when a template conflicts with project rules, the project wins |

---

## 3a. Spigot Compatibility

The templates target Paper. Spigot-API 26.3 contains only the `org.bukkit` and `org.spigotmc` packages (checked against `hub.spigotmc.org/javadocs/spigot/`). Anything outside them is unavailable on Spigot:

| Paper-only API | Used by |
|------|------|
| Adventure / MiniMessage (`net.kyori.*`) — Spigot has no native Adventure | almost every skill (messages) |
| `io.papermc.paper.*` (Dialog, Brigadier `Commands`, lifecycle, registry events, data components, `AsyncChatEvent`, world border events, `Tick`) | `paper-dialog-ui`, `paper-brigadier-command`, `paper-chest-gui`, `paper-client-side-effects`, `paper-discord-bridge`, `paper-registry-events`, `paper-data-components` |
| `Entity#teleportAsync`, `World#getChunkAtAsync`, `ItemStack#editMeta`, `Bukkit#getMinecraftVersion`, `PlayerKickEvent.Cause`, `org.bukkit.GameRules` | `paper-safe-teleport`, `paper-disposable-world`, `paper-combat-tag`, `paper-chest-gui`, `paper-packetevents-filter` |

Each `SKILL.md` states its Spigot status in its version section with one of three levels:

- **Paper only**: the core relies on Paper API.
- **Core portable**: the logic works on Spigot after you replace Adventure messages and the listed Paper calls.
- **Works on Spigot**: no Paper API in the template.

Paper stopped importing new Spigot API after its 2024-12-13 hard fork, so APIs added to Spigot since then (for example `ImbueRecipe`, `DyeRecipe`) may not exist on Paper, and the reverse also holds.

---

## 4. Skill List

| Skill | Category | Purpose |
|------|------|------|
| `paper-dialog-ui` | paper-ui | Paper Dialog API UI |
| `paper-chest-gui` | paper-ui | InventoryHolder chest GUI |
| `paper-menu-type-gui` | paper-ui | Portable vanilla menus with MenuType |
| `paper-sqlite-repository` | paper-data | SQLite Repository + schema migration + single writer thread |
| `paper-config-lang` | paper-data | Immutable config object + MiniMessage language files |
| `paper-datapack-bundling` | paper-data | Data packs bundled in the plugin jar |
| `paper-service-api` | paper-integration | ServicesManager cross-plugin API |
| `paper-softdepend-hook` | paper-integration | Soft-dependency Hook / Bridge + PlaceholderAPI expansion |
| `paper-embedded-http` | paper-integration | Embedded HTTP API (JDK HttpServer) |
| `paper-discord-bridge` | paper-integration | Two-way Discord chat bridge with only the JDK |
| `paper-packetevents-filter` | paper-network | PacketEvents / ProtocolLib packet filtering |
| `paper-client-side-effects` | paper-network | Effects shown to a single player only (virtual world border, time, weather, hidden players) |
| `paper-combat-tag` | paper-gameplay | PvP combat tagging |
| `paper-safe-teleport` | paper-gameplay | Safe landing-spot search and async teleport (RTP) |
| `paper-economy-ledger` | paper-gameplay | Multi-currency ledger + Vault provider |
| `paper-registry-events` | paper-gameplay | Custom enchantments / damage types from a bootstrapper, vanilla changes, tags |
| `paper-data-components` | paper-gameplay | Data component API: food, weapons, gliding, tooltips, PDC item ids |
| `paper-custom-recipes` | paper-gameplay | Crafting, transmute and brewing recipes |
| `paper-brigadier-command` | paper-command | Brigadier commands (LifecycleEvents.COMMANDS) |
| `paper-disposable-world` | paper-world | Disposable worlds / arena reset |
| `paper-world-clock` | paper-world | Per-world clock control (26.3 only) |
