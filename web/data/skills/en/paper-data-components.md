# Paper Data Components

## Purpose

Since 1.20.5 an item is a material plus data components such as `food`, `max_stack_size` and `tooltip_display`. Paper exposes them through `DataComponentTypes` and `ItemStack#setData` / `getData`, so custom items no longer need `ItemMeta` or NMS. Every component has a **prototype** value from the item type, and the three writes differ: `setData` overrides, `unsetData` removes (even the prototype value, for example an apple that cannot be eaten), and `resetData` goes back to the prototype.

---

## Platform Requirements

- Paper 1.21.11 / 26.3 (compile-verified on both, templates identical; runtime-verified on Paper 26.3)
- On 1.21.11 the data component API is still `@ApiStatus.Experimental`; Paper 26.3 removed the marker
- 26.3 added `ATTACK_ANIMATION`, `INTERACT_ANIMATION`, `MOB_VISIBILITY`, `VILLAGER_FOOD` and others and removed `SWING_ANIMATION` and `MAP_COLOR`; `brewing_fuel`, `cooking_fuel`, `compostable` and `block_transformer` are not exposed by Paper yet (use `nms-data-component`)
- Java 21 (1.21.11) / 25 (26.3)
- **Spigot**: Paper only — `io.papermc.paper.datacomponent` does not exist in Spigot-API 26.3 (Spigot's `org.bukkit.inventory.meta.components` is a different, `ItemMeta`-based API)

---

## Generated Code

### CustomItems.java (components only)

```java
ItemStack item = ItemStack.of(Material.GLOW_BERRIES);
item.setData(DataComponentTypes.ITEM_NAME, Component.text("Healing Berry"));
item.setData(DataComponentTypes.FOOD, FoodProperties.food().nutrition(2).saturation(1.0f).canAlwaysEat(true));
item.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
    .consumeSeconds(0.8f).animation(ItemUseAnimation.EAT)
    .addEffect(ConsumeEffect.applyStatusEffects(List.of(new PotionEffect(PotionEffectType.REGENERATION, 100, 1)), 1.0f)));
item.setData(DataComponentTypes.MAX_STACK_SIZE, 16);
item.setData(DataComponentTypes.GLIDER);   // non-valued component: present or not
```

### ItemIds.java (identify with PDC)

```java
item.editPersistentDataContainer(pdc -> pdc.set(key, PersistentDataType.STRING, id));
String id = item.getPersistentDataContainer().get(key, PersistentDataType.STRING);
```

### ItemChecks.java (compare and edit)

```java
a.matchesWithoutData(b, Set.of(DataComponentTypes.DAMAGE, DataComponentTypes.REPAIR_COST), true); // true = ignore count
target.copyDataFrom(skin, COSMETIC::contains);   // copy cosmetic components only
item.resetData(DataComponentTypes.DAMAGE);       // back to the prototype = full repair
item.unsetData(DataComponentTypes.FOOD);         // removes FOOD even though the prototype has it
```

---

## Rules

- Recognize custom items by a PDC id, never by name, lore or model
- Food needs both `FOOD` and `CONSUMABLE`
- Use `ITEM_NAME` for the item's own name (not italic); `CUSTOM_NAME` is what anvils set
- When mixing `ItemMeta` and components, call `editMeta` before `setData` and never write an old `ItemMeta` back
- Runtime-verified on Paper 26.3: 27/27 checks, including set / unset / reset semantics, wear-ignoring comparison and PDC ids surviving serialization

---

## Thread Safety

- `ItemStack` is mutable and not thread-safe: build and edit on the main thread; factories return a new stack every call
- `clone()` on the main thread before handing a stack to async code
