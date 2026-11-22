# Paper PacketEvents Filter — Code Template

Full, compile-verified source for [`SKILL.md`](SKILL.md). The base package is `com.example.filter`; rename it to your `base_package`. Rules, thread safety and the fallback table are in `SKILL.md`.

## `FilterSnapshot.java`(immutable snapshot, no packet types)

```java
package com.example.filter.state;

import org.bukkit.Material;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the Netty-thread listeners may read, as one immutable value.
 * The main thread builds a new instance and publishes it through {@link FilterState};
 * listeners read {@code FilterState#current()} once per packet and never see a torn state.
 */
public record FilterSnapshot(
        Set<UUID> chatHidden,
        String allowedPrefix,
        Set<UUID> creative,
        Set<UUID> quiet,
        Set<String> mutedSounds,
        Set<String> mutedParticles,
        Set<Material> loreMaterials,
        String loreText) {

    public static final FilterSnapshot EMPTY = new FilterSnapshot(
            Set.of(), "", Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), "");

    public FilterSnapshot {
        chatHidden = Set.copyOf(chatHidden);
        creative = Set.copyOf(creative);
        quiet = Set.copyOf(quiet);
        mutedSounds = Set.copyOf(mutedSounds);
        mutedParticles = Set.copyOf(mutedParticles);
        loreMaterials = Set.copyOf(loreMaterials);
    }

    public FilterSnapshot withChatHidden(UUID id, boolean on) {
        return new FilterSnapshot(toggled(chatHidden, id, on), allowedPrefix, creative, quiet,
                mutedSounds, mutedParticles, loreMaterials, loreText);
    }

    public FilterSnapshot withCreative(UUID id, boolean on) {
        return new FilterSnapshot(chatHidden, allowedPrefix, toggled(creative, id, on), quiet,
                mutedSounds, mutedParticles, loreMaterials, loreText);
    }

    public FilterSnapshot withQuiet(UUID id, boolean on) {
        return new FilterSnapshot(chatHidden, allowedPrefix, creative, toggled(quiet, id, on),
                mutedSounds, mutedParticles, loreMaterials, loreText);
    }

    public FilterSnapshot withChatRule(String newAllowedPrefix) {
        return new FilterSnapshot(chatHidden, newAllowedPrefix, creative, quiet,
                mutedSounds, mutedParticles, loreMaterials, loreText);
    }

    public FilterSnapshot withMuted(Set<String> sounds, Set<String> particles) {
        return new FilterSnapshot(chatHidden, allowedPrefix, creative, quiet,
                sounds, particles, loreMaterials, loreText);
    }

    public FilterSnapshot withLore(Set<Material> materials, String text) {
        return new FilterSnapshot(chatHidden, allowedPrefix, creative, quiet,
                mutedSounds, mutedParticles, materials, text);
    }

    /** Removes a player from every per-player set (quit). */
    public FilterSnapshot withoutPlayer(UUID id) {
        return new FilterSnapshot(toggled(chatHidden, id, false), allowedPrefix,
                toggled(creative, id, false), toggled(quiet, id, false),
                mutedSounds, mutedParticles, loreMaterials, loreText);
    }

    private static Set<UUID> toggled(Set<UUID> source, UUID id, boolean on) {
        if (source.contains(id) == on) {
            return source;
        }
        Set<UUID> copy = new HashSet<>(source);
        if (on) {
            copy.add(id);
        } else {
            copy.remove(id);
        }
        return copy;
    }
}
```

## `FilterState.java`(volatile publication point)

```java
package com.example.filter.state;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Single publication point for {@link FilterSnapshot}. Written on the main thread only;
 * read from any thread. One instance per plugin, passed by constructor (no static state).
 */
public final class FilterState {

    private final AtomicReference<FilterSnapshot> ref = new AtomicReference<>(FilterSnapshot.EMPTY);

    /** Safe from any thread (including Netty). Read once per packet and keep the local reference. */
    public FilterSnapshot current() {
        return ref.get();
    }

    /** Main thread only: derive a new snapshot from the current one and publish it atomically. */
    public void update(UnaryOperator<FilterSnapshot> change) {
        ref.updateAndGet(change);
    }
}
```

## `FailOpenGuard.java`(fail-open: disable and log only once)

