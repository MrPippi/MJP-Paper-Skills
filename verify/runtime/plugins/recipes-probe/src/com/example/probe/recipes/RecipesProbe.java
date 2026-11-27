package com.example.probe.recipes;

import com.example.probe.Probe;
import io.papermc.paper.datapack.Datapack;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

/**
 * paper-custom-recipes (RecipesTest plugin) and paper-datapack-bundling (CustomContent plugin):
 * crafting and brewing results, the bundled data pack, and that both survive /minecraft:reload.
 */
public final class RecipesProbe extends Probe {

    private static final NamespacedKey DATAPACK_RECIPE = new NamespacedKey("example", "gravel_from_flint");

    @Override
    protected long startDelay() {
        return 20L;
    }

    @Override
    protected void start() {
        World world = Bukkit.getWorlds().get(0);
        recipes(world);
        datapack(world);
        brewing(world, 2, "brewing stand produced REGENERATION from glow berries", () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:reload");
            later(60L, () -> {
                check("after reload: plugin recipe trident still registered", Bukkit.getRecipe(key("trident")) != null);
                check("after reload: datapack recipe present", Bukkit.getRecipe(DATAPACK_RECIPE) != null);
                // 1.21.11 brews through PotionMix, which is not a Recipe: check the brewing stand, not getRecipe
                brewing(world, 5, "after reload: brewing still produces REGENERATION", this::finish);
            });
        });
    }

    private static NamespacedKey key(String name) {
        return new NamespacedKey("recipestest", name);
    }

    private void recipes(World world) {
        for (String k : new String[] {"trident", "diamond_sword_upgrade", "infused_bread"}) {
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
        check("datapack format range matches this server", pack != null && pack.getCompatibility() == Datapack.Compatibility.COMPATIBLE);
        check("datapack recipe loaded", Bukkit.getRecipe(DATAPACK_RECIPE) != null);
        ItemStack f = new ItemStack(Material.FLINT);
        ItemStack[] grid = {f, f, null, f, f, null, null, null, null};
        check("craft gravel from 4 flint", Bukkit.craftItemResult(grid, world).getResult().getType() == Material.GRAVEL);
        check("load function created objective", Bukkit.getScoreboardManager().getMainScoreboard().getObjective("example_deaths") != null);
    }

    /** Glow berries in a real brewing stand turn awkward potions into regeneration (~400 ticks). */
    private void brewing(World world, int offset, String what, Step then) {
        Location at = world.getSpawnLocation().clone().add(offset, 0, 2);
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
            log("brewing at +" + offset + ": slot0 after 460 ticks: " + type + " ingredient left=" + after.getIngredient());
            check(what, type == PotionType.REGENERATION);
            then.run();
        });
    }

    private static ItemStack potion(PotionType type) {
        ItemStack s = new ItemStack(Material.POTION);
        s.editMeta(PotionMeta.class, m -> m.setBasePotionType(type));
        return s;
    }
}
