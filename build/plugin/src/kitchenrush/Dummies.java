package kitchenrush;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.level.GameType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test stand-ins: mannequins wearing the summoner's skin, counted as players for team distribution.
 * Each one also gets a tab-list row (a client-side player-info entry with the same name and skin), so it
 * shows up in the tab list with its team prefix like a real player.
 */
final class Dummies {
    static final String TAG = "kitchenrush_dummy", NAME_TAG = "kitchenrush_name:";

    private record TabEntry(UUID id, String name, PropertyMap skin) {
    }

    /** Mannequin uuid -> its tab-list row. */
    private final Map<UUID, TabEntry> tab = new LinkedHashMap<>();
    private int counter;

    List<Mannequin> spawn(Player owner, int count) {
        List<Mannequin> out = new ArrayList<>();
        Location base = owner.getLocation();
        ResolvableProfile skin = ResolvableProfile.resolvableProfile(owner.getPlayerProfile());
        Multimap<String, Property> props = LinkedHashMultimap.create();
        for (ProfileProperty pp : owner.getPlayerProfile().getProperties()) {
            props.put(pp.getName(), new Property(pp.getName(), pp.getValue(), pp.getSignature()));
        }
        PropertyMap tabSkin = new PropertyMap(props);
        List<TabEntry> added = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * i / Math.max(1, count);
            Location at = base.clone().add(Math.cos(angle) * 2.5, 0, Math.sin(angle) * 2.5);
            String name = "더미" + (++counter);
            Mannequin m = base.getWorld().spawn(at, Mannequin.class, e -> {
                e.setProfile(skin);
                e.addScoreboardTag(TAG);
                e.addScoreboardTag(NAME_TAG + name);
                e.customName(Component.text(name));
                e.setCustomNameVisible(true);
                e.setDescription(Component.empty());
                e.setImmovable(true);
                e.setInvulnerable(true);
                e.setPersistent(true);
            });
            TabEntry entry = new TabEntry(UUID.randomUUID(), name, tabSkin);
            tab.put(m.getUniqueId(), entry);
            added.add(entry);
            out.add(m);
        }
        sendAdd(Bukkit.getOnlinePlayers(), added);
        return out;
    }

    /** Re-sends every dummy's tab row, e.g. to a player who just joined. */
    void showTab(Player p) {
        sendAdd(List.of(p), new ArrayList<>(tab.values()));
    }

    static List<Entity> all(World world) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : world.getEntitiesByClass(Mannequin.class)) {
            if (e.getScoreboardTags().contains(TAG)) out.add(e);
        }
        return out;
    }

    int removeAll(World world) {
        List<Entity> list = all(world);
        list.forEach(Entity::remove);
        if (!tab.isEmpty()) {
            var packet = new ClientboundPlayerInfoRemovePacket(tab.values().stream().map(TabEntry::id).toList());
            for (Player p : Bukkit.getOnlinePlayers()) ((CraftPlayer) p).getHandle().connection.send(packet);
        }
        tab.clear();
        counter = 0;
        return list.size();
    }

    private static void sendAdd(Collection<? extends Player> to, List<TabEntry> entries) {
        if (entries.isEmpty() || to.isEmpty()) return;
        List<ClientboundPlayerInfoUpdatePacket.Entry> rows = new ArrayList<>();
        for (TabEntry e : entries) {
            rows.add(new ClientboundPlayerInfoUpdatePacket.Entry(e.id(), new GameProfile(e.id(), e.name(), e.skin()),
                    true, 0, GameType.ADVENTURE, null, true, 0, null));
        }
        var packet = new ClientboundPlayerInfoUpdatePacket(EnumSet.of(
                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY), rows);
        for (Player p : to) ((CraftPlayer) p).getHandle().connection.send(packet);
    }
}
