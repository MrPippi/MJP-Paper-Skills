# Paper Display Entities — Examples

## Example 1: Leaderboard hologram

**Input:**
```
a hologram at the lobby showing the top 5 players by kills, refreshed every 30 seconds; the kill counts come from our database
```

**Output — compute off the tick threads, then update the live entity in place:**

```java
import com.example.hologram.HologramSpec;
import com.example.hologram.Holograms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class KillsLeaderboard {

    record Entry(String name, int kills) {
    }

    static void start(Plugin plugin, Holograms holograms, Location lobby, Supplier<List<Entry>> topFive) {
        holograms.define(HologramSpec.at("kills", lobby, Component.text("Loading...")));
        plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> {
            List<Entry> top = topFive.get();                               // database query: async
            holograms.setText("kills", render(top));                       // applied on the entity's thread
        }, 0, 30, TimeUnit.SECONDS);
    }

    private static Component render(List<Entry> top) {
        TextComponent.Builder text = Component.text().append(Component.text("Top kills", NamedTextColor.GOLD));
        for (int i = 0; i < top.size(); i++) {
            Entry e = top.get(i);
            text.appendNewline().append(Component.text((i + 1) + ". " + e.name() + " - " + e.kills()));
        }
        return text.build();
    }
}
```

Points:
- One `TextDisplay` with newlines instead of one entity per line: no spacing math, one update
- `setText` replaces only the text: the entity keeps its UUID, so clients see the text change, not a flicker
- The definition keeps the latest text, so a respawn after a chunk reload shows current data immediately

---

## Example 2: Shop showcase and a quest marker

**Input:**
```
above each shop chest a slowly spinning item, and a "!" marker above the quest NPC that only players with the quest can see
```

**Output — `ItemSpinner` for the showcase, `PrivateHologram` for the marker, both spawned on the location's region:**

```java
import com.example.hologram.ItemSpinner;
import com.example.hologram.PrivateHologram;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ShopDecor {

    private final Plugin plugin;
    private final Map<UUID, TextDisplay> markers = new ConcurrentHashMap<>();

    ShopDecor(Plugin plugin) {
        this.plugin = plugin;
    }

    void showcase(Block chest, ItemStack item) {
        Location above = chest.getLocation().add(0.5, 1.4, 0.5);
        plugin.getServer().getRegionScheduler().run(plugin, above,
                task -> ItemSpinner.spawn(plugin, above, item, 20));        // a quarter turn per second, smooth on the client
    }

    void questMarker(Player player, Location npcHead) {
        Location at = npcHead.clone().add(0, 0.6, 0);
        plugin.getServer().getRegionScheduler().run(plugin, at, task -> {
            TextDisplay marker = PrivateHologram.show(plugin, player, at, Component.text("!", NamedTextColor.YELLOW));
            TextDisplay old = markers.put(player.getUniqueId(), marker);
            if (old != null) {
                old.getScheduler().run(plugin, t -> old.remove(), null);
            }
        });
    }

    void questDone(Player player) {
        TextDisplay marker = markers.remove(player.getUniqueId());
        if (marker != null) {
            marker.getScheduler().run(plugin, t -> marker.remove(), null);
        }
    }
}
```

Points:
- The showcase sends one transformation per second; the client fills in the frames, so 20 spinning items cost almost nothing
- Each player with the quest gets their own hidden display; the others never receive it
- Remove markers when the quest ends and on quit, or they stay until the chunk unloads (they are not persistent)
