# paper-world-clock Examples

## Example 1: Short nights, normal days

**Input:**
```
world: survival
day: 20 min (vanilla), night: 5 min
```

**Output — switch the clock rate when the time of day crosses dusk and dawn; a 100-tick check is precise enough:**
```java
// @only 26.3
package com.example.clock;

import io.papermc.paper.world.WorldClock;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Night (13000..23000) runs 2x faster: vanilla night is 10 min, so it takes 5. */
public final class ShortNights {

    private static final long DUSK = 13000L;
    private static final long DAWN = 23000L;
    private static final float NIGHT_RATE = 2.0f;

    private ShortNights() {
    }

    public static BukkitTask start(Plugin plugin, World world) {
        return plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            WorldClock clock = world.getDefaultClock();
            if (clock == null || world.isClockPaused(clock)) {
                return;
            }
            long time = Math.floorMod(world.getFullTime(clock), 24000L);
            float wanted = time >= DUSK && time < DAWN ? NIGHT_RATE : 1.0f;
            if (world.getClockRate(clock) != wanted) {
                world.setClockRate(clock, wanted);
            }
        }, 0L, 100L);
    }
}
```

- Cancel the returned task in `onDisable` and reset the rate to `1.0f` there, or the world keeps the night rate after the plugin is removed

---

## Example 2: Freeze an arena at sunset for the length of a match

**Input:**
```
arena world: arena_1, match start -> time 12000 frozen, match end -> clock runs again
```

**Output:**
```java
// @only 26.3
package com.example.clock;

import org.bukkit.World;

public final class ArenaClock {

    private static final long SUNSET = 12000L;

    private ArenaClock() {
    }

    public static void onMatchStart(World arena) {
        WorldTime.freeze(arena, SUNSET);       // false for clock-less worlds; arenas are overworld-type here
    }

    public static void onMatchEnd(World arena) {
        WorldTime.unfreeze(arena);
    }
}
```

- Only the arena world is affected: pause and rate are stored per world even though every overworld-type world uses the `minecraft:overworld` clock
- Combine with `TimeLock` so `/time set` from staff cannot move the frozen sunset during the match
