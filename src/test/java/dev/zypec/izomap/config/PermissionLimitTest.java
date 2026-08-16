package dev.zypec.izomap.config;

import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules here are the kind a server owner discovers by being surprised: a permission
 * replacing the configured limit rather than raising it, several of them taking the
 * largest, and a node that is not a number belonging to somebody else.
 *
 * <p>A {@link Player} is an interface, so a proxy that answers one method is enough; the
 * class under test asks for nothing else.</p>
 */
class PermissionLimitTest {

    private static final String PREFIX = "izomap.max_photos_by_camera";

    /**
     * A player holding exactly these permission nodes, all granted.
     */
    private static Player playerWith(String... nodes) {
        return playerWith(true, nodes);
    }

    private static Player playerWith(boolean granted, String... nodes) {
        var handler = new java.lang.reflect.InvocationHandler() {
            private final Set<PermissionAttachmentInfo> permissions = new LinkedHashSet<>();

            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                if ("getEffectivePermissions".equals(method.getName())) {
                    if (permissions.isEmpty()) {
                        for (var node : nodes) {
                            permissions.add(new PermissionAttachmentInfo(
                                    (Player) proxy, node, null, granted));
                        }
                    }
                    return permissions;
                }
                if ("toString".equals(method.getName())) return "test-player";
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return proxy == args[0];
                return null;
            }
        };
        return (Player) Proxy.newProxyInstance(
                PermissionLimitTest.class.getClassLoader(), new Class<?>[]{Player.class}, handler);
    }

    @Test
    @DisplayName("no permission falls back to the configured limit")
    void fallsBackToConfig() {
        assertEquals(5, PermissionLimit.resolve(playerWith(), PREFIX, 5));
    }

    @Test
    @DisplayName("a permission replaces the configured limit")
    void permissionReplacesConfig() {
        assertEquals(20, PermissionLimit.resolve(playerWith(PREFIX + ".20"), PREFIX, 5));
    }

    @Test
    @DisplayName("a smaller permission restricts below the configured limit")
    void permissionCanRestrict() {
        assertEquals(2, PermissionLimit.resolve(playerWith(PREFIX + ".2"), PREFIX, 5));
    }

    @Test
    @DisplayName("several permissions take the largest")
    void largestPermissionWins() {
        assertEquals(30, PermissionLimit.resolve(
                playerWith(PREFIX + ".3", PREFIX + ".30", PREFIX + ".12"), PREFIX, 5));
    }

    @Test
    @DisplayName("zero means none, and is not mistaken for absent")
    void zeroIsARealAnswer() {
        assertEquals(0, PermissionLimit.resolve(playerWith(PREFIX + ".0"), PREFIX, 5));
        assertFalse(PermissionLimit.allows(0, 0));
    }

    @Test
    @DisplayName("unlimited beats every number, whatever order they arrive in")
    void unlimitedWins() {
        assertEquals(PermissionLimit.UNLIMITED,
                PermissionLimit.resolve(playerWith(PREFIX + ".unlimited", PREFIX + ".99"), PREFIX, 5));
        assertEquals(PermissionLimit.UNLIMITED,
                PermissionLimit.resolve(playerWith(PREFIX + ".99", PREFIX + ".unlimited"), PREFIX, 5));
        assertTrue(PermissionLimit.allows(PermissionLimit.UNLIMITED, 10_000));
    }

    @Test
    @DisplayName("a denied node is not an allowance")
    void deniedNodesAreIgnored() {
        assertEquals(5, PermissionLimit.resolve(playerWith(false, PREFIX + ".50"), PREFIX, 5));
    }

    @Test
    @DisplayName("nodes that are not numbers are somebody else's")
    void nonNumericNodesAreIgnored() {
        assertEquals(5, PermissionLimit.resolve(
                playerWith(PREFIX + ".bypass", PREFIX + ".-1", PREFIX + ".2x", PREFIX + "."),
                PREFIX, 5));
    }

    @Test
    @DisplayName("a neighbouring prefix is not this one")
    void otherPrefixesAreIgnored() {
        assertEquals(5, PermissionLimit.resolve(
                playerWith("izomap.max_cameras_by_player.40", "izomap.admin"), PREFIX, 5));
    }

    @Test
    @DisplayName("allows counts what is already used")
    void allowsCountsUsage() {
        assertTrue(PermissionLimit.allows(5, 4));
        assertFalse(PermissionLimit.allows(5, 5));
        assertFalse(PermissionLimit.allows(5, 6));
    }
}
