# paper-datapack-bundling Examples

## Example 1: One folder per Minecraft version

**Input:**
```
contents: a loot table modification (fields renamed in 26.3)
versions: 1.21.11, 26.3
```

**Output — ship `datapack-1.21.11/` and `datapack-26.3/` in the jar (each with its own `pack.mcmeta` format), and pick one in bootstrap:**
```java
package com.example.datapack;

import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.net.URL;
import java.util.Map;

/** paper-plugin.yml: bootstrapper: com.example.datapack.VersionedPackBootstrap */
public final class VersionedPackBootstrap implements PluginBootstrap {

    /** MC version id -> folder in the jar. Unlisted versions get no pack (and a log line) instead of a broken one. */
    private static final Map<String, String> FOLDERS = Map.of(
            "1.21.11", "/datapack-1.21.11",
            "26.3", "/datapack-26.3");

    @Override
    public void bootstrap(BootstrapContext context) {
        ComponentLogger log = context.getLogger();
        String version = ServerBuildInfo.buildInfo().minecraftVersionId();
        String folder = FOLDERS.get(version);
        if (folder == null) {
            log.warn("No bundled data pack for Minecraft {}; content disabled", version);
            return;
        }
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
            try {
                URL root = VersionedPackBootstrap.class.getResource(folder);
                if (root == null || event.registrar().discoverPack(root.toURI(), "content",
                        config -> config.autoEnableOnServerStart(true)) == null) {
                    log.error("Bundled data pack {} could not be registered", folder);
                }
            } catch (Exception e) {
                log.error("Bundled data pack {} failed", folder, e);
            }
        });
    }
}
```

`pack.mcmeta` per folder:

| Folder | `min_format` | `max_format` |
|------|------|------|
| `datapack-1.21.11/` | `[94, 1]` | `94` |
| `datapack-26.3/` | `121` | `121` |

---

## Example 2: Let the admin switch the bundled pack off

**Input:**
```
some servers already have their own recipes; allow disabling the bundled pack without removing the plugin
```

**Output — the config file is not loaded during bootstrap, so read a marker file from the plugin data folder:**
```java
package com.example.datapack;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.net.URL;
import java.nio.file.Files;

/** Create plugins/CustomContent/disable-datapack to skip the pack on the next start. */
public final class OptionalPackBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(BootstrapContext context) {
        if (Files.exists(context.getDataDirectory().resolve("disable-datapack"))) {
            context.getLogger().info("Bundled data pack disabled by disable-datapack");
            return;
        }
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
            try {
                URL root = OptionalPackBootstrap.class.getResource("/datapack");
                if (root != null) {
                    event.registrar().discoverPack(root.toURI(), "content", config -> config.autoEnableOnServerStart(true));
                }
            } catch (Exception e) {
                context.getLogger().error("Bundled data pack failed", e);
            }
        });
    }
}
```

- The marker is read only at startup (bootstrap); restart the server after creating or deleting it
- Admins can still inspect it with `/datapack list`; the id is `<plugin name>/content`
