package com.example.probe.arena;

import com.example.arena.ArenaNames;
import com.example.arena.ArenaRules;
import com.example.arena.DisposableWorlds;
import com.example.arena.VoidChunkGenerator;
import com.example.arena.WorldFolders;
import com.example.probe.Probe;
import org.bukkit.Material;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * paper-disposable-world: create, destroy and template-copy arenas, then (second start) purge
 * what a crash and an interrupted copy left behind.
 */
public final class ArenaProbe extends Probe {

    private DisposableWorlds worlds;
    private Path dataRoot;

    @Override
    protected void start() throws IOException {
        worlds = new DisposableWorlds(this, new ArenaNames(99), new ArenaRules(getLogger()));
        dataRoot = WorldFolders.dataRoot(getServer());
        Path marker = getDataFolder().toPath().resolve("leftovers.txt");
        if (Files.exists(marker)) {
            secondStart(Files.readAllLines(marker));
        } else {
            firstStart(marker);
        }
    }

    private void firstStart(Path marker) throws IOException {
        log("dataRoot=" + dataRoot);
        World a = worlds.createVoid();
        check("arena data lives under dataRoot", a.getWorldPath().toAbsolutePath().normalize().equals(dataRoot.resolve(a.getName())));
        a.getBlockAt(5, 64, 5).setType(Material.GOLD_BLOCK);
        a.save();
        Path crashed = a.getWorldPath().toAbsolutePath().normalize();
        getServer().unloadWorld(a, true);                     // simulated crash: data stays behind

        World b = worlds.createVoid();
        check("crashed arena's name is not reused", !b.getName().equals(a.getName()));
        check("new arena has no stale blocks", b.getBlockAt(5, 64, 5).getType() == Material.AIR);
        Path bPath = b.getWorldPath().toAbsolutePath().normalize();
        worlds.destroy(b).whenComplete((v, err) -> guard(() -> {
            check("destroy succeeded", err == null);
            check("destroyed data removed", !Files.exists(bPath));
            check("no .deleting debris", !Files.exists(bPath.resolveSibling(bPath.getFileName() + WorldFolders.DELETING)));
            templateTest(marker, crashed);
        }));
    }

    private void templateTest(Path marker, Path crashed) throws IOException {
        World t = worlds.createVoid();
        t.getBlockAt(7, 64, 7).setType(Material.DIAMOND_BLOCK);
        t.save();
        UUID templateUid = t.getUID();
        Path tPath = t.getWorldPath().toAbsolutePath().normalize();
        getServer().unloadWorld(t, true);
        Path template = getDataFolder().toPath().resolve("templates").resolve("duel_map");
        copyTree(tPath, template);
        Path interrupted = dataRoot.resolve("arena_77" + WorldFolders.COPYING);
        Files.createDirectories(interrupted);                 // simulated interrupted copy
        Files.write(marker, List.of(crashed.toString(), tPath.toString(), interrupted.toString()));

        worlds.createFromTemplate(template, new VoidChunkGenerator()).thenCompose(w1 ->
                worlds.createFromTemplate(template, new VoidChunkGenerator()).thenApply(w2 -> new World[] {w1, w2})
        ).whenComplete((pair, err) -> guard(() -> {
            check("both template copies loaded", err == null && pair != null);
            if (pair != null) {
                for (World w : pair) {
                    check(w.getName() + " has the template's diamond block", w.getBlockAt(7, 64, 7).getType() == Material.DIAMOND_BLOCK);
                    check(w.getName() + " UUID differs from the template's", !w.getUID().equals(templateUid));
                    check(w.getName() + " no .copying left", !Files.exists(dataRoot.resolve(w.getName() + WorldFolders.COPYING)));
                }
                check("copies have distinct UUIDs", !pair[0].getUID().equals(pair[1].getUID()));
            }
            later(20L, this::finish);
        }));
    }

    private void secondStart(List<String> leftovers) {
        for (String p : leftovers) {
            log("before purge exists=" + Files.exists(Path.of(p)) + " " + p);
        }
        worlds.purgeStaleAsync();
        later(60L, () -> {
            for (String p : leftovers) {
                check("purged " + Path.of(p).getFileName(), !Files.exists(Path.of(p)));
            }
            check("main world untouched", Files.isDirectory(getServer().getWorlds().get(0).getWorldPath()));
            finish();
        });
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