```java
package com.example.filter.state;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One guard per filter. {@link #trip(Throwable)} disables that filter for the rest of the session and
 * logs once; every listener checks {@link #active()} first. Several Netty threads may trip at once.
 */
public final class FailOpenGuard {

    private final Logger log;
    private final String feature;
    private final AtomicBoolean tripped = new AtomicBoolean();

    public FailOpenGuard(Logger log, String feature) {
        this.log = log;
        this.feature = feature;
    }

    public boolean active() {
        return !tripped.get();
    }

    public void trip(Throwable cause) {
        if (tripped.compareAndSet(false, true)) {
            log.log(Level.WARNING, "Packet filter '" + feature
                    + "' failed and is now disabled (packets pass through unchanged). "
                    + "Check that the packet library supports this Minecraft version.", cause);
        }
    }
}
```

## `ChatPacketFilter.java`(recipe 1: filter system chat, PacketEvents)

```java
package com.example.filter.packet;

import com.example.filter.state.FailOpenGuard;
import com.example.filter.state.FilterSnapshot;
import com.example.filter.state.FilterState;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.UUID;

/**
 * Hides system chat from players in {@code chatHidden}, except messages that start with the allowed
 * prefix and action bar (overlay) messages.
 *
 * <p>Runs on the Netty event loop: reads {@link FilterState#current()} only, no Bukkit API.
 * Fail-open: any exception lets the packet through and disables this filter.
 */
public final class ChatPacketFilter extends PacketListenerAbstract {

    private final FilterState state;
    private final FailOpenGuard guard;

    public ChatPacketFilter(FilterState state, FailOpenGuard guard) {
        super(PacketListenerPriority.NORMAL);
        this.state = state;
        this.guard = guard;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!guard.active() || event.getPacketType() != PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) {
            return;
        }
        try {
            User user = event.getUser();
            UUID recipient = user == null ? null : user.getUUID();
            if (recipient == null) {
                return; // still logging in
            }
            FilterSnapshot snapshot = state.current();
            if (!snapshot.chatHidden().contains(recipient)) {
                return;
            }
            WrapperPlayServerSystemChatMessage wrapper = new WrapperPlayServerSystemChatMessage(event);
            if (wrapper.isOverlay()) {
                return;
            }
            String plain = PlainTextComponentSerializer.plainText().serialize(wrapper.getMessage());
            if (!plain.startsWith(snapshot.allowedPrefix())) {
                event.setCancelled(true); // last statement: only reached when everything above worked
            }
        } catch (RuntimeException | LinkageError e) {
            guard.trip(e);
        }
    }
}
```

## `LorePainter.java`(handles clones only, no packet types)

```java
package com.example.filter.packet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds modified <b>clones</b> of items. Never edits the stack it is given: that object may be the
 * server's own stack. The injected line carries an {@code insertion} marker so stripping is exact and
 * idempotent (do not compare line prefixes: players and other plugins can write identical text).
 */
public final class LorePainter {

    public static final String MARKER = "example-filter-lore";

    /** @return a clone with one extra lore line, or null when there is nothing to change */
    public @Nullable ItemStack paint(@Nullable ItemStack item, String text) {
        if (item == null || item.getType().isAir() || text.isEmpty()) {
            return null;
        }
        Component line = Component.text(text).decoration(TextDecoration.ITALIC, false).insertion(MARKER);
        ItemStack copy = item.clone();
        copy.editMeta(meta -> {
            List<Component> lore = withoutOurs(meta.lore());
            lore.add(line);
            meta.lore(lore);
        });
        return copy;
    }

    /** @return a clone without our lore line, or null when the item did not carry it */
    public @Nullable ItemStack strip(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        List<Component> before = meta.lore();
        if (before == null) {
            return null;
        }
        List<Component> after = withoutOurs(before);
        if (after.size() == before.size()) {
            return null;
        }
        ItemStack copy = item.clone();
        copy.editMeta(m -> m.lore(after.isEmpty() ? null : after));
        return copy;
    }

    private static List<Component> withoutOurs(@Nullable List<Component> lore) {
        List<Component> out = new ArrayList<>();
        if (lore != null) {
            for (Component line : lore) {
                if (!MARKER.equals(line.insertion())) {
                    out.add(line);
                }
            }
        }
        return out;
    }
}
```

## `LoreInjector.java`(recipe 2: per-viewer lore, PacketEvents)

