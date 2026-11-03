package kitchenrush;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Test stand-ins: mannequins wearing the summoner's skin, counted as players for team distribution. */
final class Dummies {
    static final String TAG = "kitchenrush_dummy", NAME_TAG = "kitchenrush_name:";

    private int counter;

    List<Mannequin> spawn(Player owner, int count) {
        List<Mannequin> out = new ArrayList<>();
        Location base = owner.getLocation();
        ResolvableProfile skin = ResolvableProfile.resolvableProfile(owner.getPlayerProfile());
        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * i / Math.max(1, count);
            Location at = base.clone().add(Math.cos(angle) * 2.5, 0, Math.sin(angle) * 2.5);
            String name = "더미" + (++counter);
            out.add(base.getWorld().spawn(at, Mannequin.class, m -> {
                m.setProfile(skin);
                m.addScoreboardTag(TAG);
                m.addScoreboardTag(NAME_TAG + name);
                m.customName(Component.text(name));
                m.setCustomNameVisible(true);
                m.setDescription(Component.empty());
                m.setImmovable(true);
                m.setInvulnerable(true);
                m.setPersistent(true);
            }));
        }
        return out;
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
        counter = 0;
        return list.size();
    }
}
