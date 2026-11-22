# Paper MenuType GUI

## Purpose

`MenuType` opens the **real vanilla screen** without a block: crafting table, anvil, stonecutter, smithing table, grindstone, loom, cartography table, merchant. Unlike a chest GUI (`paper-chest-gui`) the client runs its normal logic (recipe book, anvil renaming, stonecutter list); unlike `nms-custom-menu` no NMS is needed.

---

## Platform Requirements

- Paper 1.21.11 / 26.3 (`MenuType`, the view builders and `AnvilView` have identical signatures; compile-verified on both)
- Opening a screen needs a player client, so this skill is not runtime-tested; builder behavior is taken from the Paper 26.3 source
- Java 21 (1.21.11) / 25 (26.3)
- **Spigot**: Core portable — `MenuType.Typed#builder`, `AnvilView#setMaximumRepairCost` and `Bukkit.createMerchant` exist in Spigot-API 26.3; replace the Adventure titles with strings

---

## Generated Code

### Workstations.java (portable block menus)

```java
// No location(): the menu follows the player, needs no block, and vanilla returns the inputs on close
player.openInventory(MenuType.CRAFTING.builder().title(Component.text("Portable Crafting")).build(player));
```

### PortableAnvil.java (higher cost limit)

```java
AnvilView view = MenuType.ANVIL.builder().title(Component.text("Portable Anvil")).build(player);
view.setMaximumRepairCost(100);   // vanilla limit is 40
// PrepareAnvilEvent recomputes the view, so apply it again there
```

### TradeMenu.java (custom merchant)

```java
Merchant merchant = Bukkit.createMerchant();
merchant.setRecipes(List.of(offer(new ItemStack(Material.EMERALD), new ItemStack(Material.BREAD, 4))));
player.openInventory(MenuType.MERCHANT.builder().merchant(merchant).title(Component.text("Wandering Baker")).build(player));
```

---

## Rules

- Do not call `location(...)` for portable menus; it binds the screen to the real block at that position (a real anvil takes damage)
- `checkReachable` defaults to `false`; with `true` the screen closes when the player walks out of reach
- Block-entity menus (furnace, brewing stand, hopper, shulker box, 9x3 / 9x6 chest, crafter) without `location` use a **temporary block entity that is not in the world**: it does not run (a furnace never smelts) and nothing saves its contents; use `Bukkit.createInventory` for storage
- Track your own screens by player UUID, not by title text

---

## Thread Safety

- `build(player)` and `openInventory` are main-thread only; open screens from commands or scheduled tasks, never from async callbacks
