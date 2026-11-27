package com.example.probe.clock;

import com.example.clock.TimeLock;
import com.example.clock.WorldTime;
import com.example.probe.Probe;
import io.papermc.paper.world.WorldClock;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * paper-world-clock (classes in this jar, 26.3 only): clock pause/rate/lock on the first start,
 * and on the second start what persisted across the restart.
 */
public final class ClockProbe extends Probe {

    private static final String ARENA = "clock_arena";

    @Override
    protected long startDelay() {
        return 20L;
    }

    @Override
    protected void start() throws Exception {
        Path marker = getDataFolder().toPath().resolve("restarted");
        if (Files.exists(marker)) {
            secondStart();
        } else {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "x");
            firstStart();
        }
    }

    private void firstStart() {
        World world = Bukkit.getWorlds().get(0);
        World arena = new WorldCreator(ARENA).createWorld();
        WorldClock clock = world.getDefaultClock();
        world.setClockPaused(clock, true);
        world.setClockRate(clock, 4.0f);
        long t0 = world.getFullTime();
        later(40L, () -> {
            check("paused clock with rate 4 does not advance", world.getFullTime() == t0);
            world.setClockPaused(clock, false);
            check("setDayLength(40 min) -> rate 0.5", WorldTime.setDayLength(world, Duration.ofMinutes(40)) && Math.abs(world.getClockRate(clock) - 0.5f) < 0.001);
            check("freeze arena at 6000", WorldTime.freeze(arena, 6000L) && arena.isClockPaused(arena.getDefaultClock()));
            check("nether has no clock -> freeze returns false", !WorldTime.freeze(Bukkit.getWorlds().get(1), 1000L));
            getServer().getPluginManager().registerEvents(new TimeLock(Set.of(world.getName()), Set.of()), this);
            long t = world.getFullTime();
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "time add 1000");
            check("TimeLock blocks /time add in locked world", world.getFullTime() - t < 10);
            arena.save();
            world.save();
            finish();
        });
    }

    private void secondStart() {
        World world = Bukkit.getWorlds().get(0);
        World arena = new WorldCreator(ARENA).createWorld();
        WorldClock clock = world.getDefaultClock();
        log("after restart: world rate=" + world.getClockRate(clock) + " paused=" + world.isClockPaused(clock)
                + " | arena paused=" + arena.isClockPaused(arena.getDefaultClock()) + " arena time=" + arena.getFullTime(arena.getDefaultClock()));
        check("restart: world day-length rate 0.5 persisted", Math.abs(world.getClockRate(clock) - 0.5f) < 0.001);
        check("restart: arena still paused", arena.isClockPaused(arena.getDefaultClock()));
        check("restart: arena time still 6000", arena.getFullTime(arena.getDefaultClock()) == 6000L);
        finish();
    }
}
