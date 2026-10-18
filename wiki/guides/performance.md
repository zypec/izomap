# Performance

What a photo costs your server, and the settings that decide it.

### Where the time goes

A photo is taken in two halves, and only one of them touches the main thread.

| Half | Thread | Bound by | Grows with |
|---|---|---|---|
| **Chunk copy** | Main | Disk (loading) and memory | The area the frame covers |
| **Ray walk** | Render pool | CPU | Pixels × supersampling², and how far each ray travels |

Chunks that are not loaded are loaded asynchronously, and each one is copied the moment it arrives. The ray walk runs on `settings.render-threads` threads of its own and never blocks a tick. Turn on `settings.render-timing` to see both halves of every capture in the log.

### Measured

On an Apple M3 Pro, Paper 26.3, the world the gallery was shot in, `render-threads: 8`, `supersampling: 2`, sun shadow, ambient occlusion and block light all on, water in `DEPTH` mode. One player online.

| Shot | Size | Chunks | Copy | Ray walk |
|---|---|---:|---:|---:|
| Isometric, tilt 35°, zoom 0.4 | 512x256 (4x2) | 680 | 0.2 s | 1.0 s |
| Isometric, tilt 35°, zoom 0.6 | 512x384 (4x3) | 269 | 0.1 s | 1.1 s |
| Isometric, tilt 35°, zoom 0.55 | 1024x640 (8x5) | 300 to 540 | 0.1 to 0.2 s | 4 to 6.5 s |
| Isometric with depth of field | 1024x640 (8x5) | 356 | 0.1 s | 4.5 to 6.5 s |
| Horizon, tilt 3°, zoom 0.31 | 1024x512 (8x4) | 6,289 | 3.0 s | 52 s |
| Horizon, tilt 3°, zoom 0.31 | 2048x1152 (16x9) | 5,765 | 2.0 s | 171 s |

Two things stand out:

- **The tilt matters as much as the size.** A camera looking down stops its rays on the ground after a few dozen blocks. A camera looking at the horizon sends them out to `settings.max-capture-area`, through thousands of chunks, and the same pixel count costs ten times as much.
- **The copy is small next to the walk**, but it is the half that runs on the main thread. Keep `settings.max-capture-area` where a horizon shot's copy stays acceptable for your server.

### The settings that decide the cost

| Setting | Effect on cost |
|---|---|
| `settings.max-map-tiles` and `izomap.max_map_tiles.<n>` | Pixels. A 16x9 photo is 144 times a 1x1 |
| `settings.max-capture-area` | Chunks copied and how far rays may travel; grows with its square |
| `photo.supersampling` | Rays per pixel, N squared: `1` costs a quarter of `2` |
| Style `FAST` (`photo.style.fast-scale`) | A quarter of the rays at 0.5, softer image |
| `photo.shading.sun-shadow` | A second ray per hit, short (`shadow-distance`) |
| `photo.focus.samples` | Cost of the depth of field pass, linear |
| `settings.render-threads` | Faster photos, more CPU taken at once |

Filters, skies, biome tint and partial coverage cost next to nothing.

### Profiles

**Small or busy server.** `max-capture-area: 384`, `max-map-tiles: 8`, `supersampling: 1`, `render-threads: 2`. Give `izomap.style.sharp` to nobody by default.

**Normal server.** The defaults.

**Showcase or build server.** `max-capture-area: 1024` or more, `izomap.max_map_tiles.144` to builders, `render-threads` up to half your cores, every shading option on.

### The live preview

The preview renders a single 128x128 map at the camera's ratio, once per change, however many players watch. Changes that arrive while a render runs are folded into one more render, never queued one by one, so clicking fast costs no more than clicking once.
