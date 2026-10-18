# Permissions

Permissions decide **how expensive** a photo may get, not whether one can be taken. A player with nothing but `izomap.camera` can still take photos: small ones, in the fast style, with the free option of every setting.

### Every node

<!--permissions-->

### A whole area, or one option

Settings with a list of options are asked twice: `izomap.filter` grants every filter, `izomap.filter.WARM` only that one, and either is enough. The same holds for `izomap.sky.<NAME>` (`NONE`, `WORLD`, `DAWN`, `DAY`, `DUSK`, `NIGHT`), `izomap.ratio.<NAME>` (`RATIO_1_1`, `RATIO_16_9`, `RATIO_4_3`) and `izomap.frame.<ID>`. Give your staff the whole area and everybody else one option, with no second system to learn.

The cheapest value of each setting is free: the **Original** filter, the **Transparent** sky and the **Fast** style need no node. A new camera placed by a player without `izomap.style.sharp` starts in fast style, so nobody is handed a camera they cannot shoot with.

### Number limits

Three limits from `config.yml` can be set per player with a number at the end of a node:

| Node | Replaces |
|---|---|
| `izomap.max_cameras_by_player.<n>` | `settings.max-cameras-per-player` |
| `izomap.max_photos_by_camera.<n>` | `settings.max-photos-per-camera` |
| `izomap.max_map_tiles.<n>` | `settings.max-map-tiles` |

- A node **replaces** the config value, even when its number is smaller, so a group can be held below the default.
- With several, the **largest** wins: permissions add up from groups, and taking the smallest would punish joining a generous one.
- `.0` allows none. `.unlimited` removes the limit.

These nodes are deliberately not declared by the plugin, so a wildcard such as `izomap.*` does not hand anybody a number. Only nodes you actually assign count.

`max_map_tiles` is the one that matters most. A 1x1 photo is one tile and a 16x9 one is 144, and the time a photo takes grows with the tile count. The smallest grid of each ratio stays available above the limit.

### A starting point

With LuckPerms, for a survival server:

<pre class="console" data-title="Console">lp group default permission set izomap.max_map_tiles.8
lp group default permission set izomap.filter.WARM
lp group default permission set izomap.sky.DUSK
lp group vip permission set izomap.max_map_tiles.32
lp group vip permission set izomap.style.sharp
lp group vip permission set izomap.filter
lp group vip permission set izomap.sky
lp group vip permission set izomap.sky.bodies
lp group vip permission set izomap.focus</pre>

### Checked twice

The capture screen only shows what a player may use, and the server checks again when the photo is taken, since a permission can be lost while the screen is open and `/izocam maps` never shows the screen. A refused photo names the setting that blocked it.

Retaking a photo asks for no permission: the photo already exists, and losing a node later should not break a picture already on the wall.
