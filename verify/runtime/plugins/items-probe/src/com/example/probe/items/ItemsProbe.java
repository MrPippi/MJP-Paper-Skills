package com.example.probe.items;

import com.example.items.CustomItems;
import com.example.items.ItemChecks;
import com.example.items.ItemIds;
import com.example.items.ShopMatcher;
import com.example.items.VanillaTweaks;
import com.example.probe.Probe;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** paper-data-components: the example items, prototype overrides, matching and serialization. */
public final class ItemsProbe extends Probe {

    @Override
    protected long startDelay() {
        return 20L;
    }

    @Override
    protected void start() {
        ItemIds ids = new ItemIds(this);
        CustomItems items = new CustomItems(this, ids);

        ItemStack berry = items.healingBerry();
        check("berry id", ids.is(berry, CustomItems.HEALING_BERRY));
        check("berry food nutrition 2", berry.getData(DataComponentTypes.FOOD).nutrition() == 2);
        check("berry consumable has 1 effect", berry.getData(DataComponentTypes.CONSUMABLE).consumeEffects().size() == 1);
        check("berry max stack 16", berry.getMaxStackSize() == 16);
        check("berry cooldown group set", berry.getData(DataComponentTypes.USE_COOLDOWN).cooldownGroup() != null);

        ItemStack hammer = items.warHammer();
        ItemAttributeModifiers mods = hammer.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        check("hammer has 2 modifiers", mods.modifiers().size() == 2);
        check("hammer attack damage modifier", mods.modifiers().stream().anyMatch(e -> e.attribute() == Attribute.ATTACK_DAMAGE));
        check("hammer weapon component", hammer.hasData(DataComponentTypes.WEAPON));
        check("hammer max damage 500", hammer.getData(DataComponentTypes.MAX_DAMAGE) == 500);

        ItemStack vest = items.gliderVest();
        check("vest glider", vest.hasData(DataComponentTypes.GLIDER));
        check("vest equippable chest", vest.getData(DataComponentTypes.EQUIPPABLE).slot() == EquipmentSlot.CHEST);

        ItemStack icon = CustomItems.icon(Material.DIAMOND_SWORD, Component.text("Shop"), List.of(), true);
        check("icon hides attributes", icon.getData(DataComponentTypes.TOOLTIP_DISPLAY).hiddenComponents().contains(DataComponentTypes.ATTRIBUTE_MODIFIERS));
        check("icon glint", Boolean.TRUE.equals(icon.getData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE)));

        ItemStack apple = ItemStack.of(Material.APPLE);
        check("apple: has FOOD from prototype", apple.hasData(DataComponentTypes.FOOD) && !apple.isDataOverridden(DataComponentTypes.FOOD));
        apple.unsetData(DataComponentTypes.FOOD);
        check("apple: unset removes prototype FOOD", !apple.hasData(DataComponentTypes.FOOD) && apple.isDataOverridden(DataComponentTypes.FOOD));
        apple.resetData(DataComponentTypes.FOOD);
        check("apple: reset restores prototype", apple.hasData(DataComponentTypes.FOOD) && !apple.isDataOverridden(DataComponentTypes.FOOD));

        ItemStack cursed = VanillaTweaks.cursedApple();
        check("cursed apple has no FOOD/CONSUMABLE", !cursed.hasData(DataComponentTypes.FOOD) && !cursed.hasData(DataComponentTypes.CONSUMABLE));

        ItemStack small = VanillaTweaks.smallStackGoldenApple(1);
        check("small-stack golden apple max 16", small.getMaxStackSize() == 16);
        check("small-stack golden apple not similar to normal", !small.isSimilar(ItemStack.of(Material.GOLDEN_APPLE)));

        ItemStack worn = ItemStack.of(Material.IRON_SWORD);
        worn.setData(DataComponentTypes.DAMAGE, 50);
        ItemStack fresh = ItemStack.of(Material.IRON_SWORD, 2);
        check("sameIgnoringWear: worn vs fresh x2", ItemChecks.sameIgnoringWear(fresh, worn));
        check("ShopMatcher accepts worn sword", new ShopMatcher(fresh).accepts(worn));
        ItemStack renamed = ItemStack.of(Material.IRON_SWORD);
        renamed.setData(DataComponentTypes.CUSTOM_NAME, Component.text("Bob"));
        check("ShopMatcher rejects renamed sword", !new ShopMatcher(fresh).accepts(renamed));

        ItemStack target = items.warHammer();
        ItemChecks.copyCosmetics(renamed, target);
        check("copyCosmetics copies CUSTOM_NAME", target.hasData(DataComponentTypes.CUSTOM_NAME));
        check("copyCosmetics keeps attributes", target.hasData(DataComponentTypes.ATTRIBUTE_MODIFIERS));

        VanillaTweaks.repairInPlace(worn);
        check("repair: damage back to 0", worn.getData(DataComponentTypes.DAMAGE) == 0 && !worn.isDataOverridden(DataComponentTypes.DAMAGE));

        ItemStack copy = ItemStack.deserializeBytes(berry.serializeAsBytes());
        check("serialize roundtrip similar", copy.isSimilar(berry));
        check("serialize roundtrip keeps id", ids.is(copy, CustomItems.HEALING_BERRY));
        finish();
    }
}
