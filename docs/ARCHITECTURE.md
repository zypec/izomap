# Izomap architecture

How the plugin is put together and why. This is the contributor's reference; what a server owner
needs is on the wiki (`wiki/`, published at <https://zypec.github.io/izomap/wiki/>). Keep both in
step with the code: a change to behavior, a config key, a command or the architecture updates this
file in the same commit.

## 1. Overview

Izomap is an isometric "photo" plugin for Paper. A player places a camera in the world and adjusts
its angle and zoom by clicking it; the plugin renders what the camera sees with asynchronous ray
casting and cuts the result into 128x128 Minecraft maps that hang as a grid of item frames.

- **Root package:** `dev.zypec.izomap` · **Command:** `/izocam` (alias `/izocamera`)
- **Platform:** Paper 26.3 (`api-version` comes from the build's `minecraftVersion`)
- **Toolchain:** Java 25. Paper 26.1+ refuses older JVMs and its API classes are Java 25 class
  files, so the toolchain and `options.release` are both 25. Do not lower them.
- **Build:** Gradle Kotlin DSL with `paperweight-userdev`. Since 26.1 the server jar is not
  obfuscated, so there is no reobf step; the Mojang-mapped jar is the output.

## 2. Layout

Packaged by feature. The main class only wires the subsystems together.

```
dev.zypec.izomap
├── Izomap.java     onEnable/onDisable, wiring
├── config/         ConfigManager (typed config access), Messages (MiniMessage), Permissions, PermissionLimit
├── storage/        YamlStorage, the async YAML read/write base
├── camera/         camera model, entity lifecycle, clicks, command, persistence, status line
├── render/         geometry, chunk snapshots, voxel walk, color pipeline, sky, focus, fog, preview
├── map/            photo model, grids, slicing, MapView creation, hanging, frames, cache, export
├── place/          PlacementManager (the ghost shown before hanging), Protection (asking region plugins)
├── ui/             CameraDialogs, PhotoDialogs (Paper Dialog API)
├── dev/            DevShots, registered only with -Dizomap.dev=true
└── util/           small helpers that cross packages (Ids, Failures, Format)
```

### Shared helpers

| Helper | Job |
|---|---|
| `util.Ids#parse` | `String` to `UUID`, `null` when malformed. YAML records, PDC tags and `.izm` file names all go through it |
| `util.Failures#unwrap` | Unwraps the `CompletionException` a future chain adds; both `instanceof` checks and log text need the real cause |
| `util.Format` | Angle, zoom, block and coordinate formats, always `Locale.ROOT`. Hologram, status line and dialog describe the same camera and must agree |
| `Izomap#runOnMain` | Back to the main thread (`getGlobalRegionScheduler`) |
| `Izomap#asyncExecutor` | The executor `CompletableFuture` chains use |

Ids always reach us as text, so a malformed one is an expected outcome, not an error: the caller
skips that record and keeps loading.

### Startup order (`Izomap#onEnable`)

```
Messages → ConfigManager
      ↓
CameraKeys → CameraManager ──┐
BlockColorTable → RenderService ──┐
MapService ──────────────────────┼→ PreviewManager
PhotoKeys ───────────────────────┴→ PhotoManager → MapPlacer, PhotoStorage, PhotoCache
                                          ↓
                                   PlacementManager
                                          ↓
                                    CameraDialogs
                                          ↓
  CameraListener, PhotoFrameListener, PreviewManager, PlacementManager (listeners), CameraCommand
```

- **`Messages` first.** Every console line goes through it, `ConfigManager` included: it warns
  about risky values from its own constructor.
- **`cameraManager.load()` before `photoManager.load()`.** Photos load from their cache, but a lost
  cache falls back to the source camera.

## 3. The render engine

A capture has two stages, and the split is the plugin's most important design decision:

| Stage | Thread | Work |
|---|---|---|
| 1 | **Main (region)** | Camera geometry; the chunks needed are copied as `ChunkSnapshot`s |
| 2 | **Async pool** | Voxel walk, shading, filter, palette snap |

Block access in Paper is only safe on the main thread, and the heavy part must not block it.
`WorldSnapshot` is the copy that reconciles the two.

### Projection

Orthographic. Every ray leaves the camera's image plane in parallel (`RenderGeometry`). As a result:

- Only the frame height sets object size; the camera's distance to the target does not.
- The plane sits at the camera, so terrain behind the camera cannot enter the photo.
- **Moving rays along the view direction does not change the image**, only where they start.
  Backoff (below) relies on this.

| Setting | Meaning |
|---|---|
| `photo.frame-height` | World-space height of the frame in blocks. Covered area = `frame-height / zoom` |
| `photo.frame-shift` | Vertical offset of the frame relative to the camera, as a fraction of its height. `0.0` (default) centers the frame on what the camera looks at |

#### Why ray distance is not a setting

The distance a ray needs follows from the frame and the tilt:

```
distance = (vertical drop from the frame's top edge to the target floor) / sin(pitch)
target floor = min(camera, sea level, ground under the camera) - settings.render-depth
```

When it was a setting it had to change with every zoom; forgetting left the top of the frame
silently empty, and fixing that meant raising the chunk budget too. The only cost value is
`settings.max-capture-area`, which caps both this distance and the number of chunks copied.
`settings.render-depth` only says how far below the reference ground the floor sits (valleys,
cliffs).

The reference ground is **the lower of sea level and the ground in the camera's column**; below
both (a cave, a valley) the camera's own height is used. Measuring only the camera's column
(`getHighestBlockYAt`) broke on a camera standing on a tower or a tall tree: the floor moved up
hundreds of blocks, the distant sea fell out of range, and the photo got a perfectly straight empty
band along its top (the top rows need the longest rays). A missing chunk would leave a jagged,
chunk-shaped hole instead; that is how to tell them apart.

#### Backoff

The part of the frame that falls **below** the camera starts inside the ground when the camera is
low, and would print a flat cross-section of dirt along the bottom of the photo. Those rays, and
only those, are pulled back along the view direction until they start at the camera's horizontal
plane. With an orthographic projection this changes neither the frame nor the zoom.

- No ray starts below the camera's height.
- Rays at or above the camera are not pulled back (no view from behind).
- Backoff is capped by the computed ray distance, and the pulled-back length is added to the walk
  so every ray sees the same distance forward from the camera plane.
- The chunk capture prism also starts behind the plane; otherwise pulled-back rays would cross an
  uncopied region read as air.

> **Tilt warning.** Rays are parallel, so a level camera cannot see the ground's surface: the top
> of the frame becomes sky and the bottom a cross-section. `camera.default-pitch` is therefore 30,
> and players get a warning below 10 degrees.

### The ray walk (`IsometricRenderer`)

**Amanatides-Woo DDA** instead of fixed steps: every step jumps exactly to the next block boundary.
No thin block is skipped, nothing is oversampled, and the face a ray enters is known for free
(shading uses it).

The image is cut into horizontal bands spread over `settings.render-threads` threads; the last band
runs on the calling thread.

**Chunk lookup is a flat array.** `WorldSnapshot` lays the copies on the grid of chunk columns they
span; a block step costs a subtraction, a bounds check and an array read instead of boxing a `long`
key and hashing it into a `HashMap`. The box around a diagonal view holds empty slots, a reference
each. The gain was not measured.

The walk decides **what was found** and writes it to a `RayHit`: hit or not, material, base color,
entry face. Turning that into a color is `ColorPipeline`'s job. The one color question the walk
answers itself is **transparency**, because a block with no map color is see-through and the ray
must go on; the base color is read for that check anyway, so it travels in the `RayHit`.

A block is one of **three** kinds. One with no color (glass, torches) does not stop the ray; a full
cube does; in between, blocks that fill part of their cell (grass tufts, vines, and water by a
measured amount) do not stop it either: their color is written to the `RayHit` as a **layer** with
its share, and the walk continues behind it. At most `RayHit.MAX_LAYERS` layers are kept, after
which the next block counts as solid; otherwise a curtain of vines would walk a ray to the far side
of the map. See "Partial coverage" and "Water".

`RayHit` is **mutable and reused** on purpose: one per band, filled for every sample, never leaving
its thread. A 2048x1152 capture at 2x supersampling is 9.4 million samples; allocating per sample
would tie that path to the garbage collector.

### Color

Colors are not guessed; they are **the vanilla map palette itself**. `ColorPipeline` runs:

| # | Stage | From |
|---|---|---|
| 1 | Base color | `BlockColorTable`, the server's `BlockData#getMapColor()`. Stairs, slabs, walls and new blocks are right automatically |
| 2 | Face shade | Entry face to brightness: top `HIGH` (255), sides `NORMAL`/`LOW` (220/180), bottom `LOWEST` (135). On a vanilla map this comes from height; isometrically its equivalent is face orientation |
| 3 | Filter | `ColorFilter`, the operation chain from `filters.yml` |
| 4 | Palette snap | `MapColorConverter#snap`, with the same weighted (redmean) distance as Bukkit's `MapPalette` (61 base colors x 4 shades = 244) |

Colorless blocks (`MapBaseColor.NONE`) are skipped in the walk, before stage 1, and the ray sees
behind them as in vanilla. Partially covering blocks produce stage 1 **several times**: the walk
collects layers, `#compositeRgb` stacks them by share and the result goes through stages 3 and 4
via `#blend`.

So **every output pixel is a real map color**. The cache relies on that: `MapColorConverter#packedId`
maps a pixel to its map byte by exact match, `#argbOf` goes back through a 256-entry table.

#### Blocks whose color depends on their state

Wheat is `PLANT` while growing and `COLOR_YELLOW` when ripe. Which blocks behave like that is **not
listed by hand** (such a list rots every version): at load, every `Ageable` block is asked at every
age and an age table is kept only for those that really answer differently. The hot path pays only
where needed: `variesByState` is a lookup, and the full block state is read (`WorldSnapshot#blockDataAt`)
only when a ray hits such a block. An override in `block-colors.yml` binds the whole material and
drops its age table.

#### Vanilla colors that look nothing like the block

A base color is assigned by hand, not averaged from the texture. `BlockColorTable.CORRECTIONS`
replaces the worst, and each choice was **measured** against the client jar's textures:

| Block | Texture average | Vanilla | Nearest | Chosen |
|---|---|---|---|---|
| `tuff` | #6C6D66 | TERRACOTTA_GRAY | STONE (11) · DEEPSLATE (12) | `DEEPSLATE` |
| `tuff_bricks` | #62665F | TERRACOTTA_GRAY | DEEPSLATE (6) · TERRACOTTA_CYAN (15) | `DEEPSLATE` |

**Colorless in vanilla, drawn here** (`BlockColorTable.DRAWN`): the nether portal has no map color, so a
portal room printed the wall behind the portal through it. It is drawn `COLOR_PURPLE`, the entry
nearest its texture, with the corrections.

Plain tuff is nearly a tie, broken in favor of the photo: almost every tuff build also uses stone,
cobblestone and andesite, and `STONE` would make tuff indistinguishable from them. Cherry log was
checked too and vanilla's choice is right.

**Flowers.** Vanilla gives every flower `PLANT` (#007C00), right for a map and wrong for a photo.
The measurement is of the **petals**: green-dominant pixels (g above both r and b by 12) are dropped
and the rest averaged, ranked by redmean. Where the nearest entry would be gray (pale petals snap
to `WOOL`), a chromatic entry wins: a gray flower has lost its reason to be in the frame.

| Petals (measured) | Chosen |
|---|---|
| dandelion #F5CE40, sunflower #F6C536 | `COLOR_YELLOW` |
| wildflowers #EDD675 | `GOLD` (the yellow half of a tie with `SAND`) |
| poppy #C92925, red tulip #D32D2A, rose #C12A24 | `CRIMSON_NYLIUM` |
| orange tulip #D98527 | `COLOR_ORANGE` |
| torchflower #A06956 | `TERRACOTTA_ORANGE` (the average lands on `DIRT`; the warm entry keeps the bud) |
| cornflower #546EDF | `LAPIS` |
| blue orchid #25AAED, pitcher plant #797EBA | `COLOR_LIGHT_BLUE` |
| allium #BA85E5, lilac #BE75C0 | `COLOR_MAGENTA` |
| pink tulip, peony, pink petals, spore blossom, cactus flower | `COLOR_PINK` |
| white tulip, open eyeblossom | `WOOL` |
| lily of the valley #EDEDED | `QUARTZ` |
| azure bluet, oxeye daisy | `SAND` |
| closed eyeblossom | `DEEPSLATE` |
| wither rose | `TERRACOTTA_GRAY` |

Partial coverage keeps this in proportion: a flower holds about 30% of its cell, so the result is
a tone of the right hue, not a block of color. `settings.correct-vanilla-colors: false` restores
vanilla exactly. The order is vanilla, correction, `block-colors.yml`.

**`/izocam reload` rebuilds the table** and swaps the renderer whole. A render in progress keeps the
table it started with (read into a local before the walk). Existing photos are not affected: they
come from the cache. A retake shows new colors; the preview refreshes on the next click.

### Partial coverage (`BlockColorTable.coverage`, `photo.coverage`)

The DDA counted every voxel as a full cube, so a grass tuft painted its whole cell saturated green.
The table has a second column: **coverage**, how much of its cell a block really fills. `NONE` is
0.0, the old behavior 1.0.

**Coverage is spent as alpha.** The ray does not stop; the color is written with its share, the ray
finds what is behind, and `ColorPipeline#compositeRgb` stacks the layers (`c x plant + (1-c) x
ground`) before snapping. Sub-cell geometry (hitting vanilla's crossed planes exactly) would be more
correct at high zoom but turns into binary noise at preview resolution; alpha works at every size.

**It is cheap:** the ray used to stop at the grass and now reaches the ground, which is what it would
do if the block were `NONE`, plus one blend per pixel. So it is on by default; it corrects the render
rather than adding an effect.

**The list is kept by hand** because it cannot be derived: `BlockData#getCollisionShape` is the
collision box, not the visual shape, and for exactly these blocks (grass, flowers, glow lichen,
vines) it is empty.

| Class | Examples | Treatment |
|---|---|---|
| Thin or scattered plants | grass, ferns, flowers, saplings, sugar cane, mushrooms, cobwebs | `0.30` |
| Flat or skeletal | vines, glow lichen, sculk veins, ladders, chains, rails | `0.15` |
| Solid half blocks | slabs, stairs, walls, fences | untouched, `1.0` |

Half blocks are solid where they are, and blending them with the ground only fades them; fixing
them needs a sub-voxel walk, not coverage. The default list is `BlockColorTable.DEFAULT_COVERAGE`;
`block-colors.yml` → `coverage:` overrides it (0.0 to 1.0, out-of-range values are logged and
skipped). It reloads with colors and is **not** frozen into photos.

- **Thin blocks cast no shadow.** `BlockColorTable#occludes` counts blocks below 0.5 coverage as
  see-through for the shadow ray and ambient occlusion.
- **A layer has no shading of its own:** it takes the `darken` of the surface behind it.
- **Nothing behind** (a tuft against the sky): the share goes into the same majority rule the
  supersampler uses: below 50% total coverage the pixel stays sky.
- **Face-aware coverage for things lying flat.** A carpet or a snow layer covers the face it lies
  on and is thin only from the side, so one number was wrong whichever way it leaned (thin turned a
  snowfield green, full drew a carpet as a cube). The table has a second column, coverage seen
  through `TOP` or `BOTTOM` (`BlockColorTable#coverageOf(material, face)`), read with the entry
  face the walk already knows: one array read. `DEFAULT_LYING` lists carpets (1.0 from above, 0.1
  from the side), snow layers (1.0/0.15), lily pads (0.85/0.05), redstone dust (0.3/0.05), pink
  petals, wildflowers and leaf litter (0.6/0.1) and rails (0.5/0.1). That they lie flat is assumed
  from the material, not read from the block state (a state read per hit); glow lichen and sculk
  veins cling to any face and keep one number. `block-colors.yml` takes `{top, side}` for an
  entry. Shadows and occlusion use the side value, so a carpet casts none. A full eight-layer snow
  block still reads as thin from the side.

### Biome tint (`BiomeTints`, `ServerBiomeColors`, `biome-tints.yml`)

Grass, leaves and water change with the biome in game; a vanilla map does not. This is a
deliberate **departure** from vanilla, on by default (`photo.biome-tint.enabled`, `strength`).

| What | From | Why |
|---|---|---|
| Water | Server (`waterColor`) | Every biome writes it explicitly |
| Grass/foliage where the biome overrides it | Server (`grassColorOverride`, `foliageColorOverride`) | Swamp, badlands, cherry grove |
| Grass/foliage otherwise | **`Colormaps`**, our own grid | Sampled from `grass.png`/`foliage.png` by temperature and downfall, and **that texture only exists on the client** |
| Position-dependent modifiers | Server (`grassColorModifier`) | The server's own code on top of ours |

**The bug that made this table:** the first version asked `Biome#getGrassColor` for grass too. With
no override it calls `GrassColor.get(...)`, whose pixel array is filled by the client and is **always
zero on a dedicated server**, so it returned 0 (black) and grass came out black. Two defenses
followed: black no longer counts as a tint (that channel stays untinted), and `ColormapsTest` sweeps
every climate to make sure none returns black.

`Colormaps` carries a **9x9 grid** taken from the texture with bilinear interpolation: at most 3 to
4 units off per channel against the real colormap (below 1 on average), invisible after the palette
snap. The unreachable upper half of the square (downfall is multiplied by temperature) is stored as
the diagonal's color rather than white, which otherwise bled in near the diagonal by up to 48 units.

`ServerBiomeColors` is the plugin's only NMS touch and follows the rules in §9: one class, called
**once at startup** (never on the hot path), the caller catches everything including `LinkageError`
and switches the tint off. Modifiers need coordinates; the table samples them at (0,0) so the hot
loop never enters the server: a swamp comes out in one of its two greens.

**The tint gives hue, the block keeps its brightness:**

```
result = biome color × brightness(block base color) / brightness(reference biome color)
```

Using the biome color raw ignores that the client multiplies it onto a gray texture; multiplying it
with vanilla's stylized `WATER` (#4040FF) turned swamp water purple. The reference is **plains**,
so plains photos are unchanged and blocks stay apart (a tuft is darker than a grass block in every
biome). Face shading applies after the tint.

**Cost:** a tinted color is off-palette by definition, so each would need a 244-entry search. One
block type and one biome always give the same color, so `ColorPipeline` keeps a 256-entry row per
tint; rows are published atomically and two threads filling the same row compute the same numbers.
The chunk copy carries the biome array only while tint is on.

| Channel | Blocks (`BlockColorTable.DEFAULT_TINTS`) |
|---|---|
| `GRASS` | grass block, short and tall grass, ferns, sugar cane |
| `FOLIAGE` | oak, jungle, acacia and dark oak leaves, vines |
| `WATER` | water, bubble column |

Spruce, birch, cherry and azalea leaves are drawn in fixed colors by the client and stay out.
Whether a block uses a colormap lives in the client's render code, so this list is kept by hand too.
`short_grass.png` and `fern.png` are gray (#929192, #7C7D7C): texture averaging is useless for tinted
blocks.

**A biome with no color falls back to plains**, never untinted: a datapack biome loaded after the
table was read, a chunk that could not be copied (`biomeAt` returns `null`), a channel that read as
black. The rule: a color that cannot be computed never reaches the image.

**Open:** position-dependent `grass_color_modifier` (swamp noise, dark forest darkening) flattens to
one color; dry foliage color is unused.

### Water (`Water`, `WaterSpec`)

| Mode | Does | Cost |
|---|---|---|
| `FLAT` | One tone; the column is never measured | none |
| `DEPTH` (default) | The surface loses one (`dim-deeper-than`) or two (`dark-deeper-than`) shades with the cells below it | The cells the ray walks to the bottom |
| `TRANSLUCENT` | Water blends with the bottom by depth (`surface-min` to `opaque-depth`) | Also finding and coloring the bottom |

The mechanism is partial coverage with a measured share: the ray follows the column and counts
cells. `DEPTH` writes the surface hit with the count added to `darken`; `TRANSLUCENT` leaves the
column as a layer and continues. `TRANSLUCENT` works with coverage off, since blending is the walk's
own ability. Depth is counted in **cells**, so an oblique ray reads a shallow lake a little deeper;
vanilla makes the same trade. Ice, packed ice and `WATER_CAULDRON` are surfaces; bubble columns read
with the water around them; underwater plants stay off the coverage list because a thin cell in the
middle would split the measurement. `WaterSpec` is frozen into `CaptureSpec`.

**Glint** (`photo.water.glint`, off by default, frozen into `WaterSpec`, works in every mode). An
orthographic camera's rays are parallel, so a still surface would mirror the sun into every pixel
or none. Waves break it up: the surface is cut into half-block patches, each tilted by a
deterministic hash of its world position up to `spread` degrees, and only the patches tilted just so
reflect the sun to the camera. The view decides how many can: from the angle between the mirrored
view and the sun, `reach = 1 - angle / (2 spread)`, and the share of glittering patches is
`0.3 reach³`. A glittering patch moves `strength` towards the sun's color, scaled by the sky's
light (no glitter at night), and leaves the palette, so the pixel is blended and snapped like a
composite. Applied in `renderBand` to opaque `TOP` water hits; translucent layers do not glitter.
The first tuning (quarter-block patches, `reach²`) covered whole lakes in white noise on the
development server; a third of the density at twice the patch size reads as light on water.

### Sky (`SkyOption`, `Sky`, `SkyBodies`)

Rays that hit nothing leave transparent pixels; with a sky, those pixels are painted. The question
splits: the **player** picks which sky (`SkyOption`: transparent, game time, dawn, day, dusk, night),
the **server** decides how it is drawn (`photo.sky`).

**The sky freezes at capture.** `SkyOption.WORLD` reads a moving clock; `RenderService#specFor`
resolves it into a `SkySpec` (color, tick, moon phase, bodies) on the spot, so a re-render neither
drifts with the clock nor with a retuned palette. The color is interpolated around the clock between
four keys (dawn 23000, day 6000, dusk 12500, night 18000).

**A lookup table**, because the palette has a handful of blues: a vertical gradient snapped row by
row becomes a few wide bands. `photo.sky.dither` mixes the two palette entries around the true color
in a 4x4 ordered pattern. Both dithering and snapping are too expensive per pixel, so each row is
resolved into a 16-entry cell beforehand: painting a sky pixel is one array read.

**Bodies** (`SkyBodies`) are asked before the table, since they depend on both pixel coordinates
and the camera's axes. Each is placed by projecting a world direction onto the camera basis
(`u = dot(dir, right)`, `v = dot(dir, up)`, visible when `dot(dir, direction) > 0`):

- the **sun** is drawn in the shading sun's direction (the camera's own, §3 Advanced shading), so
  shadows never fall towards it;
- the **moon** opposite it, in the phase of the world's day;
- **stars** are a deterministic hash over direction space: the same camera sees the same stars and
  a panorama does not slide them. They fade in only as the sky darkens.

The player switch is `camera.skyBodies` behind `izomap.sky.bodies`; the sky has its own dialog
screen (six skies listed, plus the switch). The sky is painted during the render, before the style
passes, so `FAST`'s upscale blends terrain into the sky.

**A night sky darkens the ground** (`ShadingSpec#skyLight`). The sky's frozen hour sets a sky light
level, the way the game's sunlight follows the clock: `cos` of the hour from noon, `× 1.5 + 0.75`,
clamped, so full light holds from mid-morning to mid-afternoon; at midnight it is
`photo.shading.night-light` (3), between them a ramp (dusk 10, dawn 7). The stored sky light loses
what the sky lacks (`WorldSnapshot#lightAt(x, y, z, skyLight)`), emitted light is kept, and the
result goes through the block-light thresholds. Below 15 the walk reads light for every hit even
with `block-light` off (`ShadingSpec#readsLight`), so the chunk copy carries light then too. Open
ground at night is two shades darker, a torch-lit square stays lit; on the development server's
decorated town the night shot came out only 13% darker on average, since lanterns along every path
light most of the ground. A transparent sky is daylight; old photos read `sky-light` 15.

### Styles: `SHARP`, `FAST` and `SHADER` (`PhotoStyle`, `StylePass`, `ShaderPass`)

`SHARP` casts a ray per pixel; `FAST` renders at `photo.style.fast-scale` and scales up, so rays drop
with the square (0.5 is a quarter). `StylePass.upscale` is bilinear and **snaps back to the
palette** (a map cannot store anything else, and the cache writes palette bytes). Transparency does
not average: a pixel is a color or a hole. The style lives on the camera and freezes into the spec,
so a retake costs the same.

**`SHADER`** is `SHARP` with a shader pack's look (`izomap.style.shader`). `RenderService#specFor`
turns the sun shadow and ambient occlusion on for it whatever the server's shading says, with at
least 48 blocks of shadow (`ShadingSpec#withShadows`), and freezes a `ShaderSpec` (grading, bloom,
haze, vignette, dither from `photo.style.shader`) into the spec. `ShaderPass` runs after fog and
before focus, in float RGB: aerial haze towards the sky's row color from 96 blocks to the capture
area (absolute distance, like a shader's fog: relative to the frame's own depth it washed out the
top half of every downward shot), grading (contrast and saturation up, split toning: dark tones to
blue, light to warm), bloom (pixels above 0.72 luma, blurred at a quarter size with two box passes
each way, added back) and a vignette, then a 4x4 ordered dither and the palette snap. Holes stay
holes. On the development server, warm, the cathedral horizon view at 8x5 walked in 10.2 s as
`SHADER` and 10.4 s as `SHARP` with the server already shading; the first, cold shot took 70 s,
like any first shot of that view.

> **The "oil paint" look of an early version was not a style.** Three styles were tried to bring it
> back and all pushed the wrong way. Side-by-side screenshots showed the difference was the
> **framing**: the old shots had 2 to 3 pixels per block, the new ones 8 to 10, and a few pixels per
> block turn neighboring block types into a mosaic that reads as texture. The second factor was
> sampling: the old version had one ray per pixel (pure palette colors), today's supersampling
> averages them. Its modern equivalent is a wide frame with `supersampling: 1`.

#### Color filters are user-defined (`filters.yml`)

A filter is an operation chain run in order: `brightness`, `contrast`, `saturation`,
`rgb-offset {r,g,b}`, `grayscale` (true or luma weights), `tint {color, strength}`, `invert`,
`posterize`. Only the **id** is saved (`cameras.yml` → `color-filter`, `photos.yml` →
`capture.color-filter`); display names live in the language file → `filter.<ID>`. `ORIGINAL` is a
**code constant**, not a file entry: the fallback for unknown ids, a new camera's value, the one
filter guaranteed not to touch the image; a file entry with that id is ignored. An unknown operation
cancels only itself and is logged. `/izocam reload` rereads filters.

#### The tail of the pipeline is a table

A ray always lands on a palette entry, and there are only 244. So everything after the base color is
precomputed for every entry when the pipeline is built: a `packedId → final ARGB` table carries the
filter and the snap. A pixel whose samples agree, nearly the whole image, is one array read, and a
filtered render runs exactly the same code as an unfiltered one. Only pixels whose samples differ
run the stages (`ColorPipeline#blend`), the few percent where anti-aliasing really acts.

> **Averages are taken over unfiltered colors**, with the filter applied to the average. Filtering
> per sample first would pin each sample to the palette early: 1.5 to 2.9% of edge pixels shifted
> and WARM/COOL channel differences reached 89 to 94. In this order the output is **bit-identical**
> to the renderer before the pipeline; the speedup is only removed repetition.

### Depth of field (`FocusPass`, `FocusSpec`)

The player picks a **focus distance** (blocks from the camera plane); that distance stays sharp and
everything else blurs. Off by default, behind `izomap.focus`, kept on the camera
(`focus-enabled`/`focus-distance`), frozen into the spec.

> **There is no lens.** Rays are parallel, so there is no real circle of confusion. This is a chosen
> look: tilt-shift on a tilted camera, subject separation on a level one.

**Depth is free:** the DDA already tracks `t`. `RayHit` carries it; for a pulled-back ray `t -
backoff` is written, so depth is measured from the camera plane. The depth array is allocated only
when the effect is on. With supersampling, a pixel's depth is the average of its hits; sky pixels get
`RenderResult.SKY_DEPTH`.

- **Gather with the rule of scatter.** Blur is really a scatter (each surface spreads a disk), which
  is unaffordable at these sizes, so the pass gathers but keeps scatter's rule: a neighbor **in
  front of** this pixel contributes only if its own disk reaches here. A sharp subject does not
  bleed into the blurry background behind it, while a blurry foreground spills over what it covers.
  Neighbors behind are always accepted; they are inside the center pixel's own disk.
- **The disk is sampled, not scanned:** a fixed number of points on a golden-angle spiral
  (`photo.focus.samples`), so the cost per pixel is constant and only that number moves it, never
  the slider. Samples are precomputed in sixteen rotations (one per position of a 4x4 tile).
- **Palette again:** 4x4 ordered dither before the snap (`photo.focus.dither`). A pixel whose
  radius is under a pixel is copied as is; a blur made of one color stays that color (dithering a
  flat wall would speckle it); untouched sky is left alone (re-snapping a dithered gradient brings
  the bands back).
- **Order: walk → focus → style upscale.** The pass runs while depth is still pixel-aligned; the
  radius is a fraction of the image height, so `FAST` blurs its small image with a small radius.

Measured offline (2048x1152, 24 samples, 4 threads, Apple Silicon, synthetic image): terrain-like
254 to 273 ms, worst case 411 ms; 8/48/96 samples 273/635/1086 ms; the 128x128 preview at 48
samples on one thread about 13 ms. The pass runs entirely in the async pool.

### Fog (`FogPass`, `FogSpec`)

A parallel projection has no perspective, so nothing tells near from far; fog gives that cue
back. **The camera's setting**, like focus (`Camera#fogEnabled`, `fogStart`, `fogEnd`, behind
`izomap.fog`); `photo.fog.enabled/start/end` are where a new camera starts, strength, color and
dither stay the server's. Frozen into the spec like shading and water.

**The range hint.** The sky screen carries the switch, two sliders (0 to the camera's ray distance)
and a line that reads the frame: every preview render records depth, `SceneDepth` sorts it into the
nearest (5th percentile), median and furthest (98th) ground and the sky's share, and the camera
keeps the last one (transient). Fog from the median to the furthest keeps the near half clear and
melts the far edge into the sky; a "Use" button sets exactly that. A frame whose ground spans less
than half its far depth (a camera looking down) is told fog would only veil it evenly. A
transparent sky with `color: sky` is told it needs a sky.

- **Same depth as focus.** The walk records depth when either pass draws; fog reads it in blocks
  from the camera plane, so `start`/`end` sit at the same place in the world at any zoom or grid.
  The fade is a smoothstep between them, times `strength`.
- **The sky is the fog color.** `color: sky` blends each row towards the sky's own undithered
  color at that row (`Sky#rgbAt`), gradient included, so at `strength: 1` the far land melts into
  the sky behind it and the horizon line disappears. Below 1 the far edge keeps a trace of itself
  and the horizon reads as a line, which looked worse in every test shot. A photo with no sky gets
  no fog unless a color is named.
- **Palette again:** ordered 4x4 dither before the snap, scaled by the fog amount so a lightly
  fogged surface snaps back to its own color. Sky pixels, holes and anything nearer than `start`
  are copied as they are.
- **Order: walk → fog → shader → focus → style upscale.** Before the blur, so a defocused far hill
  is soft and hazy at once.

The defaults (128 to 2048 blocks) are tuned on the gallery's level dusk shot, where the sea starts
about 1000 blocks out. A camera looking down sees a few hundred blocks at most and needs lower
values to show any fog. The pass costs one palette snap per fogged pixel and runs in the async
pool in row bands; the 16x9 shot (2048x1152, 8 threads) took 174 s in all, most of it chunk copy
and walk.

### Advanced shading (`Shading`, `ShadingSpec`)

All three are off by default (`photo.shading`). The palette has four shades per color, so each
technique moves a surface down one or two shades; steps add up and stop at the darkest.

- **Sun shadow** (`sun-shadow`): a second ray per **hit** towards the sun, same DDA, up to
  `shadow-distance`. The biggest visual gain. The sun is fixed, not tied to the clock: it looks like
  a render and two shots of a place match.

**The sun belongs to the camera.** Where it stands decides the shadows and where it is drawn in the
sky, so it is part of the picture, like the filter. `Camera#sunYaw`/`#sunPitch` start at
`photo.shading.sun-yaw`/`sun-pitch` and are set with two sliders on the sky screen (§6);
`RenderService#specFor` lays them over the server's shading with `ShadingSpec#withSun`, so a
photo freezes its own sun. The sliders show only while the sun changes something: `sun-shadow` on,
or the sun drawn in the camera's sky. Everything else in `ShadingSpec` stays the server's.
- **Ambient occlusion** (`ambient-occlusion`): four snapshot reads per hit, no ray. If three of the
  four neighbors of the cell in front of the surface are solid, one shade down. Three, because two
  means a wall running along the surface and would darken half of every building.
- **Block light** (`block-light`): one read per hit, the cheapest. Light of the cell in front
  (`max(sky, block)`) below `light-dim-below` is one shade, below `light-dark-below` two. The only
  technique that carries interiors and caves. The sky's light follows the photo's sky (Sky, above).
  Inverted thresholds are pulled together.

**"The cell in front of the surface" is really in front.** AO and light look at the neighbor the ray
**came from**; on side faces that cell used to be taken as `+X`/`+Z` regardless, so half the time
the back of the block was read. The walk's step signs now go to `Shading#stepsAt`.

**Light copy is optional.** `Chunk#getChunkSnapshot(a, b, c)` copies light arrays **by default**, so
the plugin used to pay for light it never read. The four-argument Paper overload is used and light is
requested only while `block-light` is on.

Measured offline (512x512, 2x, one thread, synthetic terrain; ratios matter, not absolute times):
off 2047 ms · AO +7% · sun shadow +11% · both +19%. The hot loop also used to call `Material#isAir()`
per step, which goes to the block registry in Paper; the check was redundant (the color table
returns `NONE` for air) and was removed.

### Measuring

- **On a server:** `settings.render-timing` logs the chunk copy and the ray walk of every capture.
  The wiki's Performance page has a table from the development server.
- **Offline:** the same scene, two renderers side by side, on synthetic terrain without a server.

The move to the pipeline (Apple Silicon, JDK 25, 729 chunks, camera y=100, yaw 45, pitch 30,
frame-height 48, zoom 1.0; median of 5 after 2 warm-ups) was 3 to 7% faster in every scenario with
**bit-identical** output, and filtered renders now cost the same as unfiltered ones.

### Anti-aliasing

`photo.supersampling` is NxN rays per pixel (1 to 4). The palette has no partial transparency, so
alpha is a **majority vote**: fewer than half the rays hit and the pixel stays transparent.

### Chunk budget and loading

- The ray prism is cut into **8 slices** along depth and each slice's box is captured; one big box
  would catch many times more chunks on a diagonal view.
- If the chunks needed exceed the budget derived from `settings.max-capture-area` (`(area/16)²`,
  512 blocks ≈ 1024 chunks), the capture is refused with `CaptureTooLargeException` and the player is
  told to zoom in.
- Unloaded chunks are loaded with `getChunkAtAsync` when `settings.load-missing-chunks` is on;
  `settings.generate-missing-chunks` is off by default (photos should not grow the world).

**The copy is spread over ticks (`ChunkCopier`).** Copying every loaded chunk at once put the whole
copy in one tick: 300 to 700 chunks for a top-down shot, 5,000 to 6,000 for a horizon, 2 to 3 s
measured on the development server. The copier now copies until `settings.chunk-copy-budget-ms`
(8 by default) is spent and continues in the next tick, at least one chunk per tick. The first
slice runs inside `capture` itself, so a preview's few dozen chunks finish without waiting a tick.
Unloaded chunks are loaded 32 at a time (`MAX_LOADING`) and **copied in the load callback**, while
Paper still holds them loaded. Paper finishes every load on the main thread and its loader slows
down when flooded; on the development server, cold chunks of a wide frame: 32 at a time copied
6,289 in 6.3 s with a longest tick of 398 ms, 64 took 16 s for 2,882 with 1.4 s ticks, all at once
25 s for 2,972 with 2.7 s ticks. Most of what is left is the server's own work bringing a chunk in
(that world needs its old chunks converted on load). Holding a loaded chunk with a plugin ticket
until its turn in the copy queue was tried and dropped: a ticketed chunk ticks with the server. A
load that lands after its render was called off still goes into the preview's chunk cache.

**The camera's own chunk is loaded first, asynchronously.** The ray distance reads the ground under
the camera (`getHighestBlockYAt`), which loads an unloaded chunk on the spot: 420 ms of main thread
measured for a camera nobody stood near. `capture` loads it with `getChunkAtAsync` and goes on in
the callback; `floorReference` itself never loads one (an unloadable chunk falls back on sea level).

Measured on the development server, no player online, `/izodev` and RCON `mspt` sampled each
second: the cathedral horizon view at 4x2 (6,289 chunks, all cold) peaked at a 546 ms tick before
and 308 ms after, the copy spreading over 6 s instead of 2.7 s; what is left is Paper's own work
loading and unloading thousands of chunks. Loaded chunks copy at about 0.03 ms each (458 in
14.6 ms), so the case the budget is for is a horizon shot over chunks players keep loaded: some
200 ms in one tick before, 8 ms per tick now.

Two ways a queued chunk could be lost between ticks, two answers:

- **Loaded when the capture began, unloaded by its turn.** It holds no ticket of ours; the copier
  loads it again asynchronously instead of skipping it (it exists, its turn only came late).
- **Loaded by the copier.** `getChunkAtAsync` holds no ticket either, so the chunk is copied in
  the load callback itself, which Paper runs on the main thread while it is loaded.

Copying "when all are done, next tick" without a ticket is what left random chunk-sized holes in
early versions.

**A render can be called off** (`RenderCancellation`): the copier asks between ticks, the walk
before every row, `RenderService` between passes; the future then fails with a
`CancellationException`. Only the preview uses it (§5).

**At most `settings.max-concurrent-captures` photos render at once** (`map/CaptureQueue`, default
2). Each holds its own chunk copy until its walk ends and they share the render pool, so nothing
used to bound memory when several were taken together, or when a startup re-rendered a dozen
photos whose cache was gone. Captures, retakes, cache rebuilds and `image()` re-renders queue in
order; the player is told their place and when it starts. A camera (or, for a retake from a
photo's own spec, the photo) has one job queued or running at a time; a second press is refused
(`photo.busy`). The spec freezes at the press, not at the start. The preview does not queue. Snapshot
memory was not measured.

Missing copies are logged by `RenderService#warnIfIncomplete`.

## 4. Cameras

Three entities, all tagged with the camera id in PDC (`CameraKeys`):

- **Model:** `ItemDisplay` (default, spyglass) or `BLOCK_DISPLAY`; `camera.item-display-transform`
  sets the item's pose.
- **Click box:** an invisible armor stand (`ArmorStand`, no gravity, no ticking, nothing held,
  damage cancelled), scaled with the `scale` attribute to `camera.interaction-size ×
  camera.model-scale` wide (clamped to 0.25 to 3.0) and so four times as tall, centered on the model.
  It used to be an `Interaction` entity, which has no pick result: the server drops a middle click
  on one before any event, so only an entity with a pick result can open the menu by middle click
  (`PlayerPickEntityEvent`). A right click on a stand arrives as `PlayerInteractAtEntityEvent` per
  hand, maybe followed by a plain interact; only the main hand's "at" acts. Cameras that still have
  an `Interaction` get a stand when their chunk loads (`CameraManager#replaceClickBox`).
- **Hologram:** a `TextDisplay` `camera.hologram.offset-y × model-scale` above the model.

`Camera` holds state only; entity work is in `CameraManager`. Memory is the source of truth and the
whole collection is written to `cameras.yml` after every change (debounced, §8).

### The hologram

The code does not know what it says: lines come from the language file → `camera.hologram.lines`, the
code offers placeholders (`<name> <owner> <ratio> <zoom> <blocks> <yaw> <pitch> <filter> <style>
<sky> <photos>`). `<photos>` is maintained by `PhotoManager` calling `CameraManager#refreshHolograms`
after every change and after loading (photos load after cameras).

#### The unloaded-entity rule

`getEntity(UUID)` finds only entities in **loaded chunks**. Three rules follow:

- **Load the chunk before deleting** (`CameraManager#forget`, and `PhotoManager#removeFrames` for
  frames). Otherwise the record goes and the model stays as an orphan nobody can remove.
- **Reapply the transform when entities load.** Most camera chunks are unloaded at startup;
  `CameraListener` refreshes on `EntitiesLoadEvent`, matching **by id**, not type: the hologram is a
  `Display` too and the model's rotation would tip it over.
- **Rebuild a missing hologram only if the model resolves.** `null` means "missing **or** unloaded";
  rebuilding blindly would add a second hologram to every camera on every start. For the same reason
  an unresolvable hologram's id is not dropped when holograms are turned off.

`/izocam cleanup` removes Izomap entities with no record from the loaded chunks of the caller's
world.

### Interaction

| Input | Result |
|---|---|
| Right-click | Raise the active property |
| Left-click | Lower it |
| Shift + right-click | Next property (YAW → PITCH → ZOOM → MOVE) |
| Middle-click or shift + left-click | Open the capture dialog |
| Right-click a block with the camera item | Place a camera there (the item is used) |

**Only the owner and `izomap.admin` edit** (`Permissions#edit`): anybody else's click opens the
preview and, once it is open, says whose camera it is (`camera.view-only`); the capture screen's
pickup button and its confirmation ask the same. The first four rows need the **editor seat** (§5). **The first click only opens the preview:** a player
not yet watching gets the preview and nothing else; acting on the click too used to nudge the
camera before the player could see it. A player whose off hand is full gets no preview and edits on
the first click. A camera placed with the item opens its preview on placement.

Zoom is **multiplicative** (`camera.zoom-step`), so a step is proportionally the same anywhere in
0.02x to 16x. The action bar shows how many blocks the frame covers.

**Move** follows the **player's** view vector (the player is looking at the camera while adjusting
it, so "push a little, pull back" needs no thought), pitch included. It uses
`CameraManager#reposition` (display and interaction move together, no disk write; the shared
`applyAndPersist` at the end of each interaction writes once), clamps to the world border, and its
status value is the camera's position.

**Pickup.** A camera placed with the item has `placedFromItem` (`cameras.yml` → `from-item`); picking
it up returns the item. A command-made camera returns nothing (no item is invented). Photos go with
their camera (a photo without its camera cannot be retaken), so pickup confirms, and only the
owner or `izomap.admin` may do it (`Permissions#edit`, asked when the button is drawn and again on
confirm); `/izocam pickup`
skips the confirmation when there is nothing to lose. A full inventory drops the item at the
player's feet and says so.

`zoom` is the photo's frame, `camera.model-scale` the model's size; they never affect each other.
`camera.model-rotation.{x,y,z}` aligns a custom model, applied Y → X → Z.

## 5. The live preview

`PreviewManager` puts a 1x1 (128x128) map showing what the camera sees in the player's **off
hand** and refreshes it on every edit. The off hand must be empty; the map cannot be dropped (Q ends
the preview cleanly), swapped or moved; it is removed on quit and cleaned on join after a crash.

- **The map id belongs to the camera.** An id is spent for good once allocated (the server writes a
  `map_<n>.dat` and nothing gives it back). Allocating per session burned an id per use; the id is
  stored as `preview-map-id` and the view is reused and cleared on open.
- **One session per camera** (`PreviewSession`): one `MapView`, one render, many viewers. Viewers'
  maps point at the same view.
- **One editor** among those allowed to edit (owner, admins). Every interaction asks for the seat; a player who cannot get it is told who has it
  and joins as a viewer. The seat frees when its holder leaves the preview, disconnects, or does not
  touch the camera for `camera.edit-lock-seconds`. The seat is independent of the map: a player with
  a full off hand can still edit.
- **Exits:** `/izocam preview stop`, dropping the map (cancelled, preview ends), death (the map is
  removed from drops), quitting, the camera being removed (every viewer's preview closes), shutdown
  (`onDisable` collects maps, since plugins are disabled before players are kicked). Each has its own
  `preview.ended-*` message.
- **Status line** (`CameraStatus`): everybody watching sees the settings in the action bar, the
  active property highlighted. Click feedback and the once-a-second refresh go through the same code.
  Templates are `preview.actionbar`, `preview.entry`, `preview.entry-active`, `preview.value-*`;
  labels `preview.property.<NAME>`. The task starts with the first viewer and stops with the last.
  A budget warning puts the session in a 5-second notice mode so it is not overwritten.
  `actionbar-rendering` adds "Updating" while a render runs (the async gap read as a lost click);
  `actionbar-capturing` shows `<percent>`/`<bar>` from `CaptureProgress` during a capture (they count
  ray-walk rows, so they sit at 0 during the chunk copy). A half-second task refreshes hologram and
  status only while a shutter is open.
- **Only the latest state is rendered.** A change that arrives while a render is still copying
  cancels it and starts a new one at once; a generation counter makes a late result from a
  cancelled render drop itself. A render already walking its rays is let finish, with a `pending`
  flag for one more of the latest state: the walk of a 128 pixel map is quick, and cancelling
  there too left a player who kept clicking with no frame at all. Nothing queues beyond that one.
- **The preview never waits for the disk** (`ChunkCache`, per session). It draws with the chunks
  that are loaded or kept and fetches the rest in the background, 32 at a time; as they land it is
  drawn again, at most once a second and once more when the last is in. A wide frame reaches
  thousands of chunks nobody is near, and waiting for them made every click take seconds:
  measured with `/izodev clicks` (20 clicks, four a second, 128x72, 3,126 chunks), the copy took
  3.7 to 4 s against 0.1 to 0.2 s of walk, and only the last click ever showed. Drawing without
  waiting, every click showed its frame in 70 to 290 ms, the longest tick 37 ms once warm. Depth
  of field made no difference either way (the walk is 0.1 s of it). Photos always wait for every
  chunk.
- **Chunk copies are kept**: a copy of a loaded chunk for five seconds, so the preview lags a
  change in the world by at most that; one the cache fetched itself, where no player is, for a
  minute. Only copies taken with the same extras (light, biomes) are used again. Cleared when the
  session ends.
- **Every preview render records depth** for the fog hint (`SceneDepth`, §3 Fog); at 128 pixels it
  costs nothing.
- **The camera's own ratio**, letterboxed on the square map: it shows the real frame and the real
  cost (a 16:9 photo needs about 1.8x the chunks of a 1:1 preview), so the budget fails before
  placement rather than after.
- **Rule of thirds** (`camera.thirdsGuide`) is drawn on the preview only, in alternating light and
  dark 3-pixel dashes so it shows on snow and in shade.

## 6. Grids, slicing and placement

| Ratio | Grids (`GridLayouts`) |
|---|---|
| 1:1 | 1x1, 2x2, 3x3 |
| 16:9 | 1x1 (128x72), 4x2, 8x4, 16x9 |
| 4:3 | 1x1 (128x96), 4x3, 8x6 |

`GridOption` carries the picture size beside the tile count. The larger grids are filled exactly
(`cols*128 × rows*128`); a single map for 16:9 or 4:3 holds the largest picture of that ratio
(`GridOption#fitted`), centred, the rest transparent so the map's background shows as bars, the
way the preview letterboxes. The render is done at the picture size, so the photo, its cache file
and its export are 128x72, and only `ImageSlicer` knows about the bars when it cuts row-major
128x128 `MapTile`s. Because the same label is a filled grid for one ratio and a letterboxed one
for another, the capture screen looks its grid up (`GridLayouts#find`) rather than parsing it, and
`photos.yml` stores a letterboxed grid's `picture: 128x72` beside its `grid`. `MapService` makes locked, untracked `MapView`s
with a one-shot `TileMapRenderer` (`MapCanvas#setPixelColor`, transparent pixels skipped).

### Taking and hanging are separate

A photo **exists without hanging**. The dialog's button only takes it; it lands in the camera's
list, where it is hung, taken down, renamed, retaken or deleted. A name collision is **numbered**
(`view`, `view-2`), since the dialog proposes the camera's name. Taking down never deletes.

### The capture screen

Two inputs (name, grid) and a three-column button grid:

| Row | Buttons |
|---|---|
| 1 | The three aspect ratios |
| 2 | Color · Style · Sky |
| 3 | Focus · Rule of thirds · Photos |
| 4 | Take photo · Pick up camera · Reset aim |

- **Depth of field has its own screen**, like the sky: switch, slider (`DialogInput.numberRange`,
  only while on, its maximum this camera's ray distance from `RenderService#focusRange`), a hint and
  three suggestions read off the last preview's `SceneDepth`: the frame's center (the median depth
  of its central tenth), the nearest ground (5th percentile) and most of it (median). Switching it
  on starts at the center, or the ground the frame is aimed at before any preview (starting at zero
  would put the focus plane at the lens and blur everything). Its value arrives with the next
  button press (`DialogResponseView#getFloat`); there is no live channel. Both side screens end on
  a green **Confirm** that goes back.
- **The sun gets a hint and two suggestions** on the sky screen: where it stands relative to the
  camera (its yaw is the way the light travels, so the camera's own yaw puts it behind the lens),
  "side light" (60 degrees off the camera at 35, while the server casts shadows) and "sun in the
  frame" (opposite the camera, `30 - pitch` degrees up, which lands halfway up a frame that spans
  the hemisphere around the view; while the sky draws it).
- **Color and style are cycle buttons**, not dropdowns: a button can show the current value. The sky
  has its own screen (§3), which also carries the sun's two sliders (direction 0 to 355 in steps of
  5, height 1 to 89) while the sun matters; every button on it sends them.
- **Emphasis lives in the language file:** a value's color is in its own name, the button template only
  inserts `<value>`.
- **Reset aim** undoes only what clicks set: zoom, facing, tilt.
- **A button renders only on a real change.** `CameraDialogs#applyForm` compares the image-defining
  fields (ratio, thirds, zoom, filter, style, sky, bodies, focus, focus distance, sun) before and after;
  nothing changed means no save and no render. Navigation buttons used to trigger a full chunk copy.
- **Zoom is not set in the dialog.** A second way to set it rounded the clicked value to the nearest
  preset whenever any button was pressed. The info line (`dialog.info`) summarizes instead.

### The placement ghost (`place/PlacementManager`)

"Hang" starts placement mode: grid-sized `BlockDisplay`s follow the player's view, glowing green
when it fits and red when not (`Display#setGlowColorOverride`).

| Layer | What | Where | When |
|---|---|---|---|
| Frame | `WHITE_STAINED_GLASS_PANE` | The frame cell, against the wall | Always |
| Backing | `placement.backing-material` | One block behind the frame | Only where a block will really be built |

- Panes are connected along the wall (`MultipleFacing`) so the grid reads as one surface, and shifted
  6.5/16 forward to sit where the map will (the half pixel avoids z-fighting).
- The backing layer is checked cell by cell (`isEmpty()`) on every update, since others can build;
  `showEntity`/`hideEntity` go out only on change.
- **Only the placing player sees it** (`setVisibleByDefault(false)` + `Player#showEntity`), and it
  is not persistent: a crash must not leave a wall of floating blocks.
- Updates every two ticks, teleporting only when the area really changed (a 16x9 grid with two
  layers is 288 entities).
- **Fit** is `PlacementArea#fit`, which says why not: `OCCUPIED` (a frame cell is taken),
  `NO_SUPPORT` (behind a frame is neither solid nor, when a backing block may be built, empty) or
  `PROTECTED`. The action bar names it.
- **Backing needs `izomap.backing`** as well as `build-backing-wall`: the block comes from nowhere.
  `MapPlacer#buildsBacking` is the one place that says so; the ghost shows backing only then.
- **Protection is only known by trying** (below), so the ghost cannot ask it each update. A confirm
  keeps the session open until `PhotoManager#place` answers; on `PROTECTED` the area is remembered,
  the ghost stays red there until the player aims elsewhere, and they can try again. Clicks during
  the answer are ignored.
- **Confirm** is a right-click anywhere, **cancel** shift + right-click; also `/izocam cancel`,
  death, world change, quit, the photo being deleted and `placement.timeout-seconds`. `cancelAll` on
  shutdown. The live preview closes when placement starts (both want the action bar).
- No click box: an `Interaction` box is square in X and Z, so a 16-wide photo's box would reach 8
  blocks towards the player and swallow everything.

**The off-hand marker is required.** The client walks both hands on a right-click into the air and
**skips empty ones**; with two empty hands nothing reaches the server and confirmation would be lost.
An `ITEM_FRAME` sits in the off hand during placement, the player's item comes back afterwards, and
the handler accepts both hands. The marker is PDC-tagged and cannot escape (drop cancels the session,
swaps and moves are blocked, on death it is swapped back, on join it is cleaned).

### Hanging (`MapPlacer`, `PlacementArea`)

`placement.distance` blocks along the player's view, a grid facing the player with the image's
orientation preserved. The bottom row sits **one block below eye level** (`ANCHOR_DROP = 1`); the
ghost and the real placement share the calculation. Placement is non-destructive: `fit` is checked
again and nothing changes if it fails. With backing allowed, empty blocks behind frames are filled;
solid ones are used as they are.

**Protection plugins decide by the events a player would fire** (`place.Protection`), so WorldGuard,
GriefPrevention, Lands, Towny and the rest are covered with no dependency on any. Each piece is put
up the vanilla way: a backing block is set and a `BlockPlaceEvent` fired with its replaced state, a
frame is spawned and a `HangingPlaceEvent` fired. The event describes a change really made, so
block loggers record the truth. The first cancel takes everything put up so far back down (frames
removed, block states restored newest first) and the photo is refused whole. Maps are made only
after every piece passed, since a map id is spent for good.

Cameras are not blocks, so they ask with a `BlockPlaceEvent` on the anchor block that changes
nothing (`Protection#mayBuild`), the question every protection plugin answers: before one is placed
with the item, made or moved by command, and when a move click enters a new block.

### Decoration frames (`PhotoFrames`, `frames.yml`)

Three ways to write one, all compiled to the same thing: an **edge strip** (`thickness × length`,
row 0 outermost, repeated along the side) and an optional **corner stamp** (`thickness ×
thickness`, mirrored to all four corners). The drawing code does not know how a frame was written.

| Form | For | Compiled as |
|---|---|---|
| `rings` | Color + width rings | Strip of length 1, no corner |
| `edge` / `corner` | Patterns and corners as character art with a `palette` | Rows become strip and stamp |
| `texture` | A PNG in `frames/` | Nine-sliced at `inset` |

**Light and shadow** (`bevel`, `shadow`, any form). The sides are drawn mitred: a pixel belongs to
the edge it is nearest, which is a 45 degree join in every corner (the vertical sides used to
overwrite the corners of the horizontal ones). With `bevel`, light from the upper left: the top side
moves that share towards white, the bottom towards black, left and right half as much. With
`shadow`, the photo just inside darkens over that many art pixels, fading out, deeper under the top
and left. Every lit pixel is snapped back to the palette, through a cache per draw keyed by colour
and amount (a frame repeats a handful of colours over thousands of pixels).

Transparent art pixels show the photo; semi-transparent PNG pixels count as transparent (no alpha
in the palette). `scale` is photo pixels per art pixel, `auto` one step per 256 pixels of the short
side (1x1 → 1x, 8x6 → 3x, 16x9 → 4x, at most 8x). Colors are snapped **once at load**: the cache
looks colors up by exact match and an off-palette color would become a hole. The frame is drawn
**over** the outer pixels (no re-render, no resampling); total depth is capped at 20% of the short
side and cut from the inside, so the outer rows survive.

| | `photo.frames.embed: false` (default) | `true` |
|---|---|---|
| Record | `frame.id` | `frame.id` + `frame.embedded: true` |
| Drawn | On the way to the maps, every time | Once, into the cache file |
| Changeable | Yes | **No** |

The choice freezes when the frame is added. `PhotoManager#baked` draws it on the way to the file
(embedded), `#framed` on the way to the maps (referenced): exactly once either way, retakes and
cache rebuilds included.

**The wall photo screen** (`ui/PhotoDialogs`): a right-click on a hung photo opens retake / frame /
take down, for the **owner and `izomap.admin` only**; for anybody else it does nothing, as before.
`EquipmentSlot.HAND` is filtered (the event fires per hand). Buttons resolve the photo **by id** and
recheck permission on click, since the screen may stay open.

### Item frame behavior (`PhotoFrameListener`)

Breaking one frame (player, explosion, physics) takes **the whole photo** off the wall, no drops;
the photo stays in the list. Attacking and rotating frames are blocked. Frames are recognized by
**their own PDC tag** (`izomap:photo_id` + `izomap:tile_index`), so protection works before
`photos.yml` loads ("still loading"); a tagged frame with no record after loading is an orphan and
is removed without drops.

**Orphans are swept without being asked** (`PhotoManager#sweepOrphanFrames`): on every
`EntitiesLoadEvent`, and once over every loaded world when `photos.yml` has been read (chunks loaded
before that could not tell an orphan from a frame whose record had not arrived). An orphan is a
tagged frame no placed record claims by id. **Nothing is swept while there are no records at
all:** that is what a `photos.yml` that failed to parse looks like, and sweeping then would take
every photo on the server down. `/izocam cleanup` still clears them on request.

`/izocam cleanup` works both ways: `cleanupOwned` moves records whose frames are gone to "not hung",
`removeOrphanFrames` removes frames no record claims (no record, a record saying "not hung", or a
record pointing at other frames). Loaded chunks only; nothing counts as orphaned before `photos.yml`
has loaded. Moving a hung photo removes the old frames first; if the new spot fills meanwhile, the
record becomes "not hung" rather than lying.

### Restart: the photo cache (`PhotoCache`)

Photos are **not retaken on startup**: `plugins/Izomap/photos/<uuid>.izm`, one byte per pixel of
vanilla map palette index (`baseId * 4 + shadeId`, `0` transparent), Deflate-compressed:

```
int "IZMP" · int version · int width, height · int cols, rows · deflate(indices)
```

Lossless (every pixel is a palette entry) and read by table lookup. A 16x9 (2048x1152) landscape is
2.25 MB raw, about 0.9 MB stored (9 MB as ARGB).

The cache is **primary but not essential**: missing, wrong version or corrupt, the photo is
rendered once from its saved `CaptureSpec` and the cache rewritten. A format change bumps the
version; no migration.

**`CaptureSpec`** carries everything that decides the image by copy (world, position, angles, zoom,
filter, style, sky, shading, water, focus, fog, frame height and shift, supersampling, capture area,
render depth). Cost and scheduling settings (`render-threads`, chunk loading) stay in the config.
A hung photo never changes when its camera turns, zooms, refilters or disappears. Records without a
spec fall back to the camera once and get a spec written. Deleting a photo deletes its cache file;
on startup `retainOnly` sweeps orphaned `.izm` files, **unless the record set is empty** (a failed
`photos.yml` load must not wipe the cache).

### Retake and export

**Retake** shoots onto the same maps without taking the photo down, with the source camera's
**current** settings; if the camera is gone, the photo's own spec (same view, changed world).
`retake with <camera> <photo>` uses another camera and moves the photo to it. A failed capture
changes nothing; on success the cache is written first, then maps and record. **Retake asks for no
permission.**

**Export** (`PhotoExporter`, `/izocam export [as <file>] <photo>`, `izomap.export`) writes a PNG to
`plugins/Izomap/exports/` from `PhotoManager#image` (cache first, else re-render). Encoding and I/O
are async. The file name is player input: everything outside `[A-Za-z0-9._-]` becomes `_`, leading
dots are dropped, length is capped at 64, and the resolved path is **checked to stay inside** the
folder. Without a name: `<photo>-<yyyyMMdd-HHmmss>.png`. Export resolves every player's photos; a
bare name is accepted only when exactly one photo answers.

## 7. Commands and permissions

Brigadier, registered on `LifecycleEvents.COMMANDS`. The wiki's Commands page is the user-facing
list. Every argument has suggestions; grid suggestions follow the previous argument's camera ratio.

**Camera names reach the caller's own cameras.** An admin reaches anybody's when exactly one camera
has the name, and is offered every name. Anybody naming a camera that exists but is someone else's
is told whose it is (`camera.not-yours`): "not found" for a camera in plain sight read as the plugin
having lost it, when the camera had been made under another UUID (an offline-mode account, before
the server went online).

**Photo references are `<camera>/<photo>`.** Resolution order: `camera/photo`, bare photo name (names
are unique per player), short id. The reference argument is **greedy** (names may contain spaces,
and Brigadier refuses `/` in an unquoted word), so optional arguments go in front of it:
`retake with <camera> <photo>`, `export as <file> <photo>`.

### Permissions (`config/Permissions`)

`Permissions` is the one place for node names and "what allows what"; `paper-plugin.yml` only
declares them and their defaults.

- **Area or single option.** `izomap.filter` grants every filter, `izomap.filter.WARM` one; either
  passes. Same for sky, ratio and frame.
- **`izomap.fog`** and **`izomap.style.shader`** gate the camera's fog and the shader style;
  both are checked again at capture.
- **`izomap.backing`** is about building, not cost: it lets a backing block be built (§6). Operators
  by default.
- **The cheapest value is free:** `ORIGINAL` filter, `NONE` sky, `FAST` style. A player with no
  node can still shoot: permissions decide **how expensive** a photo may be. A new camera placed by a
  player without `izomap.style.sharp` starts in `FAST`.
- **The screen shows only what is allowed**, but the camera's current value always stays listed. A
  button left with a single option is not drawn.
- **The server checks again** (`PhotoManager#mayCapture`): style, filter, sky, bodies, ratio, focus
  and grid at capture. **Nothing is silently downgraded**; a refusal names the setting.

### Number permissions (`config/PermissionLimit`)

| Prefix | Overrides |
|---|---|
| `izomap.max_photos_by_camera.<n>` | `settings.max-photos-per-camera` |
| `izomap.max_cameras_by_player.<n>` | `settings.max-cameras-per-player` |
| `izomap.max_map_tiles.<n>` | `settings.max-map-tiles` |

A node replaces the config value entirely (a smaller number holds a group below the default); with
several, the largest wins (permissions accumulate from groups); `.0` is none, `.unlimited` is
`PermissionLimit.UNLIMITED`; non-numeric suffixes are skipped (another plugin's node). They are
**deliberately not declared** in `paper-plugin.yml`, so a wildcard like `izomap.*` hands out no
number; they are read from `Player#getEffectivePermissions()` by prefix. The smallest grid of a
ratio stays selectable above the tile limit. The photo limit is per camera and counts unhung photos;
the real check is inside `PhotoManager#capture`.

### The web editor (`editor/EditorServer`, the editor kit)

The plugin answers a JSON API itself, with the JDK's `HttpServer`, on `editor.bind:editor.port`
(127.0.0.1:8790 by default). The page reaches it one of two ways:

- **Through the relay** (`editor.relay`, `https://izomap-relay.zypec.workers.dev` by default), from
  any browser, with no port opened on the server. The relay, the page's tunnel and the plugin's end
  of it are the **editor kit**, shared with Particles+: `zypec/editor-kit`, published as
  `dev.zypec:editor-kit` at `https://zypec.github.io/maven` and shaded into the jar under
  `dev.zypec.izomap.lib.editorkit`. Its `RelayLink` dials the relay the first time a link is asked
  for; a link is `<relay>/#<session>.<key>`, and everything the page and the plugin say to each
  other is sealed with the key (AES-GCM), which only the link holds, in its fragment. What the page
  asks arrives at the local server from `RelayLink` with `Authorization: Bearer <token>` (made fresh
  at every start) and `X-Editor-Who-Id`, the player who asked for the link; the token is what makes
  that header trusted. A link opens one tab, a new one takes back the player's older ones
  (`revokeAll`), and one unused for `editor.idle-minutes` is let go of.
- **Directly**, with `editor.relay: ""`: the server serves the page as well, so it opens where the
  address can be reached, and every call carries `X-Izomap-Session`, a 128-bit key from the link
  (`EditorSessions`), which stops after `editor.idle-minutes` unused or when a new link is asked for.

Either way a call acts as that player, with their permissions, while they are online, or as the
console: `izocam editor` from the console or RCON hands out a link with nobody's name on it (a relay
link with no owner, which is the only kind of request that reaches the local server with the token
and no `X-Editor-Who-Id`; a session key whose player is `null`). The calls take a `CommandSender`;
the console has every permission, no camera, photo or tile limit, picks the world (`?world=`), sees
every player's cameras, and names an owner to place one for (`CameraManager#createFor`, no
protection asked, as for its commands). A photo it takes goes to the camera owner
(`PhotoManager#capture(CommandSender, UUID, ...)`), what is said about it to the console, and its
capture progress is watched by nobody in particular (`NOBODY`), so the hologram still counts it. The page
(`resources/editor/index.html`) is the same for both: it loads the kit's `kit/net/relay.js` and
uses `relay.fetch` when the link is a relay link, `fetch` otherwise. Its windows, dropdowns,
switches, sliders, boxes, toasts and theme switch are the kit's interface (`kit/ui/*.js`,
`kit/ui/ui.css`, the same pieces Particles+'s editor runs on); the page sets only Izomap's colors
and lays them out. The kit's jar carries the web files and the relay's `worker.js` beside the Java
class, so page, relay and plugin always come
from one version of the kit: the plugin serves `kit/net/relay.js` from the jar in the direct mode,
and `./gradlew editorSite` writes the relay's site (`build/editor-site`, the page with `kit/`
beside it) and its worker (`build/relay/worker.js`) from the same jar. `relay/wrangler.toml` runs
that task before every `npx wrangler deploy`; `npx wrangler dev --port 8788 --ip 127.0.0.1` in
`relay/` is a relay on this machine for a test server with `editor.relay: "http://127.0.0.1:8788"`.
A sibling checkout of `../editor-kit` (or `-PeditorKit=<folder>`) replaces the published kit in the
build, so a change to it is tried here before it is published.

**The aim plane.** A frame dragged on the map used to land somewhere else: the plugin read the ground
afresh at the new target to place the camera, then drew the frame from where the middle ray met the
ground, which on roofs and slopes is a different height again. A camera now keeps `aimY`, the
height of the plane it was aimed on (`cameras.yml` `aim-y`), and `RenderService#frame` draws the
frame on that plane. A drag sends `targetY` to stay on it, so the frame lands exactly where it is let
go; turning, tilting and zooming keep the target on it too. A click with Move reads the ground at the
spot (loading the chunk first, off the main thread). Moving a camera in the game (`/izocam move`,
the MOVE click) drops the plane, which belonged to the old place. On the map the frame's zoom is read
along the line from its middle through the handle held, so sliding along a side changes nothing.

**Open air.** A camera the editor stands somewhere (`RenderService#standClear`) is drawn back along
its view until it is above the ground under it. Along its own view an orthographic camera's
picture does not change; inside a hill its lowest rays, which start at its own height, would print
the hill's insides along the bottom of the photo. A level camera cannot climb this way, and below
about five degrees whatever of the frame is under the ground shows its inside: Portrait starts at 8.

**Undo.** The page remembers every change as the fields that differ between the camera before it
(before a drag or a run of keys moved it on the page) and the camera the plugin answered with, and
undoes it by sending the old ones back. Changes to the server's settings wait in a list, and an
applied set is undone by applying the old values.

**Layout and fog.** A camera keeps the layout its photos are taken at (`cameras.yml` `layout`,
`GridLayouts#chosenFor`, which falls back to the ratio's first the player may shoot); the capture
screen's Ratio & Layout screen and the editor's layout both write it, and a capture reads it. The
fog the editor and the capture screen offer is read off the last preview's `SceneDepth`: Haze
(from 60% of the way between the typical and the furthest ground to 1.3 times the furthest), Soft
(from the typical ground to 1.15 times the furthest) and Thick (from halfway between the nearest and
the typical ground to 60% of the way to the furthest). It used to be fog full at the furthest
ground itself, which whited the back of the photo out.

**Reload.** `/izocam reload` used to start the editor again, which closed the relay link and every
open tab. `EditorServer#reload` starts it again only when one of its own settings changed.

**Saving and copying.** Save PNG and Copy shoot the camera at the layout's full size through the
capture queue, so a save waits its turn like any photo and the server never runs more than
`settings.max-concurrent-captures`. Copy hands the clipboard a `ClipboardItem` whose PNG is the
promise of that answer: a browser only lets a page write to the clipboard while the click that
asked is fresh, and a wide shot takes longer than that. A box a Look tile opens is drawn again
only when what it says has changed; drawn again under a press, it swallowed the click.

`EditorServer.API` is the API's version; the page names the same in `relay.configure({api})`, and
a page and a plugin that disagree say so instead of misreading each other. Since the relay serves
one page to every server, a change that an older plugin cannot answer raises both.

| Call | Does |
|---|---|
| `GET /api/alive` | Nothing: a page back from the background asks it to tell a live tunnel from a dead one |
| `GET /api/state` | The player (or the console, with the worlds and the players it may place a camera for), their cameras in their world (the console: everybody's in the world asked), every choice with its name in the server's language and whether the player may use it (filters, styles, skies, ratios, grids per ratio, frames, fog, focus, sun and moon, export) |
| `GET /api/cameras/<id>` | One camera as the state lists it, with the capture screen's suggestions read off its last preview's `SceneDepth`: focus distances, a fog range, side light and sun in the frame |
| `GET /api/tile?level&x&z[&world]` | One 256 px tile of the world from above, north up, `64 << level` blocks wide (level 0 to 3). Drawn by the walk at pitch 90 the first time, then served from memory for ten minutes (at most 48 MB, the least recently asked for dropped); two asking for one share the drawing, and at most two are drawn at once. A tile copies every chunk it covers: on the development server a 256-block tile took 0.7 s cold, a 1024-block one 7.5 s, which is why the screen stops at 2048 blocks across |
| `GET /api/cameras/<id>/progress` | Where the camera's photo or export stands: its place in line (`position`, 0 while it runs) and how far it is (`percent`), polled by the island |
| `POST /api/cameras {x,z}` | A camera 12 blocks above the ground there (protection asked) |
| `PATCH /api/cameras/<id>` | Any setting the capture screen has, plus position, the thirds guide, `name` (the camera's photos follow it, `PhotoManager#cameraRenamed`) and `aimY`; only the fields sent. `targetX`/`targetZ` aims the frame there, `orbit: true` turns facing and tilt round the frame's middle |
| `GET /api/cameras/<id>/preview?w[&frame&grid]` | The camera at its ratio, `w` wide; it records the scene's depth as the in-game preview does, and keeps the chunk copies of the last two cameras previewed (`ChunkCache.forEditor`: a whole picture, copies kept two minutes), so a wide frame that took 12 s cold came back in 0.35 to 1.3 s. With a frame, it is drawn at the grid's full size and shrunk back, so it is as thick against the picture as on the photo |
| `POST /api/cameras/<id>/photo {name,grid}` | `PhotoManager#capture`, queue and all |
| `POST /api/cameras/<id>/export {grid,frame}` | `PhotoManager#shootForExport`: the same checks and queue as a photo, the frame drawn on, a PNG back and no photo filed. `izomap.export` |
| `POST /api/cameras/<id>/reset` | The capture screen's Reset aim |
| `POST /api/cameras/<id>/mode {mode}` | A kind of shot from where the camera is aimed: `top` (pitch 90), `photo` (16:9, default tilt), `portrait` (3:4 or 9:16, 8 degrees, raised half a frame up its own up so the frame's bottom edge meets the ground at the target), `square` (1:1) |
| `GET /api/frames/<id>/sample` | The frame drawn round a plain 360x96 box, for the frame list |
| `GET`/`PATCH /api/config` | `config.yml` for admins and the console (`EditorConfig`): every key of the shipped file with its value, default, kind, the range and choices the wiki's reference gives (`reference/config.yml`, copied into the jar) and its words. A change is checked against all of that, written into the server's file with its comments kept, and applied with `Izomap#reloadAll` |
| `GET /api/cameras/<id>/photos` | The camera's photos |
| `GET /api/photos/<id>/image[?w]` | The photo as it hangs, frame and all: at full size (`izomap.export`) or shrunk to `w` (at most 480) for the list |
| `PATCH /api/photos/<id> {frame}` | `PhotoManager#applyFrame`; `""` takes it off |

**The editor aims the frame, not the camera.** An orthographic camera's position along its view does
not change the picture, so a position on a map says little about the shot, and the same was felt in
the game. `RenderService#frame` gives what does: the point the frame's middle is aimed at and the
frame's four corners on the ground there (ground read only in loaded chunks, sea level elsewhere,
so it never loads one). The map draws that frame; dragging it sends a new target, and the plugin
puts the camera back along its view at the same distance (`RenderService#standFor`), as it does
when facing or tilt change with `orbit`. A level camera has no ground to aim at; its target is 96
blocks ahead.

Requests run on a pool of four; anything touching the world hops to the main thread and is waited
for, renders run on the render pool.

## 8. Configuration and data

All config reads go through `ConfigManager`; key names and defaults live there and values are
clamped. `saveDefaultConfig()` keeps an existing `config.yml`, so a changed default does not reach
old installs; `photo.frame-shift` above 0.25 is warned about on load and reload because it silently
empties photos.

Until the first release there is **no backwards compatibility**: no install exists to migrate.
From the first release on, its files count as version 1 and later versions keep reading them. Each
shipped user file carries a `version:` key for that purpose; nothing reads it yet.

| File | Holds |
|---|---|
| `config.yml` | Settings |
| `lang/<code>.yml` | Every visible text, console included, one file per language (`settings.language`); missing keys fall back to the jar's copy of that language, then English |
| `filters.yml` | Color filter definitions |
| `frames.yml` | Decoration frames |
| `block-colors.yml` | Color overrides, coverage, tint channels |
| `biome-tints.yml` | Biome color overrides |
| `cameras.yml` | Cameras: position, angles, zoom, ratio, filter, style, sky, bodies, thirds, focus, fog, sun, entity ids, preview map id |
| `photos.yml` | Photos: name, camera, grid (and `picture` when letterboxed), `capture` spec; when hung, `placement` with map ids, frame ids and anchor |
| `photos/<uuid>.izm` | The photo's image (binary) |
| `exports/<name>.png` | `/izocam export` output; never read back |

`YamlStorage` never writes on the main thread, except `saveNow()` in `onDisable`. Records are
serialized whole, but **saves are debounced**: `requestSave` schedules one write 20 ticks later and
every request until then folds into it, the serializer running at that moment (a camera turned
click after click used to rewrite `cameras.yml` per click). Writes go through one thread per file,
in order, newest dump wins (two saves on a shared pool could finish the wrong way round), to
`<file>.tmp` and then moved over the file atomically, so a crash mid-write leaves the old file whole
rather than empty. `saveNow` drains the writer first.

### Languages (`Messages`)

`settings.language` names a file in `lang/`. Every shipped language is written out on first start.
The server's file reads its gaps from the jar's copy of the same language, and that one from
English (`setDefaults` chains), so a key a newer version added shows the shipped text, never a
placeholder. Messages load in English before the config is read (the config reports through
them) and again in the configured language right after; `/izocam reload` reads the config first
for the same reason. `lang/en.yml` is the reference and holds every comment; other languages keep
only the keys.

### Where text goes

| Surface | Path |
|---|---|
| Player messages | `Messages#send` / `#get` |
| Console | `Messages#info` / `#warn` / `#error` → `log.*` keys (MiniMessage works there too) |
| APIs that want a `String` | `Messages#plain` |
| Text left in code | **English** exception messages only |

`<reason>` is filled by `Messages#reason` (`Failures#unwrap`, falling back to `log.no-reason`).
Enum display names live in the key tree (`filter.<NAME>`, `preview.property.<NAME>`); only constant
names are saved.

## 9. Development rules

1. **No heavy work on the main thread.** Ray casting, slicing and YAML I/O are async
   (`Izomap#asyncExecutor`); block, entity and `MapView` access returns to the main thread
   (`Izomap#runOnMain`). The scheduler is not called directly.
2. **Paper API, not NMS.** The one exception is biome colors (`ServerBiomeColors`), and it defines
   the bar: no API equivalent, confined to **one class**, called **once at startup**, and the caller
   catches `Throwable` and disables the feature. A new NMS use that does not meet all four is not
   written.
3. **All visible text comes from the language files** (`lang/en.yml`, `lang/tr.yml`), console included. MiniMessage only, never legacy
   `&` codes. `getLogger()` is not called directly.
4. **Brigadier commands** with meaningful `suggests`; failures throw `CommandSyntaxException`.
5. **Config through `ConfigManager`**, never `getConfig().getX("...")`.
6. **Feature packages.** Helpers that cross packages go to `util/`; something every subsystem holds
   goes on `Izomap`. The same logic never lives in two places.
7. **`var`** for locals whose type reads from the right-hand side; fields, parameters and return
   types stay explicit.
8. **Minimal English comments.** A comment says a *why* the code cannot; long explanations belong
   here. No references to conversations, TODO ids or history in code.

## 10. Tests

`./gradlew test`, JUnit 5, no server. Paper classes are on the test classpath (`testImplementation`
extends `compileOnly`), so interfaces like `Player` can be proxied; `FakeChunk` builds a
`ChunkSnapshot` from a function (every unasked method throws) and `BlockColorTable.of` builds the
table without a server, so the ray walk itself is testable.

| Test | Guards |
|---|---|
| `MapColorConverterTest` | All 244 palette colors survive color → byte → color; otherwise photos silently change after a restart |
| `SkyTest` | The clock circle, the dither cell's 4-pixel period, every sky pixel on the palette |
| `StylePassTest` | Style passes stay on the palette and never average a hole into a color |
| `PermissionLimitTest` | Number permission rules |
| `PermissionsTest` | Free defaults, area versus single option, grid filtering, the smallest grid always kept |
| `MessagesFileTest` | Every key the code names, or builds from an enum, filter or frame id, exists in `lang/en.yml` as Bukkit's YAML 1.1 loader reads it; every other language has exactly English's keys, each with the same placeholders |
| `PhotoFramesTest` | Rings drawn inwards, the inside untouched, the source copied, depth capped |
| `PhotoExporterTest` | File names are player input: separators, leading dots, length |
| `ColormapsTest` | Known biome climates' colors, and no climate giving black |
| `BiomeTintTest` | Reference brightness kept, swamp/desert/snow shifting the right way, tinted pixels on the palette |
| `PartialCoverageTest` | Thin-block share, layer cap, the majority rule with nothing behind, a carpet full from above and a sliver from the side |
| `WaterDepthTest` | Depth thresholds and how much a translucent bottom shows |
| `FocusPassTest` | The depth of field pass |
| `FogPassTest` | Near and sky pixels untouched, the fade growing with depth, strength capping it, the palette kept |
| `ColorOpTest` | Filter operations |
| `GridAndSlicingTest` | Tile order of the slicer, only visible once hung; a letterboxed picture centred between transparent bars and its size surviving `photos.yml` |
| `FormatAndIdsTest` | Locale-independent numbers; malformed ids return `null` (a truncated UUID used to parse into a different one) |

## 11. The site

`./gradlew generateWiki` writes `build/site`: the front page (`wiki/site/index.html`) and the wiki
(`wiki/guides/*.md` plus a reference read off the shipped files), drawn with the shared kit from
`zypec/zypec.github.io` (`kit/`: template, `wiki.js`, `wiki.css`, `front.css`, `theme.js`). The
generator reads the kit from `../zypec.github.io/kit` or `-Pkit=<folder>`. `.github/workflows/site.yml`
publishes it to <https://zypec.github.io/izomap/>.

- `<!--config:<section>-->` in a guide expands to every key of that section of `config.yml`, with
  its shipped default and the prose in `wiki/reference/config.yml`. Undocumented keys are marked on
  the page and reported by the build, and so are prose entries for keys that no longer exist.
- `<!--permissions-->` expands to every node `paper-plugin.yml` declares.
- Gallery pictures come from the development server: `wiki/gallery/shots.yml` lists their views and
  `/izodev shots` reproduces them (see `CLAUDE.md`). The folder is copied beside the site whole; the
  pictures are shown on the front page only, not in the wiki.
- **The try-it page** (`wiki/site/try.html`) is a camera in the browser over photos the plugin took:
  `/izodev try <view>` shoots every style, every sky with and without its bodies, fog and depth of
  field on and off (102 pictures at 1024x576, named `try-<style>-<sky>[-b][-fog][-dof]`), kept in
  `wiki/gallery/try/` as palette PNGs. Two settings are done by the page, and both are exact: the
  aspect ratio, because an orthographic camera at a narrower ratio casts the same rays as the middle
  of the wider frame; and the color filter, through `filters.json` (`/izodev palettes`), the
  plugin's own table from each map color to its filtered one, applied to the pixels the same
  view's transparent-sky shot says are not sky (the sky is never filtered). It differs from a real
  filtered shot only where fog, focus or the shader style blended colors after the filter would have
  run.
  The matrix renders fast because every shot is the same view: one `ChunkCache.keeping()` (copies
  never expire, every capture waits for all of its chunks, unlike the preview's) is filled by the
  first shot and read by the rest, three at a time, with `render-threads` at the core count. On an
  11-core M3 Pro that took 79 shots in 293 s against about 20 s a shot one by one; a stopped run
  resumes, keeping the shots already on disk.
- **The name is one face everywhere** (`accent.css`, `fonts/`): Helvetica Neue Bold through `local()`
  where the device has it (Apple's), else TeX Gyre Heros Bold, a free Helvetica, subset to Basic
  Latin (10 KB) and renamed "Izomap Wordmark" as its GUST license asks of a derived font. Windows
  used to draw the name in Arial.