```java
package com.example.filter.packet;

import com.example.filter.state.FailOpenGuard;
import com.example.filter.state.FilterSnapshot;
import com.example.filter.state.FilterState;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adds a per-viewer lore line to items in SET_SLOT / WINDOW_ITEMS, and strips it again from creative
 * inventory actions. Creative viewers are skipped (the client echoes what it sees back to the server).
 *
 * <p>Netty thread: reads the {@link FilterState} snapshot only. {@code SpigotConversionUtil} returns an
 * independent Bukkit stack and {@link LorePainter} clones before editing, so the server-side stack is
 * never touched. The viewer is identified through {@link User#getUUID()}, not {@code event.getPlayer()}:
 * before PLAY there is no Bukkit player behind the connection.
 */
public final class LoreInjector implements PacketListener {

    private final FilterState state;
    private final FailOpenGuard guard;
    private final LorePainter painter;

    public LoreInjector(FilterState state, FailOpenGuard guard, LorePainter painter) {
        this.state = state;
        this.guard = guard;
        this.painter = painter;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        boolean setSlot = event.getPacketType() == PacketType.Play.Server.SET_SLOT;
        if (!guard.active() || (!setSlot && event.getPacketType() != PacketType.Play.Server.WINDOW_ITEMS)) {
            return;
        }
        try {
            User user = event.getUser();
            UUID viewer = user == null ? null : user.getUUID();
            FilterSnapshot snapshot = state.current();
            if (viewer == null || snapshot.loreText().isEmpty() || snapshot.creative().contains(viewer)) {
                return;
            }
            boolean changed = setSlot
                    ? paintSetSlot(new WrapperPlayServerSetSlot(event), snapshot)
                    : paintWindowItems(new WrapperPlayServerWindowItems(event), snapshot);
            if (changed) {
                event.markForReEncode(true); // only after the wrapper was fully updated
            }
        } catch (RuntimeException | LinkageError e) {
            guard.trip(e);
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!guard.active() || event.getPacketType() != PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) {
            return;
        }
        try {
            WrapperPlayClientCreativeInventoryAction wrapper = new WrapperPlayClientCreativeInventoryAction(event);
            ItemStack stripped = painter.strip(SpigotConversionUtil.toBukkitItemStack(wrapper.getItemStack()));
            if (stripped != null) {
                wrapper.setItemStack(SpigotConversionUtil.fromBukkitItemStack(stripped));
                event.markForReEncode(true);
            }
        } catch (RuntimeException | LinkageError e) {
            guard.trip(e);
        }
    }

    private boolean paintSetSlot(WrapperPlayServerSetSlot wrapper, FilterSnapshot snapshot) {
        ItemStack painted = paint(wrapper.getItem(), snapshot);
        if (painted == null) {
            return false;
        }
        wrapper.setItem(SpigotConversionUtil.fromBukkitItemStack(painted));
        return true;
    }

    private boolean paintWindowItems(WrapperPlayServerWindowItems wrapper, FilterSnapshot snapshot) {
        boolean changed = false;
        List<com.github.retrooper.packetevents.protocol.item.ItemStack> out = new ArrayList<>();
        for (com.github.retrooper.packetevents.protocol.item.ItemStack slot : wrapper.getItems()) {
            ItemStack painted = paint(slot, snapshot);
            out.add(painted == null ? slot : SpigotConversionUtil.fromBukkitItemStack(painted));
            changed |= painted != null;
        }
        if (changed) {
            wrapper.setItems(out);
        }
        Optional<com.github.retrooper.packetevents.protocol.item.ItemStack> carried = wrapper.getCarriedItem();
        if (carried.isPresent()) {
            ItemStack painted = paint(carried.get(), snapshot);
            if (painted != null) {
                wrapper.setCarriedItem(SpigotConversionUtil.fromBukkitItemStack(painted));
                changed = true;
            }
        }
        return changed;
    }

    private @Nullable ItemStack paint(com.github.retrooper.packetevents.protocol.item.ItemStack packetItem,
                                      FilterSnapshot snapshot) {
        ItemStack item = SpigotConversionUtil.toBukkitItemStack(packetItem);
        if (!snapshot.loreMaterials().contains(item.getType())) {
            return null;
        }
        return painter.paint(item, snapshot.loreText());
    }
}
```

## `QuietPacketFilter.java`(recipe 3: hide particles/sounds for a single player, PacketEvents)

