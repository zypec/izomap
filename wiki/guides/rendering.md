# Rendering

How a photo is drawn, and every `photo.*` key that changes it.

### How a photo is made

A photo is taken in two halves. On the main thread, the plugin works out which chunks the frame covers and copies them. Off the main thread, it casts a ray through that copy for every pixel (several with supersampling), finds the block it hits and the face it enters, and turns that into a color. The main thread never waits for the second half, which is most of the work.

Every color is the vanilla map palette's own: the block's map color, made brighter or darker by the face the ray entered (top, the two sides, bottom), passed through the color filter and matched to the 244 colors a map can hold. Each pixel of a photo is a real map color, which is why it looks the same on the wall as it did in the preview.

A photo is saved as the image it was taken as, so changing these later never changes a photo already on a wall. A retake uses the values in force at the time.

### Frame and quality

<!--config:photo-->

### What each option costs

| Option | Visual gain | Cost |
|---|---|---|
| `supersampling: 2` | Smooth edges | 4x the rays of `1` |
| `shading.sun-shadow` | Light has a direction; buildings cast shadows | A second ray per hit |
| `shading.ambient-occlusion` | Inner corners read as corners | Four block reads per hit |
| `shading.block-light` | Caves and interiors are dark | One read per hit; the chunk copy carries light |
| `coverage.enabled` | Grass and flowers stop looking like noise | Next to nothing |
| `biome-tint.enabled` | Swamps look like swamps | Next to nothing, after the first pixel of each block and biome |
| `water.mode: DEPTH` | Coastlines and depth | The cells of water each ray crosses |
| `focus` | Tilt-shift and subject separation | A second pass over the image; `samples` sets it |
| `fog.enabled` | Depth: far land fades into the sky | A light pass over the image |
| A color filter | A look | Nothing |
| A sky | A background | Nothing |

The palette has four shades per color and nothing in between. Each shading option moves a surface down a whole shade or leaves it alone, and the steps add up to the darkest shade. That is why they are off by default: they change the image, and how much shadow suits a server is a matter of taste.
