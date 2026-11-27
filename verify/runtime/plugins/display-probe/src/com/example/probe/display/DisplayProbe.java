package com.example.probe.display;

import com.example.hologram.HologramSpec;
import com.example.hologram.Holograms;
import com.example.hologram.ItemSpinner;
import com.example.probe.Probe;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.joml.Quaternionf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * paper-display-entities (template classes in this jar). First start: spawn, in-place text update, interpolated
 * spinning, and a non-persistent hologram that is discarded with its chunk and respawned when the chunk loads again;
 * a persistent tagged copy is left behind on purpose. Second start: that leftover is removed and one copy remains.
 */
public final class DisplayProbe extends Probe {

    private static final int FAR_BLOCKS = 800;

    private Holograms holograms;
    private final AtomicInteger loadedCopies = new AtomicInteger();

    /** Counts tagged copies read from disk (before Holograms removes them). */
    private final class LoadCounter implements Listener {
        @EventHandler
        public void onEntitiesLoad(EntitiesLoadEvent event) {
            for (Entity e : event.getEntities()) {
                if ("welcome".equals(e.getPersistentDataContainer().get(holograms.idKey(), PersistentDataType.STRING))) {
                    loadedCopies.incrementAndGet();
                }
            }
        }
    }

    @Override
    protected void start() throws Exception {
        holograms = new Holograms(this);
        getServer().getPluginManager().registerEvents(new LoadCounter(), this);
        getServer().getPluginManager().registerEvents(holograms, this);
        Path marker = getDataFolder().toPath().resolve("restarted");
        World world = getServer().getWorlds().get(0);
        Location a = world.getSpawnLocation().toBlockLocation().add(0.5, 2, 0.5);
        if (Files.exists(marker)) {
            onRegion(a, () -> secondStart(a));
        } else {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "x");
            onRegion(a, () -> firstStart(a));
        }
    }

    /** Loads the chunk, keeps it loaded with a ticket, then runs `step` on its region. */
    private void onRegion(Location at, Step step) {
        at.getWorld().getChunkAtAsync(at).thenAccept(chunk -> getServer().getRegionScheduler().run(this, at, task -> guard(() -> {
            chunk.addPluginChunkTicket(this);
            step.run();
        })));
    }

    private void firstStart(Location a) {
        holograms.define(HologramSpec.at("welcome", a, Component.text("Hello")));
        getServer().getRegionScheduler().runDelayed(this, a, task -> guard(() -> {
            TextDisplay d = holograms.entity("welcome").orElse(null);
            check("hologram spawned in a loaded chunk", d != null);
            if (d == null) {
                finish();
                return;
            }
            check("hologram is not persistent", !d.isPersistent());
            check("hologram tagged with its id", "welcome".equals(d.getPersistentDataContainer().get(holograms.idKey(), PersistentDataType.STRING)));
            check("billboard CENTER, text set", d.getBillboard() == Display.Billboard.CENTER && "Hello".equals(plain(d.text())));
            UUID before = d.getUniqueId();
            holograms.setText("welcome", Component.text("Hello again"));
            boolean rejected;
            try {
                d.setTeleportDuration(100);
                rejected = false;
            } catch (IllegalArgumentException e) {
                log("setTeleportDuration(100) -> " + e.getMessage());
                rejected = true;
            }
            check("setTeleportDuration above 59 throws", rejected);
            ItemDisplay spinner = ItemSpinner.spawn(this, a.clone().add(2, 0, 0), new ItemStack(Material.DIAMOND), 5);
            getServer().getRegionScheduler().runDelayed(this, a, t2 -> guard(() -> {
                TextDisplay same = holograms.entity("welcome").orElse(null);
                check("setText updates in place (same entity)", same != null && same.getUniqueId().equals(before)
                        && "Hello again".equals(plain(same.text())));
                Quaternionf rotation = spinner.getTransformation().getLeftRotation();
                log("spinner after 30 ticks: interpolation " + spinner.getInterpolationDuration() + ", rotation " + rotation);
                check("spinner sets interpolation duration to its step", spinner.getInterpolationDuration() == 5);
                check("spinner rotated", Math.abs(rotation.w) < 0.999f);
                farChunk(a);
            }), 30L);
        }), 10L);
    }

    /** A definition in an unloaded chunk spawns when the chunk loads, vanishes when it unloads, and comes back. */
    private void farChunk(Location a) {
        Location b = a.clone().add(FAR_BLOCKS, 0, 0);
        holograms.define(HologramSpec.at("far", b, Component.text("Far away")));
        later(5L, () -> {
            check("definition in an unloaded chunk spawns nothing yet", holograms.entity("far").isEmpty());
            b.getWorld().getChunkAtAsync(b).thenAccept(chunk -> getServer().getRegionScheduler().run(this, b, task -> guard(() -> {
                chunk.addPluginChunkTicket(this);
                getServer().getRegionScheduler().runDelayed(this, b, t2 -> guard(() -> {
                    TextDisplay first = holograms.entity("far").orElse(null);
                    check("ChunkLoadEvent spawned the far hologram", first != null);
                    chunk.removePluginChunkTicket(this);
                    boolean requested = b.getWorld().unloadChunkRequest(chunk.getX(), chunk.getZ());
                    log("unloadChunkRequest -> " + requested);
                    waitUntilUnloaded(b, 0, () -> reload(a, b, first));
                }), 10L);
            })));
        });
    }

    private void waitUntilUnloaded(Location b, int tries, Step then) {
        later(10L, () -> {
            if (b.getWorld().isChunkLoaded(b.getBlockX() >> 4, b.getBlockZ() >> 4) && tries < 30) {
                waitUntilUnloaded(b, tries + 1, then);
                return;
            }
            log("far chunk loaded after waiting " + (tries * 10) + " ticks: " + b.getWorld().isChunkLoaded(b.getBlockX() >> 4, b.getBlockZ() >> 4));
            then.run();
        });
    }

    private void reload(Location a, Location b, TextDisplay first) {
        check("non-persistent hologram discarded with its chunk", first == null || !first.isValid());
        b.getWorld().getChunkAtAsync(b).thenAccept(chunk -> getServer().getRegionScheduler().run(this, b, task -> guard(() -> {
            chunk.addPluginChunkTicket(this);
            getServer().getRegionScheduler().runDelayed(this, b, t2 -> guard(() -> {
                TextDisplay again = holograms.entity("far").orElse(null);
                check("respawned as a new entity when the chunk loaded again", again != null && (first == null || !again.getUniqueId().equals(first.getUniqueId())));
                check("old entity stays removed after the reload", first == null || !first.isValid());
                check("exactly one far hologram in the chunk", tagged(chunk, "far") == 1);
                leaveLeftover(a);
            }), 10L);
        })));
    }

    /** A persistent copy tagged like the real one, as an older version of the plugin would have saved it. */
    private void leaveLeftover(Location a) {
        getServer().getRegionScheduler().run(this, a, task -> guard(() -> {
            a.getWorld().spawn(a, TextDisplay.class, d -> {
                d.setPersistent(true);
                d.getPersistentDataContainer().set(holograms.idKey(), PersistentDataType.STRING, "welcome");
                d.text(Component.text("old copy"));
            });
            a.getWorld().save();
            later(20L, this::finish);
        }));
    }

    private void secondStart(Location a) {
        Chunk chunk = a.getChunk();
        holograms.define(HologramSpec.at("welcome", a, Component.text("Hello")));
        getServer().getRegionScheduler().runDelayed(this, a, task -> guard(() -> {
            TextDisplay live = holograms.entity("welcome").orElse(null);
            int count = tagged(chunk, "welcome");
            log("restart: copies loaded from disk " + loadedCopies.get() + ", tagged entities after define " + count);
            check("restart: the persistent leftover was saved and loaded", loadedCopies.get() >= 1);
            check("restart: leftover removed, exactly one live non-persistent copy",
                    live != null && count == 1 && !live.isPersistent() && "Hello".equals(plain(live.text())));
            finish();
        }), 20L);
    }

    private int tagged(Chunk chunk, String id) {
        int n = 0;
        for (Entity e : chunk.getEntities()) {
            if (e instanceof TextDisplay && id.equals(e.getPersistentDataContainer().get(holograms.idKey(), PersistentDataType.STRING))) {
                n++;
            }
        }
        return n;
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }
}