```java
package com.example.filter.packet;

import com.example.filter.state.FailOpenGuard;
import com.example.filter.state.FilterSnapshot;
import com.example.filter.state.FilterState;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.sound.Sound;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntitySoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;

import java.util.UUID;

/**
 * Cancels selected particles and sounds for players in {@code quiet}. Particle and sound keys are the
 * namespaced ids ({@code minecraft:sweep_attack}, {@code minecraft:entity.player.attack.sweep}).
 * Netty thread, snapshot-only, fail-open.
 */
public final class QuietPacketFilter extends PacketListenerAbstract {

    private final FilterState state;
    private final FailOpenGuard guard;

    public QuietPacketFilter(FilterState state, FailOpenGuard guard) {
        super(PacketListenerPriority.NORMAL);
        this.state = state;
        this.guard = guard;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (!guard.active()
                || (type != PacketType.Play.Server.PARTICLE
                && type != PacketType.Play.Server.SOUND_EFFECT
                && type != PacketType.Play.Server.ENTITY_SOUND_EFFECT)) {
            return;
        }
        try {
            User user = event.getUser();
            UUID receiver = user == null ? null : user.getUUID();
            FilterSnapshot snapshot = state.current();
            if (receiver == null || !snapshot.quiet().contains(receiver)) {
                return;
            }
            if (shouldDrop(event, type, snapshot)) {
                event.setCancelled(true);
            }
        } catch (RuntimeException | LinkageError e) {
            guard.trip(e);
        }
    }

    private static boolean shouldDrop(PacketSendEvent event, PacketTypeCommon type, FilterSnapshot snapshot) {
        if (type == PacketType.Play.Server.PARTICLE) {
            String key = new WrapperPlayServerParticle(event).getParticle().getType().getName().toString();
            return snapshot.mutedParticles().contains(key);
        }
        Sound sound = type == PacketType.Play.Server.SOUND_EFFECT
                ? new WrapperPlayServerSoundEffect(event).getSound()
                : new WrapperPlayServerEntitySoundEffect(event).getSound();
        return sound != null && snapshot.mutedSounds().contains(sound.getSoundId().toString());
    }
}
```

## `PacketEventsBridge.java`(the only registration point that touches PacketEvents)

```java
package com.example.filter.integration;

import com.example.filter.packet.ChatPacketFilter;
import com.example.filter.packet.LoreInjector;
import com.example.filter.packet.LorePainter;
import com.example.filter.packet.QuietPacketFilter;
import com.example.filter.state.FailOpenGuard;
import com.example.filter.state.FilterState;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.EventManager;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * The only class (with the listeners) that touches PacketEvents types. <b>It must never be loaded when
 * the packetevents plugin is absent</b>: the types are compileOnly, so loading it would throw
 * {@link NoClassDefFoundError}. {@link PacketEventsHook} guards every call.
 *
 * <p>Only registers and unregisters listeners. Never call {@code load()}, {@code init()} or
 * {@code terminate()}: the API lifecycle belongs to the packetevents plugin and other plugins share it.
 */
final class PacketEventsBridge {

    private PacketEventsBridge() {
    }

    /** @return opaque handles to pass back to {@link #unregister(List)} */
    static List<Object> register(FilterState state, Logger log) {
        EventManager events = PacketEvents.getAPI().getEventManager();
        List<PacketListenerCommon> registered = new ArrayList<>();
        try {
            registered.add(events.registerListener(
                    new ChatPacketFilter(state, new FailOpenGuard(log, "chat"))));
            registered.add(events.registerListener(
                    new QuietPacketFilter(state, new FailOpenGuard(log, "quiet"))));
            registered.add(events.registerListener(
                    new LoreInjector(state, new FailOpenGuard(log, "lore"), new LorePainter()),
                    PacketListenerPriority.NORMAL));
        } catch (RuntimeException | LinkageError e) {
            registered.forEach(events::unregisterListener); // no half-registered state
            throw e;
        }
        return List.copyOf(registered);
    }

    static void unregister(List<Object> handles) {
        EventManager events = PacketEvents.getAPI().getEventManager();
        for (Object handle : handles) {
            events.unregisterListener((PacketListenerCommon) handle);
        }
    }
}
```

## `PacketEventsHook.java`(soft-dependency entry point, no PacketEvents types)

