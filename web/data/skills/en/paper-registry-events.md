# Paper Registry Events

## Purpose

Enchantments, damage types, mob variants, jukebox songs, dialogs and more live in **registries** that freeze once the server has loaded. Paper lets a plugin add to and change them before that, from a `PluginBootstrap`, with no data pack: `compose()` adds entries, `entryAdd()` + `filter` changes existing ones, `LifecycleEvents.TAGS.postFlatten` extends tags, and `RegistryAccess` looks them up at runtime. The template adds a Lifesteal enchantment and a Bleed damage type, raises Sharpness to level 6, and implements the enchantment's behavior in a listener (the API defines what an enchantment is, not what it does).

---

## Platform Requirements

- Paper 1.21.11 / 26.3 (compile-verified on both, templates identical; runtime-verified on Paper 26.3)
- Pure Paper API, no Paperweight needed; requires `paper-plugin.yml` with a `bootstrapper`
- Registry events for `TRIM_MATERIAL`, `TRIM_PATTERN`, `DECORATED_POT_PATTERN`, `SULFUR_CUBE_ARCHETYPE`, `WORLD_CLOCK` and `TIMELINE` exist only on 26.3
- Java 21 (1.21.11) / 25 (26.3)
- **Spigot**: Paper only — `RegistryEvents`, `PluginBootstrap` and `LifecycleEvents` are Paper API; Spigot can only add content through a data pack

---

## Generated Code

### ContentBootstrap.java (new enchantment and damage type)

```java
lifecycle.registerEventHandler(RegistryEvents.ENCHANTMENT.compose().newHandler(event ->
    event.registry().register(ContentKeys.LIFESTEAL, b -> b
        .description(Component.text("Lifesteal"))
        .supportedItems(event.getOrCreateTag(ItemTypeTagKeys.ENCHANTABLE_MELEE_WEAPON))
        .primaryItems(event.getOrCreateTag(ItemTypeTagKeys.ENCHANTABLE_SHARP_WEAPON))
        .weight(2).maxLevel(3)
        .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(15, 9))
        .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(65, 9))
        .anvilCost(4)
        .activeSlots(EquipmentSlotGroup.MAINHAND)
        .exclusiveWith(RegistrySet.keySet(RegistryKey.ENCHANTMENT, EnchantmentKeys.SMITE)))));
```

### Changing vanilla entries and tags

```java
// Sharpness only: without a filter the handler runs for every enchantment
RegistryEvents.ENCHANTMENT.entryAdd().newHandler(e -> e.builder().maxLevel(6)).filter(EnchantmentKeys.SHARPNESS);
// Tag handlers run again on every /minecraft:reload, so they must be repeatable
LifecycleEvents.TAGS.postFlatten(RegistryKey.ENCHANTMENT), event ->
    event.registrar().addToTag(EnchantmentTagKeys.IN_ENCHANTING_TABLE, Set.of(ContentKeys.LIFESTEAL));
LifecycleEvents.TAGS.postFlatten(RegistryKey.DAMAGE_TYPE), event ->
    event.registrar().addToTag(DamageTypeTagKeys.BYPASSES_ARMOR, Set.of(ContentKeys.BLEED));
```

### Runtime lookup and custom damage

```java
DamageType bleed = RegistryAccess.registryAccess().getRegistry(RegistryKey.DAMAGE_TYPE).get(ContentKeys.BLEED);
target.damage(level, DamageSource.builder(bleed).withCausingEntity(attacker).withDirectEntity(attacker).build());
```

---

## Rules

- Bootstrap never throws: every handler catches and records problems, and `onEnable` reports them; there is no Bukkit server during `bootstrap()`
- Keep every key in `ContentKeys` and never rename one after release (items and worlds store keys)
- Implement enchantment effects in listeners, and skip your own follow-up damage to avoid loops
- A custom `messageId` needs a `death.attack.<messageId>` translation from a resource pack
- Runtime-verified on Paper 26.3: entries registered, tags still present after `/minecraft:reload`; Bleed dealt the full 6.0 to a zombie in full diamond armor, while the mob_attack control dealt 1.2

---

## Thread Safety

- Bootstrap handlers run during server startup: no Bukkit, worlds, players or schedulers there
- Runtime lookups and `damage()` are main-thread only; delayed tasks carry only UUIDs and re-fetch entities
