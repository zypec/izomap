# Block colors

Izomap does not guess colors. Every block is drawn with Minecraft's own map color, the one a vanilla map gives it, so stairs, slabs, new blocks and datapack blocks are all right with nothing to configure. `block-colors.yml` is only for the blocks you want different.

### How a block gets its color

1. **The map color** the server reports for the block, in its current state: wheat is green while it grows and yellow when ripe.
2. **A correction** where vanilla's color looks nothing like the block (`settings.correct-vanilla-colors`).
3. **Your override** from `block-colors.yml`, which beats both.

Then the face the ray entered brightens or darkens it (top 255, sides 220 and 180, bottom 135), as height does on a vanilla map.

Blocks without a map color, such as glass and torches, are invisible: the ray goes straight through them.

### What vanilla gets wrong

A map color is assigned by hand, not measured from the texture, and a few are far off. With the correction on (the default):

- **Tuff** reports a near-black brown on all fourteen of its blocks, although its texture averages a light gray-green. It is drawn as deepslate, which it is rarely next to, rather than stone, which it nearly always is.
- **Flowers** are all plain green in vanilla, which is right on a map where a meadow is one pixel per column, and wrong in a photo. Each flower takes the palette color closest to its *petals* (the stem and leaves are left out of the measurement): poppies and roses red, dandelions and sunflowers yellow, cornflowers blue, alliums and lilacs magenta, pink tulips and peonies pink.

Want vanilla's green back for one flower? `POPPY: PLANT` under `overrides`.

### Overrides

<pre data-title="block-colors.yml">overrides:
  GLASS: QUARTZ          # make glass visible
  WATER: COLOR_BLUE      # a deeper blue
  SHORT_GRASS: NONE      # remove grass tufts from photos entirely</pre>

A value is a map base color name (`GRASS`, `SAND`, `STONE`, `WOOL`, `WATER`, `QUARTZ`, `COLOR_ORANGE`, `TERRACOTTA_RED`, `DEEPSLATE`, the full list is the [base color table](https://minecraft.wiki/w/Map_item_format#Base_colors)), a hex color (matched to the nearest base color) or `NONE`, which makes the block invisible.

An override binds the whole material, every growth stage included.

### Thin blocks: coverage

A ray used to treat every block as a full cube, so one tuft of grass painted its whole cell bright green and a meadow looked like noise. Now thin blocks do not stop the ray: their color is blended with the block behind them by how much of the cell they fill.

| Kind | Blocks | Coverage |
|---|---|---|
| Thin plants | grass, ferns, flowers, saplings, sugar cane, mushrooms, cobwebs | 0.30 |
| Flat or skeletal | vines, glow lichen, sculk veins, ladders, chains | 0.15 |
| Lying on the ground | carpets, snow layers, lily pads, redstone dust, pink petals, wildflowers, leaf litter, rails | one value seen from above, a thin one from the side |
| Half blocks | slabs, stairs, walls, fences | not listed, they are solid |

Blocks lying on the ground cover the face they lie on: a carpet seen from above is all carpet, seen from the side it is a sliver. They get two values, `top` and `side`.

<pre data-title="block-colors.yml">coverage:
  SHORT_GRASS: 0.2     # fainter grass
  WHEAT: 0.4           # fields in tufts rather than a solid yellow
  VINE: 1.0            # vines fill their cell again
  MOSS_CARPET: {top: 1.0, side: 0.1}</pre>

`0.0` is the same as `NONE`, `1.0` a full block. A line here replaces the built-in value. `photo.coverage.enabled: false` turns the blending off.

The list is kept by hand because it cannot be read from the server: a block's collision box is not its visual shape, and for exactly these blocks (grass, flowers, vines) it is empty.

Carpets, snow layers, lily pads and redstone dust are not on the list: they cover the face they lie on completely and are thin only from the side, and one number cannot say both.

### Biome tint channels

Which blocks take their biome's color, and which of the three:

| Channel | Blocks |
|---|---|
| `GRASS` | grass block, short and tall grass, ferns, sugar cane |
| `FOLIAGE` | oak, jungle, acacia and dark oak leaves, vines |
| `WATER` | water, bubble columns |

Spruce, birch, cherry and azalea leaves are left out on purpose: the game draws them in fixed colors of their own.

<pre data-title="block-colors.yml">tint:
  MANGROVE_LEAVES: FOLIAGE
  WATER: NONE              # the same water in every biome</pre>

The colors themselves are on [Biome tints](#biome-tints).

### After editing

Run `/izocam reload`. The live preview shows the new colors on the next click. Photos already taken keep theirs until they are retaken.
