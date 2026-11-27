# Paper Folia Scheduler — Examples

## Example 1: Port a BukkitScheduler plugin

**Input:**
```
our plugin uses runTask / runTaskLater / runTaskTimer / runTaskAsynchronously everywhere; make it Folia compatible without keeping two code paths
```

**Output — replace each call by what the code touches, then add `folia-supported: true`:**

| Before (`BukkitScheduler`) | Touches | After (`Tasks`) |
|------|------|------|
| `runTask(plugin, () -> player.sendMessage(...))` | a player | `tasks.entity(player, () -> player.sendMessage(...), () -> { })` |
| `runTaskLater(plugin, () -> block.setType(STONE), 40)` | a block | `tasks.atLater(block.getLocation(), 40, () -> block.setType(Material.STONE))` |
| `runTaskTimer(plugin, this::broadcastScores, 0, 200)` | no world state | `tasks.globalTimer(1, 200, t -> broadcastScores())` (delay 0 → 1) |
| `runTaskAsynchronously(plugin, this::saveAll)` | files | `tasks.async(this::saveAll)` |
| `runTask(plugin, () -> Bukkit.dispatchCommand(console, "say hi"))` | console | `tasks.global(() -> Bukkit.dispatchCommand(console, "say hi"))` |
| `player.teleport(spawn)` | an entity moving | `player.teleportAsync(spawn).thenAccept(ok -> tasks.entity(player, () -> ..., () -> { }))` |
| `for (Player p : Bukkit.getOnlinePlayers()) p.setHealth(20)` on one thread | many players | one `tasks.entity(p, ...)` per player |

```java
import com.example.scheduling.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Before: one runTaskTimer healed everyone. After: a global timer fans out to each player's own thread. */
final class HealAll {

    static void start(JavaPlugin plugin, Tasks tasks) {
        tasks.globalTimer(1L, 20L * 60, timer -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                tasks.entity(player, () -> player.setHealth(20.0), () -> { });
            }
        });
    }
}
```

Checklist after porting: no `getScheduler()` call left (`grep -n "getScheduler()" -r src`), every delay ≥ 1, shared maps concurrent, `folia-supported: true` in `plugin.yml`.

---

## Example 2: Arena countdown that works on Folia

**Input:**
```
arena start: 10 second countdown shown to the players in the arena, then teleport them to the spawn points; players may leave during the countdown
```

**Output — a global timer drives the count; each step goes to the players' own threads; teleports are async:**

```java
import com.example.scheduling.Tasks;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class ArenaCountdown {

    private final Tasks tasks;
    private final Map<UUID, Player> players = new ConcurrentHashMap<>();   // leave events arrive on other threads

    ArenaCountdown(Tasks tasks, List<Player> joined) {
        this.tasks = tasks;
        joined.forEach(p -> players.put(p.getUniqueId(), p));
    }

    void leave(Player player) {
        players.remove(player.getUniqueId());
    }

    void start(Location spawn) {
        AtomicInteger seconds = new AtomicInteger(10);
        tasks.globalTimer(1L, 20L, (ScheduledTask timer) -> {
            int left = seconds.getAndDecrement();
            for (Player player : players.values()) {
                tasks.entity(player, () -> {
                    if (left > 0) {
                        player.sendActionBar(Component.text("Starting in " + left));
                    } else {
                        player.teleportAsync(spawn);                       // never the blocking teleport on Folia
                    }
                }, () -> players.remove(player.getUniqueId()));          // player quit: drop them
            }
            if (left <= 0) {
                timer.cancel();
            }
        });
    }
}
```

Points:
- The countdown itself touches no world state, so it lives on the global scheduler; everything per player goes through the player's scheduler
- `players` is a `ConcurrentHashMap`: on Folia `leave` is called from the leaving player's region thread while the timer runs on the global thread
- The `retired` callback removes players who quit between ticks, so the next step skips them
