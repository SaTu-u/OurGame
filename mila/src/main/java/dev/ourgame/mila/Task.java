package dev.ourgame.mila;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.function.Predicate;

/** A job she does on her owner's command; one at a time. tick() runs every server tick until it returns true. */
abstract class Task {

    protected final Companion c;
    /** Shown under her name and in messages: "добывает железо". */
    final String title;

    Task(Companion c, String title) {
        this.c = c;
        this.title = title;
    }

    abstract String startMessage();

    /** One tick of work; true when the job is over (the task has told the owner how it went). */
    abstract boolean tick(Player owner, long now);

    /** A line for /mila info. */
    String progress() {
        return "";
    }

    /** Called when the job ends for any reason. */
    void stopped() {
    }

    protected double speed() {
        return c.settings().walkSpeed();
    }

    /** Walks to the chest and empties the bag into it. */
    final class Deliver {
        private final Location chest;
        private final Predicate<ItemStack> keep;
        private final boolean dig;
        private int ticks;
        Map<org.bukkit.Material, Integer> stored = Map.of();
        boolean chestFull;

        Deliver(Location chest, Predicate<ItemStack> keep, boolean dig) {
            this.chest = chest;
            this.keep = keep;
            this.dig = dig;
            c.nav.reset();
        }

        /** Navigator.MOVING while on the way, ARRIVED when done, STUCK when the chest is gone. */
        int tick(Player owner) {
            Block b = chest.getBlock();
            if (!(b.getState(false) instanceof Container)) {
                c.say(owner, "Сундук пропал! Покажи мне новый: /mila chest");
                return Navigator.STUCK;
            }
            ticks++;
            int r = c.nav.go(owner, DigPathFinder.withinReach(b, 3.5), c.settings().runSpeed(), dig, false);
            if (r == Navigator.STUCK || ticks > 20 * 90) {
                // she cannot walk there (or it takes too long): she uses her magic, like when she is called
                c.poof();
                c.entity.teleport(Body.safeSpot(b.getLocation().add(0.5, 1, 0.5)));
                c.poof();
                r = Navigator.ARRIVED;
            }
            if (r != Navigator.ARRIVED) return Navigator.MOVING;
            c.face(b.getLocation().add(0.5, 0.5, 0.5));
            c.swing();
            stored = c.storeInto(b, keep);
            chestFull = c.count(it -> !keep.test(it)) > 0;
            return Navigator.ARRIVED;
        }
    }
}
