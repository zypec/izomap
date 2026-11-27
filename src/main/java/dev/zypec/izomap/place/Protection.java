package dev.zypec.izomap.place;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Asks the server's protection plugins whether a player may build somewhere, by firing
 * the event a player building there by hand would fire.
 *
 * <p>Region and claim plugins (WorldGuard, GriefPrevention, Lands, Towny and the rest)
 * all listen to these events, so asking through them covers every one of them without
 * depending on any. Whatever the plugin cancels, the placement does not happen.</p>
 *
 * <p>Each event describes a change that is really being made, at the moment it is made,
 * as vanilla does: block loggers record what is placed and protection plugins read the
 * world as it will be. Main thread only.</p>
 */
public final class Protection {

    private Protection() {
    }

    /**
     * Fires the event for a block that has just been put down; {@code replaced} is what
     * stood there before. Returns whether the placement may stay.
     */
    public static boolean mayPlace(Player player, Block placed, BlockState replaced, Block against) {
        var event = new BlockPlaceEvent(placed, replaced, against,
                ItemStack.of(placed.getType()), player, true, EquipmentSlot.HAND);
        return event.callEvent() && event.canBuild();
    }

    /**
     * Fires the event for a hanging entity that has just been spawned on {@code wall}'s
     * {@code face}. Returns whether it may stay.
     */
    public static boolean mayHang(Player player, Hanging hanging, Block wall, BlockFace face) {
        return new HangingPlaceEvent(hanging, player, wall, face, EquipmentSlot.HAND,
                ItemStack.of(Material.ITEM_FRAME)).callEvent();
    }

    /**
     * Whether the player may put something that is not a block, a camera, into this
     * block. Asked as a block placed with {@code item} that leaves the block as it is:
     * every protection plugin answers block placement, and nothing in the world changes
     * whatever the answer.
     */
    public static boolean mayBuild(Player player, Block block, ItemStack item) {
        var event = new BlockPlaceEvent(block, block.getState(), block.getRelative(BlockFace.DOWN),
                item, player, true, EquipmentSlot.HAND);
        return event.callEvent() && event.canBuild();
    }
}