```java
package com.example.filter.integration;

import com.example.filter.state.FilterState;
import org.bukkit.Bukkit;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Soft-dependency entry point for packetevents. Has no PacketEvents types in fields, signatures or
 * lambdas; everything that touches them is in {@link PacketEventsBridge}, reached only after
 * {@code isPluginEnabled} is true and wrapped in a {@link LinkageError} catch.
 * Without packetevents the plugin logs one warning and the rest keeps working.
 */
public final class PacketEventsHook {

    public static final String PLUGIN_NAME = "packetevents";

    private final Logger log;
    private @Nullable List<Object> handles;

    public PacketEventsHook(Logger log) {
        this.log = log;
    }

    /** Call from {@code onEnable}. @return whether the filters are active */
    public boolean install(FilterState state) {
        if (handles != null) {
            return true; // already registered: never register twice
        }
        if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN_NAME)) {
            log.warning("packetevents is not installed: packet filters are disabled; everything else works.");
            return false;
        }
        try {
            handles = PacketEventsBridge.register(state, log);
            return true;
        } catch (LinkageError | RuntimeException e) {
            log.log(Level.WARNING, "packetevents is enabled but the filters could not be registered.", e);
            return false;
        }
    }

    /** Call from {@code onDisable}. No-op when nothing was registered. */
    public void uninstall() {
        List<Object> registered = handles;
        handles = null;
        if (registered == null) {
            return;
        }
        try {
            PacketEventsBridge.unregister(registered);
        } catch (LinkageError | RuntimeException e) {
            // packetevents may have been disabled first, taking its listeners with it
            log.log(Level.FINE, "Could not unregister packet filters (packetevents already disabled?)", e);
        }
    }

    public boolean installed() {
        return handles != null;
    }
}
```

## `ProtocolLibLoreAdapter.java`(ProtocolLib variant: PacketAdapter)

```java
package com.example.filter.packet;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.example.filter.state.FailOpenGuard;
import com.example.filter.state.FilterSnapshot;
import com.example.filter.state.FilterState;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ProtocolLib variant of {@link LoreInjector} (outgoing SET_SLOT / WINDOW_ITEMS only).
 *
 * <p>Same rules: snapshot-only reads, clone before editing, skip creative, fail-open. ProtocolLib
 * listeners registered with {@code addPacketListener} run on the Netty thread; if you move work to
 * ProtocolLib's async manager it runs on a ProtocolLib pool thread, still never the main thread.
 */
public final class ProtocolLibLoreAdapter extends PacketAdapter {

    private final FilterState state;
    private final FailOpenGuard guard;
    private final LorePainter painter;

    public ProtocolLibLoreAdapter(Plugin plugin, FilterState state, FailOpenGuard guard, LorePainter painter) {
        super(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.SET_SLOT, PacketType.Play.Server.WINDOW_ITEMS);
        this.state = state;
        this.guard = guard;
        this.painter = painter;
    }

    @Override
    public void onPacketSending(PacketEvent event) {
        // During the configuration->play handshake the "player" is a TemporaryPlayer proxy
        // whose getUniqueId() throws; skip those packets (resend the inventory a tick after join).
        if (!guard.active() || event.isPlayerTemporary()) {
            return;
        }
        try {
            UUID viewer = event.getPlayer().getUniqueId(); // the only Player call allowed here
            FilterSnapshot snapshot = state.current();
            if (snapshot.loreText().isEmpty() || snapshot.creative().contains(viewer)) {
                return;
            }
            PacketContainer packet = event.getPacket();
            if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
                paintSlot(packet, snapshot);
            } else {
                paintWindow(packet, snapshot);
            }
        } catch (RuntimeException | LinkageError e) {
            guard.trip(e);
        }
    }

    private void paintSlot(PacketContainer packet, FilterSnapshot snapshot) {
        ItemStack painted = paint(packet.getItemModifier().read(0), snapshot);
        if (painted != null) {
            packet.getItemModifier().write(0, painted);
        }
    }

    private void paintWindow(PacketContainer packet, FilterSnapshot snapshot) {
        List<ItemStack> items = packet.getItemListModifier().read(0);
        List<ItemStack> out = new ArrayList<>(items.size());
        boolean changed = false;
        for (ItemStack item : items) {
            ItemStack painted = paint(item, snapshot);
            out.add(painted == null ? item : painted);
            changed |= painted != null;
        }
        if (changed) {
            packet.getItemListModifier().write(0, out);
        }
    }

    private @Nullable ItemStack paint(@Nullable ItemStack item, FilterSnapshot snapshot) {
        if (item == null || !snapshot.loreMaterials().contains(item.getType())) {
            return null;
        }
        return painter.paint(item, snapshot.loreText()); // a clone; never edit `item`
    }
}
```

## `ProtocolLibBridge.java`

