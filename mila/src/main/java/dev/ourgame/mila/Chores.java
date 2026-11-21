package dev.ourgame.mila;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Small jobs: empty the backpack into a chest, fetch something from a chest, pick up loose items. */
final class Chores {

    private Chores() {
    }

    /** /mila deposit */
    static final class Deposit extends Task {
        private final Deliver deliver;

        Deposit(Companion c, Location chest) {
            super(c, "несёт вещи в сундук");
            this.deliver = new Deliver(chest, Mining::isEquipment, false);
        }

        @Override
        String startMessage() {
            return "Несу всё из рюкзака в сундук (инструменты оставлю себе).";
        }

        @Override
        boolean tick(Player owner, long now) {
            int r = deliver.tick(owner);
            if (r == Navigator.MOVING) return false;
            if (r == Navigator.ARRIVED) {
                c.say(owner, "Положила в сундук: ", Companion.summary(deliver.stored), "."
                        + (deliver.chestFull ? " Всё не влезло, остальное у меня." : ""));
            }
            return true;
        }
    }

    /** /mila fetch <item> [amount]: takes the item from the chest and brings it to her owner. */
    static final class Fetch extends Task {
        private final Location chest;
        private final Material item;
        private final int amount;
        private ItemStack[] carried;
        private int ticks;

        Fetch(Companion c, Location chest, Material item, int amount) {
            super(c, "несёт вещи из сундука");
            this.chest = chest;
            this.item = item;
            this.amount = amount;
            c.nav.reset();
        }

        @Override
        String startMessage() {
            return "Сейчас принесу из сундука!";
        }

        @Override
        boolean tick(Player owner, long now) {
            ticks++;
            if (carried == null) {
                Block b = chest.getBlock();
                if (!(b.getState(false) instanceof Container cont)) {
                    c.say(owner, "Сундук пропал.");
                    return true;
                }
                int r = c.nav.go(owner, DigPathFinder.withinReach(b, 3.5), c.settings().runSpeed(), false, false);
                if (r == Navigator.STUCK || ticks > 20 * 60) {
                    c.say(owner, "Не могу дойти до сундука.");
                    return true;
                }
                if (r != Navigator.ARRIVED) return false;
                c.face(b.getLocation().add(0.5, 0.5, 0.5));
                c.swing();
                c.flashLid(b);
                Inventory inv = cont.getInventory();
                int left = amount;
                java.util.List<ItemStack> got = new java.util.ArrayList<>();
                for (int i = 0; i < inv.getSize() && left > 0; i++) {
                    ItemStack it = inv.getItem(i);
                    if (it == null || it.getType() != item) continue;
                    int k = Math.min(left, it.getAmount());
                    ItemStack part = it.clone();
                    part.setAmount(k);
                    got.add(part);
                    left -= k;
                    if (k >= it.getAmount()) inv.setItem(i, null);
                    else {
                        it.setAmount(it.getAmount() - k);
                        inv.setItem(i, it);
                    }
                }
                if (got.isEmpty()) {
                    c.say(owner, "В сундуке нет такого: ", Companion.item(item), ".");
                    return true;
                }
                carried = got.toArray(new ItemStack[0]);
                c.hold(carried[0].clone());
                c.nav.reset();
                ticks = 0;
                return false;
            }
            int r = c.nav.go(owner, DigPathFinder.near(owner.getX(), owner.getY(), owner.getZ(), 2.0), c.settings().runSpeed(), false, true);
            if (r == Navigator.MOVING && ticks < 20 * 60) return false;
            int total = 0;
            for (ItemStack it : carried) {
                total += it.getAmount();
                for (ItemStack l : owner.getInventory().addItem(it).values()) owner.getWorld().dropItemNaturally(owner.getLocation(), l);
            }
            c.face(owner.getEyeLocation());
            c.swing();
            c.sound("minecraft:entity.item.pickup", 0.6f, 1.2f);
            c.say(owner, "Держи: ", Companion.item(item), " ×" + total + (total < amount ? " (больше не было)" : "") + ".");
            return true;
        }

        @Override
        void stopped() {
            if (carried != null) {                      // interrupted on the way back: keep it in her bag
                for (ItemStack l : c.addToBag(List.of(carried))) {
                    if (c.isSpawned()) c.entity.getWorld().dropItemNaturally(c.entity.getLocation(), l);
                }
                carried = null;
            }
        }
    }

    /** /mila collect: picks up loose items around her (ones dropped by blocks, mobs or her owner). */
    static final class Collect extends Task {
        private final Location center;
        private final int radius;
        private Item target;
        private final java.util.Set<UUID> skip = new java.util.HashSet<>();
        private int collected;
        private int ticks;

        Collect(Companion c, int radius) {
            super(c, "собирает предметы");
            this.center = c.entity.getLocation();
            this.radius = radius;
        }

        @Override
        String startMessage() {
            return "Соберу всё, что валяется вокруг!";
        }

        @Override
        boolean tick(Player owner, long now) {
            ticks++;
            if (now % 5 == 0) collected += c.pickUp(1.8, it -> true);
            if (c.freeSlots() == 0) {
                c.say(owner, "Рюкзак полон! Собрала " + collected + ". Выложить в сундук: /mila deposit");
                return true;
            }
            if (target == null || !target.isValid()) {
                target = nearest(owner.getUniqueId());
                if (target == null) {
                    c.say(owner, collected > 0 ? "Собрала " + collected + " предметов." : "Вокруг ничего не валяется.");
                    return true;
                }
                c.nav.reset();
            }
            int r = c.nav.go(owner, DigPathFinder.near(target.getX(), target.getY(), target.getZ(), 1.2), c.settings().runSpeed(), false, false);
            if (r == Navigator.STUCK || ticks > 20 * 120) {
                skip.add(target.getUniqueId());
                target = null;
            }
            return false;
        }

        private Item nearest(UUID ownerId) {
            Location me = c.entity.getLocation();
            return center.getWorld().getNearbyEntitiesByType(Item.class, center, radius).stream()
                    .filter(i -> !skip.contains(i.getUniqueId()))
                    .filter(i -> i.getThrower() == null || i.getThrower().equals(ownerId))
                    .min(Comparator.comparingDouble(i -> i.getLocation().distanceSquared(me)))
                    .orElse(null);
        }
    }
}
