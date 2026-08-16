package dev.zypec.izomap.config;

import org.bukkit.entity.Player;

/**
 * Reads a numeric allowance off a player's permissions, e.g.
 * {@code izomap.max_photos_by_camera.10}.
 *
 * <p>A permission, when present, <b>replaces</b> the configured value rather than
 * raising it. A smaller number therefore restricts, which is the point: a server that
 * wants one group held below the general limit has no other way to say so.</p>
 *
 * <p>Several of them add up to the <b>largest</b>. Permissions are expected to
 * accumulate — a player inherits from every group they are in — so taking the smallest
 * would mean joining a generous group could quietly cost someone their allowance.</p>
 *
 * <p>The nodes are read from {@link Player#getEffectivePermissions()} by prefix, and
 * are deliberately <b>not</b> declared in {@code paper-plugin.yml}. A wildcard like
 * {@code izomap.*} expands over declared nodes, so declaring them would hand everyone
 * holding the wildcard whichever number happened to be written down.</p>
 */
public final class PermissionLimit {

    /**
     * No ceiling at all; {@code izomap.<prefix>.unlimited}.
     */
    public static final int UNLIMITED = -1;

    private static final String UNLIMITED_SUFFIX = "unlimited";

    private PermissionLimit() {
    }

    /**
     * The player's allowance under {@code prefix}, or {@code fallback} when they hold
     * no such permission.
     *
     * @param prefix   node prefix without the trailing dot, e.g.
     *                 {@code izomap.max_photos_by_camera}
     * @param fallback the configured limit
     * @return the allowance, or {@link #UNLIMITED}
     */
    public static int resolve(Player player, String prefix, int fallback) {
        var dotted = prefix + ".";
        var best = Integer.MIN_VALUE;

        for (var info : player.getEffectivePermissions()) {
            if (!info.getValue()) continue;

            var name = info.getPermission();
            if (!name.startsWith(dotted)) continue;

            var suffix = name.substring(dotted.length());
            if (UNLIMITED_SUFFIX.equals(suffix))
                return UNLIMITED; // nothing else can beat it

            var value = parseCount(suffix);
            if (value >= 0 && value > best) {
                best = value;
            }
        }
        return best == Integer.MIN_VALUE ? fallback : best;
    }

    /**
     * Whether an allowance still has room for one more.
     */
    public static boolean allows(int limit, int used) {
        return limit == UNLIMITED || used < limit;
    }

    /**
     * A whole non-negative count, or {@code -1} for anything else. A node that is not a
     * number is somebody else's permission that happens to sit under the same prefix,
     * not a mistake worth reporting.
     */
    private static int parseCount(String suffix) {
        if (suffix.isEmpty() || suffix.length() > 9) return -1;

        var value = 0;
        for (var i = 0; i < suffix.length(); i++) {
            var digit = suffix.charAt(i) - '0';
            if (digit < 0 || digit > 9) return -1;

            value = value * 10 + digit;
        }
        return value;
    }
}