```java
package com.example.filter.integration;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketListener;
import com.example.filter.packet.LorePainter;
import com.example.filter.packet.ProtocolLibLoreAdapter;
import com.example.filter.state.FailOpenGuard;
import com.example.filter.state.FilterState;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/** The only class that touches ProtocolLib types besides the adapter. Loaded only via {@link ProtocolLibHook}. */
final class ProtocolLibBridge {

    private ProtocolLibBridge() {
    }

    static Object register(Plugin plugin, FilterState state, Logger log) {
        PacketListener adapter = new ProtocolLibLoreAdapter(
                plugin, state, new FailOpenGuard(log, "lore"), new LorePainter());
        ProtocolLibrary.getProtocolManager().addPacketListener(adapter);
        return adapter;
    }

    static void unregister(Object handle) {
        ProtocolLibrary.getProtocolManager().removePacketListener((PacketListener) handle);
    }
}
```

## `ProtocolLibHook.java`

```java
package com.example.filter.integration;

import com.example.filter.state.FilterState;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Same shape as {@link PacketEventsHook}, for ProtocolLib (plugin name {@code ProtocolLib}). */
public final class ProtocolLibHook {

    public static final String PLUGIN_NAME = "ProtocolLib";

    private final Plugin plugin;
    private final Logger log;
    private @Nullable Object handle;

    public ProtocolLibHook(Plugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public boolean install(FilterState state) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled(PLUGIN_NAME)) {
            log.warning("ProtocolLib is not installed: packet filters are disabled.");
            return false;
        }
        try {
            handle = ProtocolLibBridge.register(plugin, state, log);
            return true;
        } catch (LinkageError | RuntimeException e) {
            log.log(Level.WARNING, "ProtocolLib is enabled but the adapter could not be registered.", e);
            return false;
        }
    }

    public void uninstall() {
        Object registered = handle;
        handle = null;
        if (registered == null) {
            return;
        }
        try {
            ProtocolLibBridge.unregister(registered);
        } catch (LinkageError | RuntimeException e) {
            log.log(Level.FINE, "Could not unregister ProtocolLib adapter (ProtocolLib already disabled?)", e);
        }
    }
}
```

## `GameModeTracker.java`(main thread publishes the snapshot; no packet types)

```java
package com.example.filter;

import com.example.filter.state.FilterState;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Main thread -> Netty thread bridge. Netty listeners cannot call {@code Player#getGameMode()}, so this
 * listener publishes the creative flag into the snapshot, then resends the inventory so the client
 * redraws with or without the injected lore.
 */
public final class GameModeTracker implements Listener {

    private final Plugin plugin;
    private final FilterState state;

    public GameModeTracker(Plugin plugin, FilterState state) {
        this.plugin = plugin;
        this.state = state;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        state.update(s -> s.withCreative(id, creative));
        // The login-time SET_SLOT / WINDOW_ITEMS were skipped (no player yet): redraw one tick later.
        plugin.getServer().getScheduler().runTask(plugin, player::updateInventory);
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        boolean creative = event.getNewGameMode() == GameMode.CREATIVE; // event fires before the change
        state.update(s -> s.withCreative(id, creative));
        plugin.getServer().getScheduler().runTask(plugin, player::updateInventory);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        state.update(s -> s.withoutPlayer(id));
    }
}
```

## `FilterPlugin.java`(lifecycle)

```java
package com.example.filter;

import com.example.filter.integration.PacketEventsHook;
import com.example.filter.integration.ProtocolLibHook;
import com.example.filter.state.FilterState;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;

public final class FilterPlugin extends JavaPlugin {

    private final FilterState state = new FilterState();
    private PacketEventsHook packetEvents;
    private ProtocolLibHook protocolLib;

    @Override
    public void onEnable() {
        // Publish configuration as an immutable snapshot (rebuild and republish on reload).
        state.update(s -> s
                .withChatRule("[Server] ")
                .withMuted(Set.of("minecraft:entity.player.attack.sweep"), Set.of("minecraft:sweep_attack"))
                .withLore(Set.of(Material.DIAMOND, Material.EMERALD), "Worth: 100"));

        getServer().getPluginManager().registerEvents(new GameModeTracker(this, state), this);

        // PacketEvents first; ProtocolLib only when PacketEvents is not there. Never run both for the same job.
        packetEvents = new PacketEventsHook(getLogger());
        if (!packetEvents.install(state)) {
            protocolLib = new ProtocolLibHook(this);
            protocolLib.install(state);
        }
    }

    @Override
    public void onDisable() {
        if (packetEvents != null) {
            packetEvents.uninstall();
        }
        if (protocolLib != null) {
            protocolLib.uninstall();
        }
    }
}
```
