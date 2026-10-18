# Biome tints

In game, grass, leaves and water change color with the biome: a swamp is a murky green, a desert's grass yellowish, a warm ocean turquoise. Izomap reads those colors from the server when it starts, so every vanilla biome and every datapack biome is right with nothing to configure. `biome-tints.yml` is only for the ones you want different.

A vanilla map does not tint at all; this is a deliberate departure from it. `photo.biome-tint.enabled` turns it off and `photo.biome-tint.strength` softens it.

### Overrides

<pre data-title="biome-tints.yml">overrides:
  swamp:
    water: "#4F6B50"
  desert:
    grass: "#D6C24A"
    foliage: "#C9B93A"
  "mypack:volcano":
    water: "#7A2E14"</pre>

A biome is named as the game names it: `swamp` (the `minecraft:` namespace is implied) or `mypack:volcano`. Each of `grass`, `foliage` and `water` is a hex color; a channel you leave out keeps the server's.

### How the tint is applied

The biome color gives a block its **hue**; the block keeps its own **brightness**:

<pre>result = biome color × brightness(block's map color) / brightness(plains color)</pre>

Plains is the reference, so a plains photo looks as it always did and every other biome shifts from it. Two consequences worth knowing:

- Darkening a color here darkens the block, since brightness is relative to plains.
- Blocks stay apart: a tuft of grass is still darker than a grass block in every biome, because what is tinted is each block's own brightness.

### A biome with no color

A biome the plugin has no color for is drawn as plains, never left untinted. That covers a datapack biome loaded after startup (`/izocam reload` reads it) and a chunk that could not be copied. If you want a custom biome to have its own color without waiting for a reload, write it here.

### When it switches itself off

The colors that are not written in a biome's own data (most grass and foliage) come from a texture only the client has, so the plugin carries its own copy of that color map. If the server's internals ever differ from what the plugin expects, the tint turns itself off and the log says why: photos are then taken untinted rather than wrong.
