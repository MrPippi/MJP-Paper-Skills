# paper-menu-type-gui Examples

## Example 1: `/workbench`, `/anvil`, `/trader` commands

**Input:**
```
commands: /workbench (example.workbench), /anvil (example.anvil), /trader
anvil_max_cost: 100
```

**Output — Brigadier registration (see `paper-brigadier-command`); each command only opens a screen for a player executor:**
```java
package com.example.workstations;

import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Consumer;

public final class WorkstationsPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        PortableAnvil anvil = new PortableAnvil(100);
        getServer().getPluginManager().registerEvents(anvil, this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register(Commands.literal("workbench")
                    .requires(source -> source.getSender().hasPermission("example.workbench"))
                    .executes(context -> openFor(context.getSource().getExecutor(), Workstations::crafting))
                    .build(), "Open a portable crafting table");
            commands.register(Commands.literal("anvil")
                    .requires(source -> source.getSender().hasPermission("example.anvil"))
                    .executes(context -> openFor(context.getSource().getExecutor(), anvil::open))
                    .build(), "Open a portable anvil");
            commands.register(Commands.literal("trader")
                    .executes(context -> openFor(context.getSource().getExecutor(), TradeMenu::open))
                    .build(), "Open the trader");
        });
    }

    /** Commands run on the main thread, so the screen can be opened directly. */
    private static int openFor(org.bukkit.entity.Entity executor, Consumer<Player> open) {
        if (executor instanceof Player player) {
            open.accept(player);
            return Command.SINGLE_SUCCESS;
        }
        return 0;
    }
}
```

---

## Example 2: Remote access to a real furnace

**Input:**
```
players link a furnace with a command and can open it from anywhere
```

**Output — `location(...)` binds the screen to that block entity, so the furnace keeps smelting and its contents are saved; `checkReachable(false)` keeps the screen open at any distance:**
```java
package com.example.workstations;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.FurnaceView;

public final class RemoteFurnace {

    private RemoteFurnace() {
    }

    /** Returns false when the linked block is gone or its chunk is not loaded. */
    public static boolean open(Player player, Location furnace) {
        if (furnace.getWorld() == null || !furnace.isChunkLoaded()
                || furnace.getBlock().getType() != Material.FURNACE) {
            return false;
        }
        FurnaceView view = MenuType.FURNACE.builder()
                .location(furnace)
                .checkReachable(false)
                .title(Component.text("Linked Furnace"))
                .build(player);
        player.openInventory(view);
        return true;
    }
}
```

- If the block at the location is not a furnace, Paper silently falls back to a temporary furnace that never smelts; the type check above prevents that
- An unloaded chunk would have to be loaded first; refusing is simpler than loading chunks on demand
