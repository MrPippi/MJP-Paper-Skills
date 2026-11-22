# Paper World Clock

## Purpose

Minecraft 26.1 replaced the single "day time" with **world clocks** (`minecraft:overworld`, `minecraft:the_end`), and Paper 26.3 exposes them: each world's clock can be paused, run at a different rate and set. A lobby stuck at noon, a 40-minute day, an arena where `/time` does nothing, or night skipping only in the survival world no longer need a repeating task fighting the day cycle.

---

## Platform Requirements

- **Paper 26.3 only**: `WorldClock`, `WorldClocks`, the clock methods on `World` and `ClockTimeSkipEvent` do not exist on 1.21.11; every template is marked `// @only 26.3`
- 1.21.11 alternative: the `advance_time` game rule plus `World#setTime` and a repeating task
- Java 25
- **Spigot**: Paper only — Spigot-API 26.3 has no clock types

---

## Generated Code

### WorldTime.java

```java
WorldClock clock = world.getDefaultClock();   // null in the Nether
world.setFullTime(clock, 6000);                // set the time first
world.setClockPaused(clock, true);             // then pause
world.setClockRate(clock, 0.5f);               // 40-minute day (20 min / 40 min)
```

### TimeLock.java

```java
@EventHandler(ignoreCancelled = true)
public void onTimeSkip(TimeSkipEvent event) {        // 26.3: TimeSkipEvent extends ClockTimeSkipEvent and adds getWorld()
    if (locked.contains(event.getWorld().getName())
            && event.getSkipReason() != ClockTimeSkipEvent.SkipReason.NIGHT_SKIP) {
        event.setCancelled(true);                     // blocks /time and setTime
    }
}
```

---

## Rules

- Always use `world.getDefaultClock()` and handle `null` (the Nether has no clock)
- Worlds of the same type share one `WorldClock` key, but pause, rate and time are **per world**
- Apply settings in `onEnable` and in `WorldLoadEvent`
- Runtime-verified on Paper 26.3 build 258: a paused world advanced 0 ticks in 40, rate 4 advanced 160, a paused clock with a rate still did not move; a cancelled `/time add` left the time unchanged; after a restart the 0.5 rate and an arena paused at 6000 were kept

---

## Thread Safety

- Clock methods and events are main-thread only
- The state is saved with the world; re-applying it on every load is idempotent
