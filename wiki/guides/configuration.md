# Configuration

Every key of `config.yml`, with its default. Open a row for what it does in detail. The keys that change how a photo looks (shading, water, sky, focus, fog) are on [Rendering](#rendering).

Edit `plugins/Izomap/config.yml` and run `/izocam reload`; no restart is needed. A value outside its range is pulled back into it.

### File version

<!--config:version-->

### Limits and cost

These decide what one photo may cost the server. [Performance](#performance) has measurements.

<!--config:settings-->

### Cameras

How a camera looks in the world and how it responds to clicks.

<!--config:camera-->

### The capture screen

<!--config:dialog-->

### Hanging photos

<!--config:placement-->

### The other files

| File | What it holds |
|---|---|
| `lang/en.yml`, `lang/tr.yml` | Every message, in game and in the console, one file per language. See [Messages](#messages) |
| `filters.yml` | The color filters. See [Filters](#filters) |
| `frames.yml` | The decoration frames. See [Frames](#frames) |
| `block-colors.yml` | Block color overrides, thin-block coverage and biome tint channels. See [Block colors](#block-colors) |
| `biome-tints.yml` | Biome color overrides. See [Biome tints](#biome-tints) |
| `cameras.yml`, `photos.yml` | The plugin's own records. Do not edit them while the server runs |
| `photos/` | One `.izm` file per photo, its image |
| `exports/` | PNGs written by `/izocam export` |

A new version of the plugin never rewrites your files. A key you do not have takes its default until you add it.
