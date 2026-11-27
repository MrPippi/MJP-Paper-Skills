package com.example.probe.homes;

import com.example.homes.HomeService;
import com.example.homes.HomesPlugin;
import com.example.homes.domain.Home;
import com.example.homes.persistence.SchemaMigrations;
import com.example.homes.persistence.SqliteDatabase;
import com.example.probe.Probe;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

/**
 * paper-sqlite-repository (Homes plugin, its own jar): the driver arrives through plugin.yml `libraries:`, the
 * migration and pragmas apply, write-behind persists, the final flush on disable keeps late changes across a restart,
 * and a schema newer than the plugin disables it.
 *
 * Direct database access goes through the template's SqliteDatabase class, which Homes' class loader defines, so the
 * driver (a library of Homes, not of this probe) is visible to it.
 */
public final class HomesProbe extends Probe {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Override
    protected void start() throws Exception {
        Path stage = getDataFolder().toPath().resolve("stage");
        int n = Files.exists(stage) ? Integer.parseInt(Files.readString(stage).trim()) + 1 : 1;
        Files.createDirectories(stage.getParent());
        Files.writeString(stage, Integer.toString(n));
        Plugin homes = getServer().getPluginManager().getPlugin("Homes");
        switch (n) {
            case 1 -> firstStart(homes);
            case 2 -> secondStart(homes);
            default -> thirdStart(homes);
        }
    }

    private void firstStart(Plugin plugin) throws SQLException {
        check("Homes enabled (sqlite-jdbc from libraries:, native library loaded)", plugin != null && plugin.isEnabled());
        HomesPlugin homes = (HomesPlugin) plugin;
        withDatabase(homes, c -> {
            check("migrated to schema version 1", SchemaMigrations.currentVersion(c) == 1);
            check("journal_mode is WAL", "wal".equalsIgnoreCase(query(c, "PRAGMA journal_mode")));
        });
        HomeService service = homes.homes();
        service.setHome(home("base"));
        service.setHome(home("mine"));
        service.setHome(home("farm"));
        service.removeHome(OWNER, "farm");
        // The flusher runs every 100 ticks on its writer thread; wait for at least one pass
        later(220L, () -> service.loadHomes(OWNER, loaded -> guard(() -> {
            check("loadHomes callback on the main thread", Bukkit.isPrimaryThread());
            check("write-behind persisted base + mine, not the removed farm", names(loaded).equals(List.of("base", "mine")));
            // Changed right before shutdown: only the synchronous flush in onDisable can persist these
            service.setHome(home("nether"));
            service.removeHome(OWNER, "mine");
            finish();
        })));
    }

    private void secondStart(Plugin plugin) {
        check("restart: Homes enabled", plugin != null && plugin.isEnabled());
        HomesPlugin homes = (HomesPlugin) plugin;
        homes.homes().loadHomes(OWNER, loaded -> guard(() -> {
            check("restart: final flush kept the late changes (base + nether)", names(loaded).equals(List.of("base", "nether")));
            // Simulate a database written by a newer plugin version
            withDatabase(homes, c -> {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("PRAGMA user_version = 2");
                }
            });
            finish();
        }));
    }

    private void thirdStart(Plugin plugin) {
        check("newer schema (2 > 1): Homes disabled itself", plugin != null && !plugin.isEnabled());
        finish();
    }

    @FunctionalInterface
    private interface SqlStep {
        void run(Connection connection) throws SQLException;
    }

    private void withDatabase(HomesPlugin homes, SqlStep step) throws SQLException {
        SqliteDatabase database = new SqliteDatabase(homes.getDataFolder().toPath().resolve("homes.db"), getSLF4JLogger());
        database.open();
        try {
            step.run(database.connection());
        } finally {
            database.close();
        }
    }

    private static String query(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static Home home(String name) {
        return new Home(OWNER, name, "world", 1, 64, 2, 0f, 0f);
    }

    private static List<String> names(List<Home> homes) {
        return homes.stream().map(Home::name).sorted().toList();
    }
}
