package com.example.probe.registry;

import com.example.content.ContentKeys;
import com.example.probe.Probe;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.keys.EnchantmentKeys;
import io.papermc.paper.registry.keys.tags.DamageTypeTagKeys;
import io.papermc.paper.registry.keys.tags.EnchantmentTagKeys;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredListener;

/**
 * paper-registry-events: what the CustomContent (example 1) and Balance (example 2) bootstrappers
 * registered, that bleed damage really ignores armor, and that it all survives /minecraft:reload.
 */
public final class RegistryProbe extends Probe {

    @Override
    protected void start() {
        Registry<Enchantment> enchantments = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        Registry<DamageType> damageTypes = RegistryAccess.registryAccess().getRegistry(RegistryKey.DAMAGE_TYPE);
        Enchantment lifesteal = enchantments.get(ContentKeys.LIFESTEAL);
        DamageType bleed = damageTypes.get(ContentKeys.BLEED);
        check("lifesteal registered", lifesteal != null);
        check("bleed registered", bleed != null);
        if (lifesteal != null) {
            check("lifesteal max level 3", lifesteal.getMaxLevel() == 3);
            check("lifesteal can enchant diamond sword", lifesteal.canEnchantItem(new ItemStack(Material.DIAMOND_SWORD)));
            check("lifesteal cannot enchant bow", !lifesteal.canEnchantItem(new ItemStack(Material.BOW)));
            check("lifesteal conflicts with smite", lifesteal.conflictsWith(enchantments.getOrThrow(EnchantmentKeys.SMITE)));
            check("lifesteal in #in_enchanting_table", enchantments.getTag(EnchantmentTagKeys.IN_ENCHANTING_TABLE).contains(ContentKeys.LIFESTEAL));
            check("lifesteal in #tradeable", enchantments.getTag(EnchantmentTagKeys.TRADEABLE).contains(ContentKeys.LIFESTEAL));
            check("lifesteal in #non_treasure", enchantments.getTag(EnchantmentTagKeys.NON_TREASURE).contains(ContentKeys.LIFESTEAL));
        }
        check("sharpness max level 6", enchantments.getOrThrow(EnchantmentKeys.SHARPNESS).getMaxLevel() == 6);
        check("protection max level 3 (example 2)", enchantments.getOrThrow(EnchantmentKeys.PROTECTION).getMaxLevel() == 3);
        check("mending not in #tradeable (example 2)", !enchantments.getTag(EnchantmentTagKeys.TRADEABLE).contains(EnchantmentKeys.MENDING));
        check("bleed in #bypasses_armor", damageTypes.getTag(DamageTypeTagKeys.BYPASSES_ARMOR).contains(ContentKeys.BLEED));
        boolean listener = false;
        for (RegisteredListener l : EntityDamageByEntityEvent.getHandlerList().getRegisteredListeners()) {
            listener |= l.getPlugin().getName().equals("CustomContent");
        }
        check("LifestealListener registered by CustomContent", listener);
        if (bleed != null) {
            armorTest(bleed);
        } else {
            reloadTest();
        }
    }

    /** An armored zombie takes the full amount from bleed and less from a normal mob attack. */
    private void armorTest(DamageType bleed) {
        World world = getServer().getWorlds().get(0);
        Location at = world.getSpawnLocation().add(0, 1, 0);
        at.getChunk().addPluginChunkTicket(this);   // no player online: keep the chunk loaded
        Zombie a = armored(world, at);
        Zombie b = armored(world, at.clone().add(3, 0, 0));
        later(10L, () -> {
            log("zombies valid=" + a.isValid() + "/" + b.isValid());
            double before = a.getHealth();
            a.damage(6.0, DamageSource.builder(bleed).build());
            double bleedTaken = before - a.getHealth();
            b.setNoDamageTicks(0);
            double beforeB = b.getHealth();
            b.damage(6.0, DamageSource.builder(DamageType.MOB_ATTACK).build());
            double attackTaken = beforeB - b.getHealth();
            log("bleed took " + bleedTaken + ", mob_attack took " + attackTaken);
            check("bleed ignores armor (6.0 taken)", Math.abs(bleedTaken - 6.0) < 0.01);
            check("mob_attack is reduced by armor", attackTaken < 6.0);
            a.remove();
            b.remove();
            reloadTest();
        });
    }

    private void reloadTest() {
        getServer().dispatchCommand(getServer().getConsoleSender(), "minecraft:reload");
        later(100L, () -> {
            Registry<Enchantment> after = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
            check("after /reload: lifesteal still in #in_enchanting_table", after.getTag(EnchantmentTagKeys.IN_ENCHANTING_TABLE).contains(ContentKeys.LIFESTEAL));
            check("after /reload: mending still not in #tradeable", !after.getTag(EnchantmentTagKeys.TRADEABLE).contains(EnchantmentKeys.MENDING));
            finish();
        });
    }

    private static Zombie armored(World world, Location at) {
        Zombie z = (Zombie) world.spawnEntity(at, EntityType.ZOMBIE);
        z.setAI(false);
        EntityEquipment eq = z.getEquipment();
        eq.setHelmet(new ItemStack(Material.DIAMOND_HELMET));
        eq.setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
        eq.setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
        eq.setBoots(new ItemStack(Material.DIAMOND_BOOTS));
        return z;
    }
}
