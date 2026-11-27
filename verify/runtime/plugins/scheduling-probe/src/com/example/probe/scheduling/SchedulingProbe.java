package com.example.probe.scheduling;

import com.example.probe.Probe;
import com.example.scheduling.BlockRegen;
import com.example.scheduling.Platform;
import com.example.scheduling.Tasks;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Zombie;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * paper-folia-scheduler (template classes in this jar), the same jar on Paper and on Folia: which thread each
 * scheduler runs on, delay clamping, retired callbacks, async hops, region-scheduled block restores, teleportAsync
 * and Tasks.cancelAll. Checks are logged from whichever thread the callback runs on.
 */
public final class SchedulingProbe extends Probe {

    private Tasks tasks;

    @Override
    protected void start() {
        tasks = new Tasks(this);
        boolean folia = Platform.isFolia();
        log("server=" + getServer().getName() + " folia=" + folia);
        check("Platform.isFolia() matches the server", folia == "Folia".equalsIgnoreCase(getServer().getName()));
        check("probe steps run on the global tick thread", Bukkit.isGlobalTickThread());
        check("raw scheduler rejects delay 0", rejectsZeroDelay());
        check(folia ? "Folia: BukkitScheduler.runTask throws UnsupportedOperationException" : "Paper: BukkitScheduler.runTask works",
                bukkitSchedulerUsable() != folia);

        AtomicBoolean clamped = new AtomicBoolean();
        tasks.globalLater(0L, () -> clamped.set(true));

        World world = getServer().getWorlds().get(0);
        Location spawn = world.getSpawnLocation().toBlockLocation();
        world.getChunkAtAsync(spawn).thenAccept(chunk -> tasks.at(spawn, () -> guard(() -> {
            chunk.addPluginChunkTicket(this);   // no player online: keep the region loaded
            regionStage(world, spawn);
        })));
        later(160L, () -> {
            check("Tasks.globalLater(0) clamped to 1 tick and ran", clamped.get());
            cancelAllStage();
        });
    }

    private void regionStage(World world, Location spawn) {
        check("region task owns its location", Bukkit.isOwnedByCurrentRegion(spawn));
        Location at = spawn.clone().add(0, 1, 0);

        // entity timer that cancels itself after 3 runs; then the zombie is removed and a pending task retires
        Zombie counted = spawnZombie(world, at);
        AtomicInteger runs = new AtomicInteger();
        ScheduledTask[] timer = new ScheduledTask[1];
        timer[0] = tasks.entityTimer(counted, 1L, 5L, task -> {
            check("entity timer runs on the entity's thread", Bukkit.isOwnedByCurrentRegion(counted));
            if (runs.incrementAndGet() == 3) {
                task.cancel();
            }
        }, () -> { });
        AtomicBoolean pendingRan = new AtomicBoolean();
        AtomicBoolean retired = new AtomicBoolean();
        tasks.entityLater(counted, 60L, () -> pendingRan.set(true), () -> retired.set(true));
        tasks.entityLater(counted, 30L, counted::remove, () -> { });
        tasks.globalLater(80L, () -> guard(() -> {
            check("entity timer cancelled itself after 3 runs", runs.get() == 3 && timer[0].isCancelled());
            check("removing the entity runs retired, not the task", retired.get() && !pendingRan.get());
            check("scheduling on a removed entity returns null", tasks.entity(counted, () -> { }, () -> { }) == null);
        }));

        // block restored later through the region scheduler
        Location ore = spawn.clone().add(3, 0, 3);
        BlockData coal = Material.COAL_ORE.createBlockData();
        ore.getBlock().setType(Material.AIR);
        new BlockRegen(tasks, 20L).schedule(ore, coal);
        tasks.atLater(ore, 40L, () -> check("BlockRegen restored the ore after its delay", ore.getBlock().getType() == Material.COAL_ORE));

        // async work, then back to an entity's own thread
        Zombie target = spawnZombie(world, at.clone().add(2, 0, 0));
        tasks.async(() -> {
            check("async task is off the tick threads", !Bukkit.isPrimaryThread() && !Bukkit.isGlobalTickThread()
                    && !Bukkit.isOwnedByCurrentRegion(target));
            tasks.entity(target, () -> guard(() -> {
                check("hop from async back to the entity's thread", Bukkit.isOwnedByCurrentRegion(target));
                teleportStage(target);
            }), () -> { });
        });
    }

    /** Runs on the target's thread. The destination is far enough to be another region on Folia. */
    private void teleportStage(Zombie target) {
        boolean folia = Platform.isFolia();
        boolean syncTeleportWorks;
        try {
            syncTeleportWorks = target.teleport(target.getLocation().add(1, 0, 0));
        } catch (UnsupportedOperationException e) {
            log("sync teleport (1 block) threw " + e);
            syncTeleportWorks = false;
        }
        check(folia ? "Folia: Entity#teleport throws even for 1 block" : "Paper: Entity#teleport works", syncTeleportWorks != folia);
        Location far = target.getLocation().add(3000, 0, 0);
        // keep the destination loaded: with no player there, the entity would stop ticking after it arrives
        far.getWorld().getChunkAtAsync(far).thenAccept(chunk -> tasks.at(far, () -> {
            chunk.addPluginChunkTicket(this);
            tasks.entity(target, () -> target.teleportAsync(far).thenAccept(ok -> tasks.entity(target, () -> {
                check("teleportAsync completed", ok);
                check("entity scheduler follows the entity after teleportAsync",
                        Bukkit.isOwnedByCurrentRegion(target) && Math.abs(target.getLocation().getX() - far.getX()) < 1);
            }, () -> log("target retired after teleport"))), () -> log("target retired before teleport"));
        }));
    }

    private void cancelAllStage() {
        AtomicInteger ticks = new AtomicInteger();
        tasks.globalTimer(1L, 1L, task -> ticks.incrementAndGet());
        later(5L, () -> {
            tasks.cancelAll();
            int atCancel = ticks.get();
            later(10L, () -> {
                check("Tasks.cancelAll stopped the repeating task", atCancel > 0 && ticks.get() == atCancel);
                finish();
            });
        });
    }

    private Zombie spawnZombie(World world, Location at) {
        Zombie zombie = (Zombie) world.spawnEntity(at, EntityType.ZOMBIE);
        zombie.setAI(false);
        zombie.setPersistent(true);
        return zombie;
    }

    private boolean rejectsZeroDelay() {
        try {
            ScheduledTask task = getServer().getGlobalRegionScheduler().runDelayed(this, t -> { }, 0L);
            task.cancel();
            return false;
        } catch (IllegalArgumentException expected) {
            log("delay 0 -> " + expected);
            return true;
        }
    }

    private boolean bukkitSchedulerUsable() {
        try {
            getServer().getScheduler().runTask(this, () -> { }).cancel();
            return true;
        } catch (UnsupportedOperationException e) {
            log("BukkitScheduler.runTask threw " + e);
            return false;
        }
    }
}
