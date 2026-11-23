package com.example.probe.gameplay;

import com.example.clock.TimeLock;
import com.example.clock.WorldTime;
import com.example.probe.Probe;
import io.papermc.paper.datapack.Datapack;
import io.papermc.paper.world.WorldClock;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * paper-custom-recipes (RecipesTest plugin), paper-datapack-bundling (CustomContent plugin) and
 * paper-world-clock (classes in this jar): crafting and brewing results, the bundled data pack,
 * clock pause/rate/lock, /minecraft:reload, and (second start) what persists across a restart.
 */
public final class GameplayProbe extends Probe {

    private static final String ARENA = "gameplay_arena";

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
        recipes(world);
        datapack(world);
        clocks(world, arena);
        brewing(world, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:reload");
            later(60L, () -> {
                check("after reload: plugin recipe trident still registered", Bukkit.getRecipe(key("trident")) != null);
                check("after reload: brewing recipe still registered", Bukkit.getRecipe(key("regeneration_from_glow_berries")) != null);
                check("after reload: datapack recipe present", Bukkit.getRecipe(new NamespacedKey("example", "gravel_from_flint")) != null);
                finish();
            });
        });
    }

    private static NamespacedKey key(String name) {
        return new NamespacedKey("recipestest", name);
    }

    private void recipes(World world) {
        for (String k : new String[] {"trident", "diamond_sword_upgrade", "infused_bread", "regeneration_from_glow_berries"}) {
            check("recipe registered " + k, Bukkit.getRecipe(key(k)) != null);
        }
        ItemStack p = new ItemStack(Material.PRISMARINE_SHARD);
        ItemStack h = new ItemStack(Material.BLAZE_ROD);
        ItemStack[] trident = {null, p, p, null, h, p, h, null, null};
        check("craft trident with blaze rod handle", Bukkit.craftItemResult(trident, world).getResult().getType() == Material.TRIDENT);

        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        sword.addEnchantment(Enchantment.SHARPNESS, 3);
        sword.editMeta(m -> m.customName(Component.text("Old Faithful")));
        ItemStack[] upgrade = {sword, new ItemStack(Material.DIAMOND), null, null, null, null, null, null, null};
        ItemStack result = Bukkit.craftItemResult(upgrade, world).getResult();
        check("transmute -> diamond sword", result.getType() == Material.DIAMOND_SWORD);
        check("transmute keeps sharpness 3", result.getEnchantmentLevel(Enchantment.SHARPNESS) == 3);
        check("transmute keeps custom name", result.getItemMeta() != null && result.getItemMeta().hasCustomName());

        ItemStack[] bread = {new ItemStack(Material.GLOW_BERRIES), new ItemStack(Material.WHEAT), null, null, null, null, null, null, null};
        ItemStack b = Bukkit.craftItemResult(bread, world).getResult();
        check("infused bread x2", b.getType() == Material.BREAD && b.getAmount() == 2);
    }

    private void datapack(World world) {
        Datapack pack = Bukkit.getDatapackManager().getPack("CustomContent/content");
        check("datapack CustomContent/content found", pack != null);
        check("datapack enabled", pack != null && pack.isEnabled());
        check("datapack recipe loaded", Bukkit.getRecipe(new NamespacedKey("example", "gravel_from_flint")) != null);
        ItemStack f = new ItemStack(Material.FLINT);
        ItemStack[] grid = {f, f, null, f, f, null, null, null, null};
        check("craft gravel from 4 flint", Bukkit.craftItemResult(grid, world).getResult().getType() == Material.GRAVEL);
        check("load function created objective", Bukkit.getScoreboardManager().getMainScoreboard().getObjective("example_deaths") != null);
    }

    private void clocks(World world, World arena) {
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
        });
    }

    /** Glow berries in a real brewing stand turn awkward potions into regeneration (~400 ticks). */
    private void brewing(World world, Step then) {
        Location at = world.getSpawnLocation().clone().add(2, 0, 2);
        at.getChunk().addPluginChunkTicket(this);   // no player online: keep the chunk ticking
        Block block = at.getBlock();
        block.setType(Material.BREWING_STAND);
        BrewerInventory inv = ((BrewingStand) block.getState()).getInventory();
        for (int i = 0; i < 3; i++) {
            inv.setItem(i, potion(PotionType.AWKWARD));
        }
        inv.setIngredient(new ItemStack(Material.GLOW_BERRIES));
        inv.setFuel(new ItemStack(Material.BLAZE_POWDER));
        later(460L, () -> {
            BrewerInventory after = ((BrewingStand) block.getState()).getInventory();
            ItemStack out = after.getItem(0);
            PotionType type = out != null && out.getItemMeta() instanceof PotionMeta pm ? pm.getBasePotionType() : null;
            log("brewing slot0 after 460 ticks: " + type + " ingredient left=" + after.getIngredient());
            check("brewing stand produced REGENERATION from glow berries", type == PotionType.REGENERATION);
            then.run();
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
        check("restart: recipes re-registered", Bukkit.getRecipe(key("trident")) != null);
        finish();
    }

    private static ItemStack potion(PotionType type) {
        ItemStack s = new ItemStack(Material.POTION);
        s.editMeta(PotionMeta.class, m -> m.setBasePotionType(type));
        return s;
    }
}
