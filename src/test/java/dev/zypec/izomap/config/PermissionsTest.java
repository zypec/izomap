package dev.zypec.izomap.config;

import dev.zypec.izomap.map.GridLayouts;
import dev.zypec.izomap.map.GridOption;
import dev.zypec.izomap.render.AspectRatio;
import dev.zypec.izomap.render.ColorFilter;
import dev.zypec.izomap.render.PhotoStyle;
import dev.zypec.izomap.render.SkyOption;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a player holding nothing may still do, and what the two shapes of node — the
 * whole area and the single option — each let through.
 *
 * <p>{@link Player} is an interface, so a proxy answering the two permission methods is
 * enough.</p>
 */
class PermissionsTest {

    private static final ColorFilter WARM = new ColorFilter("WARM", List.of());

    /**
     * A player holding exactly these nodes.
     */
    private static Player playerWith(String... nodes) {
        var held = Set.of(nodes);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "hasPermission" -> args[0] instanceof String node && held.contains(node);
            case "getEffectivePermissions" -> {
                Set<PermissionAttachmentInfo> infos = new LinkedHashSet<>();
                for (var node : nodes) {
                    infos.add(new PermissionAttachmentInfo((Player) proxy, node, null, true));
                }
                yield infos;
            }
            case "toString" -> "test-player";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
        };
        return (Player) Proxy.newProxyInstance(
                PermissionsTest.class.getClassLoader(), new Class<?>[]{Player.class}, handler);
    }

    @Test
    @DisplayName("the cheapest value of every setting needs no permission")
    void defaultsAreFree() {
        var nobody = playerWith();
        assertTrue(Permissions.style(nobody, PhotoStyle.FAST));
        assertTrue(Permissions.filter(nobody, ColorFilter.ORIGINAL));
        assertTrue(Permissions.sky(nobody, SkyOption.NONE));
    }

    @Test
    @DisplayName("the expensive style is not free")
    void sharpNeedsANode() {
        assertFalse(Permissions.style(playerWith(), PhotoStyle.SHARP));
        assertTrue(Permissions.style(playerWith(Permissions.STYLE_SHARP), PhotoStyle.SHARP));
    }

    @Test
    @DisplayName("the area node grants every option in it")
    void areaGrantsAll() {
        var player = playerWith(Permissions.FILTER, Permissions.SKY);
        assertTrue(Permissions.filter(player, WARM));
        assertTrue(Permissions.sky(player, SkyOption.NIGHT));
        assertTrue(Permissions.sky(player, SkyOption.DAWN));
    }

    @Test
    @DisplayName("an option node grants that option and no other")
    void optionGrantsOne() {
        var player = playerWith("izomap.sky.NIGHT");
        assertTrue(Permissions.sky(player, SkyOption.NIGHT));
        assertFalse(Permissions.sky(player, SkyOption.DAWN));
        assertFalse(Permissions.filter(player, WARM));
    }

    @Test
    @DisplayName("a filter is matched by its id, not its display name")
    void filterMatchesById() {
        assertTrue(Permissions.filter(playerWith("izomap.filter.WARM"), WARM));
        assertFalse(Permissions.filter(playerWith("izomap.filter.Sıcak"), WARM));
    }

    @Test
    @DisplayName("the tile allowance counts tiles, not grids")
    void gridCountsTiles() {
        var player = playerWith();
        assertTrue(Permissions.grid(player, new GridOption(2, 2), 4));   // 4 tiles
        assertFalse(Permissions.grid(player, new GridOption(3, 3), 4));  // 9 tiles
        assertTrue(Permissions.grid(playerWith("izomap.max_map_tiles.9"), new GridOption(3, 3), 4));
        assertTrue(Permissions.grid(playerWith("izomap.max_map_tiles.unlimited"),
                new GridOption(16, 9), 4));
    }

    @Test
    @DisplayName("the allowance filters the grid list")
    void gridListIsFiltered() {
        var options = GridLayouts.allowedFor(playerWith(), AspectRatio.RATIO_1_1, 4);
        assertEquals(List.of(new GridOption(1, 1), new GridOption(2, 2)), options);
    }

    @Test
    @DisplayName("a ratio always keeps its smallest grid, however tight the allowance")
    void smallestGridSurvives() {
        // 16:9 starts at eight tiles, so a limit of four would leave it with none.
        var options = GridLayouts.allowedFor(playerWith(), AspectRatio.RATIO_16_9, 4);
        assertEquals(List.of(new GridOption(4, 2)), options);
    }
}
